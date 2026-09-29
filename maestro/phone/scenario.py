"""Сценарии на телефонах: элементы с проверкой, сторож времени, сброс.

Навык и правила — `.cursor/skills/phone-scenario-runner/`. Коротко:

- **Элемент** приводит телефоны к названному состоянию и сам проверяет, что привёл.
  Проверка — по журналу приложения (adb), а не по тому, что кнопка нажалась.
- **Способ** у элемента с нажатиями — `adb` или `maestro`. Какой лучше, решает журнал
  попыток навыка и заказчик, а не этот файл: здесь оба.
- **Сторож** снаружи элемента: элемент идёт отдельным процессом, и превысивший предел
  останавливается со всем деревом процессов. Дальше — **сброс**: телефоны приводятся в
  безопасное состояние (звонков нет, окно звонка закрыто, приложение впереди, драйвера
  Maestro нет), и сброс проверяется тем же журналом.

Запуск:

    python maestro/phone/scenario.py element ready --dev samsung --method adb
    python maestro/phone/scenario.py element presets --dev samsung --file <presets.json>
    python maestro/phone/scenario.py element bench --dev samsung --method maestro --preset "T h264 500 off" --expect "Прогон 1 из 16"
    python maestro/phone/scenario.py call --caller samsung --callee redmi --peer "redmi, @test_redmi" --hang caller --method adb --hold 60
    python maestro/phone/scenario.py series ... --calls 16

Отчёт — строками JSON в `--out` (по умолчанию `doc_add/тесты-звонков/сценарии/<время>/`).
"""
import argparse
import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import phone as ph  # noqa: E402

# Пределы времени элементов, секунды. Меняются здесь, а не в вызове: предел — часть
# определения элемента.
LIMITS = {
    'ready': 90,
    'presets': 90,
    'bench': 200,
    'enter': 220,
    'exit': 200,  # проверка со сбросом журналов обоих — до трёх попыток (PH-25)
    'reset': 120,
}
# Maestro медленнее: каждый его сценарий начинается подготовкой run.sh и подъёмом драйвера.
MAESTRO_EXTRA = {'exit': 160}
MAESTRO_BIN = os.path.expanduser('~/maestro/bin')


# ── ОТЧЁТ ───────────────────────────────────────────────────────────────────

class Report:
    def __init__(self, out):
        self.out = out
        os.makedirs(out, exist_ok=True)
        self.path = os.path.join(out, 'report.jsonl')

    def add(self, row):
        with open(self.path, 'a', encoding='utf-8') as f:
            f.write(json.dumps(row, ensure_ascii=False) + '\n')
        mark = {'достигнуто': 'OK', 'не достигнуто': 'НЕТ', 'превышено время': 'ВРЕМЯ'}.get(row['итог'], row['итог'])
        print('%s  %-8s %-7s %-6s %5.1f с / %d  %s  %s' % (
            row['начало'][11:19], row['элемент'], row['способ'], mark, row['секунд'], row['предел'],
            ','.join(row['телефоны']), row.get('заметка', '')), flush=True)


# ── MAESTRO ─────────────────────────────────────────────────────────────────

def maestro(flow, dev, env=None, limit=120):
    """Сценарий Maestro на одном телефоне — через `maestro/run.sh`, как велит инструкция.
    KEEP_AWAKE=1: настройки экрана возвращаются один раз, в конце серии."""
    e = dict(os.environ)
    e['PATH'] = MAESTRO_BIN + os.pathsep + e.get('PATH', '')
    e['KEEP_AWAKE'] = '1'
    for k, v in (env or {}).items():
        e['MAESTRO_' + k] = v
    shell = shutil.which('sh') or 'sh'
    try:
        r = subprocess.run([shell, os.path.join(ROOT, 'maestro', 'run.sh'), 'phone/flows/' + flow, ph.PHONES[dev]],
                           capture_output=True, timeout=limit, env=e, cwd=ROOT)
    except subprocess.TimeoutExpired:
        return False, 'maestro дольше %d с' % limit
    out = r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace')
    tail = [l for l in out.splitlines() if l.strip()][-6:]
    return r.returncode == 0, ' | '.join(tail)


# ── ЭЛЕМЕНТЫ (выполняются в дочернем процессе) ──────────────────────────────
#
# Каждый возвращает (достигнуто: bool, доказательство: list[str], заметка: str).

def act_ready(a):
    dev = ph.PHONES[a['dev']]
    notes = []
    ph.wake(dev)
    drivers = ph.maestro_drivers(dev)
    if drivers and a['method'] == 'adb':
        ph.stop_drivers(dev)
        notes.append('выгружен драйвер Maestro (мешает дереву экрана), порты %s' % ','.join(p for _, p in drivers))
    if a['method'] == 'maestro':
        # Драйвер Maestro не выгружается между шагами — «тёплый» запуск втрое быстрее
        # (МОДЕЛИ.md). Дерево экрана через adb при живом драйвере не читается (PH-6),
        # поэтому проверка здесь — сценарием (assert `tab:Chats`) и без дерева.
        ok, tail = maestro('ready.yaml', a['dev'])
        notes.append('maestro: ' + ('прошёл' if ok else 'упал: ' + tail))
        ev = [ph.focus(dev)]
        good = ok and ph.awake(dev) and ph.APP in ev[0] and not ph.in_call(dev)
        return good, ev + ['главное окно: %s (сценарий)' % ('да' if ok else 'нет')], '; '.join(notes)
    else:
        ph.to_front(dev)
        ns = ph.nodes(dev)
        if not ph.find(ns, rid='tab:Chats'):
            # окно стенда или звонка: из стенда «Назад» закрывает приложение (PH-3) —
            # значит сразу обратно на передний план
            if ph.find(ns, 'Закрыть'):
                ph.tap(dev, 'Закрыть', wait=2)
            else:
                ph.sh(dev, 'input keyevent KEYCODE_BACK')
                time.sleep(1)
            ph.to_front(dev)
    ev = [ph.focus(dev)]
    chats = ph.find(ph.nodes(dev), rid='tab:Chats') is not None
    ok = ph.awake(dev) and ph.APP in ev[0] and chats and not ph.in_call(dev)
    ev.append('главное окно: %s' % ('да' if chats else 'нет'))
    return ok, ev, '; '.join(notes)


def act_presets(a):
    dev = ph.PHONES[a['dev']]
    ps = os.path.join(ROOT, 'push-bench-presets.ps1')
    r = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ps,
                        '-File', a['file'], '-Serial', dev], capture_output=True, timeout=80)
    want = ph.local_sha(a['file'])
    got = ph.remote_sha(dev, 'files/test/presets.json')
    return want == got, ['на ПК %s' % want[:16], 'на телефоне %s' % got[:16]], ''


def act_bench(a):
    dev = ph.PHONES[a['dev']]
    if a['method'] == 'maestro':
        ph.hide_keyboard(dev)  # не дерево экрана — тёплому драйверу не мешает
        ph.sh(dev, 'cmd statusbar collapse')
        # сценарий сам проверяет «Остановить прогон», EXPECT и `tab:Chats`; драйвер тёплый
        ok, tail = maestro('bench.yaml', a['dev'], {'PRESET': a['preset'], 'EXPECT': a['expect']}, limit=140)
        return ok, ['maestro: ' + ('прошёл' if ok else 'упал')], tail if not ok else ''
    ph.stop_drivers(dev)
    ph.to_front(dev)
    ph.hide_keyboard(dev)
    ns = ph.nodes(dev)
    # окно завершённого звонка («Звонок завершён» / «Закрыть») — закрыть, оно поверх всего
    if ph.find(ns, 'Закрыть') and ph.find(ns, 'Перезвонить'):
        ph.tap(dev, 'Закрыть', wait=3)
        time.sleep(1.5)
        ns = ph.nodes(dev)
    # окно стенда может быть уже открыто — приложение возвращается, где его оставили
    if not (ph.find(ns, 'Остановить прогон') or ph.find(ns, 'Начать прогон') or ph.find(ns, 'Наборы')):
        if not ph.tap(dev, 'Телефон', wait=5):
            return False, ['нет плашки «Телефон»'], ''
        time.sleep(1)
        # на Redmi список окон длиннее экрана — «Стенд звонков» ниже края (PH-12)
        item = ph.scroll_to(dev, 'Стенд звонков', tries=4)
        if not item:
            return False, ['нет «Стенд звонков» в переключателе'], ''
        ph.tap_node(dev, item)
        time.sleep(2)
    else:
        # к началу окна — там кнопка вооружения
        w, h = ph.size(dev)
        for _ in range(6):
            ph.sh(dev, 'input swipe %d %d %d %d 200' % (w // 2, int(h * 0.3), w // 2, int(h * 0.85)))
    ns = ph.nodes(dev)
    if ph.find(ns, 'Начать прогон'):
        ph.tap(dev, 'Начать прогон', wait=2)
        time.sleep(1)
        ns = ph.nodes(dev)
    armed = ph.find(ns, 'Остановить прогон') is not None
    chip = ph.scroll_to(dev, __import__('re').escape(a['preset']))
    if not chip:
        return False, ['набор «%s» не найден' % a['preset']], ''
    # имя набора стоит и в поле названия, и на плашке; плашка — последняя в дереве
    same = [n for n in ph.nodes(dev) if n['text'] == a['preset']]
    ph.tap_node(dev, same[-1])
    time.sleep(1.2)
    seen = ph.find(ph.nodes(dev), r'Прогон \d+ из \d+')
    seen = seen['text'] if seen else '—'
    ph.sh(dev, 'input keyevent KEYCODE_BACK')
    time.sleep(1)
    ph.to_front(dev)
    chats = ph.find(ph.nodes(dev), rid='tab:Chats') is not None
    ok = armed and seen == a['expect'] and chats
    return ok, ['вооружён: %s' % armed, 'на экране: %s' % seen, 'главное окно: %s' % chats], ''


def _accept_adb(dev, box):
    n = ph.tap(dev, 'Принять', wait=45)
    box['accept'] = 'нажато' if n else '«Принять» не появилось'


def _accept_maestro(name, box):
    ok, tail = maestro('call-accept.yaml', name, limit=190)
    box['accept'] = 'maestro прошёл' if ok else 'maestro упал: ' + tail


def act_enter(a):
    """Вход в звонок: принимающий ждёт «Принять», звонящий нажимает «📹» у собеседника.
    Одно действие с развилкой по роли. Проверка здесь — по экрану (окно звонка открыто у
    того, чей экран читается): журнал ещё в памяти (PH-17). Журналом «Вход» проверяется в
    «Выходе», после сброса журналов."""
    caller, callee = ph.PHONES[a['caller']], ph.PHONES[a['callee']]
    # отсечка — по часам каждого телефона: часы Redmi отстают от Samsung на ~10 с (PH-22)
    t0, t0e = ph.now(caller), ph.now(callee)
    box = {}
    if a['method'] == 'adb':
        for d in (caller, callee):
            ph.stop_drivers(d)
    if a['method'] == 'maestro':
        t = threading.Thread(target=_accept_maestro, args=(a['callee'], box))
    else:
        t = threading.Thread(target=_accept_adb, args=(callee, box))
    t.start()
    time.sleep(12 if a['method'] == 'maestro' else 2)  # драйверу принимающего — подняться
    if a['method'] == 'maestro':
        query = a.get('query') or a['peer'].split(',')[0]
        ok, tail = maestro('call-start.yaml', a['caller'], {'PEER': a['peer'], 'QUERY': query}, limit=100)
        started = 'maestro прошёл' if ok else 'maestro упал: ' + tail
    else:
        started = _start_adb(caller, a['peer'])
    # Страховка PH-10: адресат сверяется сразу после набора, а не в «Выходе». Звонок не
    # тому кладётся немедленно — 2026-09-29 Maestro трижды позвонил чужому номеру (PH-19).
    if a.get('peer_id'):
        for _ in range(3):
            began = ph.has(ph.journal(caller, t0, fresh=True), 'CALL звонок начат')
            if began:
                break
            time.sleep(2)
        wrong = [l for l in began if 'кому=' + a['peer_id'] not in l]
        if wrong:
            ph.hang_up(caller)
            box['stop'] = True
            return False, [l[:140] for l in wrong], 'звонок ушёл НЕ тому собеседнику — положен сразу'
    t.join(timeout=190)
    if a['method'] == 'maestro':
        # дерево экрана при тёплом драйвере не читается — «соединён» по журналам обоих
        ok = False
        for _ in range(4):
            jc = ph.journal(caller, t0, fresh=True)
            je = ph.journal(callee, t0e, fresh=True)
            cc, ce = ph.has(jc, 'стадия звонка  стала=Connected'), ph.has(je, 'стадия звонка  стала=Connected')
            if cc and ce:
                ok = True
                break
            time.sleep(4)
        ev = ['соединён: звонящий %s, принимающий %s' % ('да' if cc else 'НЕТ', 'да' if ce else 'НЕТ')]
        return ok, ev, 'звонящий: %s; принимающий: %s' % (started, box.get('accept', 'нет ответа'))
    # окно звонка — у того, чей экран читается во время видео (Honor — нет, PH-4)
    watch = a['callee'] if a['caller'] == 'honor' else a['caller']
    seen = False
    for _ in range(10):
        try:
            if ph.call_screen(ph.nodes(ph.PHONES[watch])):
                seen = True
                break
        except Exception:
            pass
        time.sleep(2)
    ev = ['окно звонка у %s: %s' % (watch, 'открыто' if seen else 'НЕТ')]
    return seen, ev, 'звонящий: %s; принимающий: %s' % (started, box.get('accept', 'нет ответа'))


def _start_adb(dev, peer):
    ph.to_front(dev)
    if not ph.tap(dev, rid='tab:Contacts', wait=5):
        return 'нет вкладки «Контакты»'
    time.sleep(1.5)
    n = ph.scroll_to(dev, peer, tries=5)
    if not n:
        return 'нет строки «%s»' % peer
    ns = ph.nodes(dev)
    n = ph.find(ns, peer)
    # кнопка той же строки: её рамка по высоте накрывает имя собеседника
    btn = [x for x in ns if x['id'] == 'book:video-call' and x['b'][1] <= n['y'] <= x['b'][3]]
    if not btn:
        return 'нет «📹» в строке собеседника'
    ph.tap_node(dev, btn[0])
    return 'нажато «📹»'


def act_hold(a):
    """Разговор держится `hold` с; снимки обоих на середине. Проверка по журналу — в «Выходе»."""
    time.sleep(a['hold'] / 2)
    shots = []
    for name in (a['caller'], a['callee']):
        try:
            ph.shot(ph.PHONES[name], os.path.join(a['out'], '%02d-%s.png' % (a['no'], name)))
            shots.append(name)
        except Exception:
            pass
    time.sleep(a['hold'] / 2)
    return True, ['снимки: %s' % (', '.join(shots) or 'нет')], ''


def act_exit(a):
    """Выход: положить трубку у того, чей экран читается (Honor во время видео — нет, PH-4),
    закрыть окна у обоих. Проверка — «звонок закончен/кончился» и «следующий набор» у обоих."""
    hanger = a['caller'] if a['hang'] == 'caller' else a['callee']
    other = a['callee'] if hanger == a['caller'] else a['caller']
    hd, od = ph.PHONES[hanger], ph.PHONES[other]
    note = ''
    if a['method'] == 'maestro':
        box = {}
        # оба сценария — одновременно: каждый с подготовкой run.sh идёт около минуты
        th = threading.Thread(target=lambda: box.setdefault('o', maestro('call-close.yaml', other, limit=150)))
        th.start()
        ok1, tail = maestro('call-hangup.yaml', hanger, limit=150)
        th.join(timeout=155)
        note = 'maestro: трубка %s, окно у второго %s' % ('да' if ok1 else 'нет: ' + tail,
                                                        'да' if box.get('o', (False,))[0] else 'нет')
    else:
        for d in (hd, od):
            ph.stop_drivers(d)
        n = ph.hang_up(hd)  # только в окне звонка (PH-10)
        time.sleep(2)
        c1 = ph.tap(hd, 'Закрыть', wait=8)
        try:
            c2 = ph.tap(od, 'Закрыть', wait=8)
        except Exception:
            c2 = None
        note = 'трубка %s, окна %s/%s' % ('да' if n else 'нет', 'да' if c1 else 'нет', 'да' if c2 else 'нет')
    time.sleep(3)
    # Весь звонок проверяется здесь, по сброшенным журналам обоих (PH-17). Конец звонка
    # принимающий узнаёт от сервера с задержкой — до трёх попыток через 5 с (PH-25).
    for attempt in range(3):
        ok, ev, note2 = _exit_check(a)
        if ok:
            break
        time.sleep(5)
    return ok, ev, note + note2


def _exit_check(a):
    ev, ok, nums, names = [], True, [], []
    for name, d in ((a['caller'], ph.PHONES[a['caller']]), (a['callee'], ph.PHONES[a['callee']])):
        j = ph.journal(d, a['since'][name], fresh=True)
        conn = ph.has(j, 'стадия звонка  стала=Connected')
        cam = ph.has(j, 'своя камера  включена=true')
        out = ph.has(j, 'уходящее видео')
        pr = ph.has(j, 'прогон стенда начат')
        end = ph.has(j, 'CALL звонок закончен') + ph.has(j, 'CALL звонок кончился')
        nxt = ph.has(j, 'следующий набор забега')
        nums.append(nxt[-1].split('номер=')[-1].split()[0] if nxt else '—')
        names.append(pr[-1].split('пресет=')[-1].strip() if pr else '—')
        # итог элемента — механика звонка; камера и уходящее видео — замер стенда, а не
        # провал сценария: «уходящее видео 0» — находка (Redmi на «h264 800 k», 2026-09-29)
        part = bool(conn) and bool(end) and bool(nxt)
        if name == a['caller']:
            began = ph.has(j, 'CALL звонок начат')
            video = [l for l in began if 'видео=true' in l]
            right = [l for l in began if not a.get('peer_id') or 'кому=' + a['peer_id'] in l]
            part = part and len(began) == 1 and bool(video) and len(right) == len(began)
            ev.append('%s: начат %s' % (name, began[-1][24:95] if began else 'НЕТ'))
        ok = ok and part
        ev.append('%s: соединён %s, камера %s, «уходящее видео» %d, набор %s, конец %s, следующий %s' % (
            name, 'да' if conn else 'НЕТ', 'да' if cam else 'НЕТ', len(out), names[-1],
            'да' if end else 'НЕТ', nums[-1]))
    same = nums[0] == nums[1] and names[0] == names[1]
    return ok and same, ev, ('' if same else '; наборы или номера разные')


def act_reset(a):
    """Сброс: звонков нет, окна звонка закрыты, драйверов Maestro нет, приложение впереди."""
    names = a['devs']
    ev = []
    for name in names:
        ph.stop_drivers(ph.PHONES[name])
    # положить трубку у того, чей экран читается; Honor во время видео не читается (PH-4).
    # Только «📞» окна звонка — в «Контактах» тот же значок звонит (PH-10).
    for name in sorted(names, key=lambda n: n == 'honor'):
        d = ph.PHONES[name]
        if any(ph.in_call(ph.PHONES[n]) for n in names):
            try:
                if ph.hang_up(d):
                    ev.append('%s: трубка положена' % name)
                    time.sleep(3)
            except Exception as e:
                ev.append('%s: экран не читается (%s)' % (name, str(e)[:60]))
    for name in names:
        d = ph.PHONES[name]
        try:
            ph.tap(d, 'Закрыть', wait=3)
        except Exception:
            pass
        ph.to_front(d)
    busy = [n for n in names if ph.in_call(ph.PHONES[n])]
    ev.append('звонок идёт: %s' % (', '.join(busy) or 'ни у кого'))
    return not busy, ev, ''


ACTS = {'ready': act_ready, 'presets': act_presets, 'bench': act_bench, 'enter': act_enter,
        'hold': act_hold, 'exit': act_exit, 'reset': act_reset}


# ── СТОРОЖ ──────────────────────────────────────────────────────────────────

def kill_tree(pid):
    subprocess.run(['taskkill', '/T', '/F', '/PID', str(pid)], capture_output=True)


def run(name, args, report, limit=None, devs=()):
    """Элемент отдельным процессом под сторожем. Превышен предел — процесс со всеми
    дочерними (adb, maestro, sh) останавливается, и итог — «превышено время»."""
    limit = limit or LIMITS.get(name, 120)
    if args.get('method') == 'maestro':
        limit += MAESTRO_EXTRA.get(name, 0)
    start = dt.datetime.now().strftime('%Y-%m-%dT%H:%M:%S')
    t = time.time()
    p = subprocess.Popen([sys.executable, '-X', 'utf8', __file__, '_act', name, json.dumps(args, ensure_ascii=False)],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        out, err = p.communicate(timeout=limit)
        try:
            ok, ev, note = json.loads(out.decode('utf-8').strip().splitlines()[-1])
            verdict = 'достигнуто' if ok else 'не достигнуто'
        except Exception:
            ok, ev, note = False, [err.decode('utf-8', 'replace')[-300:]], 'элемент упал'
            verdict = 'не достигнуто'
    except subprocess.TimeoutExpired:
        kill_tree(p.pid)
        ok, ev, note, verdict = False, [], 'остановлен сторожем', 'превышено время'
    report.add({'начало': start, 'секунд': round(time.time() - t, 1), 'предел': limit, 'элемент': name,
                'способ': args.get('method', 'adb'), 'телефоны': list(devs), 'итог': verdict,
                'доказательство': ev, 'заметка': note, 'номер': args.get('no')})
    return ok


def reset(devs, report):
    return run('reset', {'devs': list(devs)}, report, devs=devs)


def call(a, report, no):
    """Один звонок: Вход → Разговор → Выход. Не достигнут элемент — сброс."""
    pair = (a['caller'], a['callee'])
    base = dict(caller=a['caller'], callee=a['callee'], peer=a['peer'], peer_id=a.get('peer_id'), query=a.get('query'), hang=a['hang'], method=a['method'],
                out=report.out, no=no)
    since = {n: ph.now(ph.PHONES[n]) for n in pair}
    # прошлый звонок не кончился — не набирать (PH-7)
    if any(ph.in_call(ph.PHONES[n]) for n in pair) and not reset(pair, report):
        return False
    if not run('enter', base, report, devs=pair):
        reset(pair, report)
        return False
    if not run('hold', dict(base, hold=a['hold'], since=since), report, limit=a['hold'] + 60, devs=pair):
        pass  # замер плохой — но звонок кладём штатно, это не авария
    if not run('exit', dict(base, since=since), report, devs=pair):
        reset(pair, report)
        return False
    return True


def main():
    if len(sys.argv) > 1 and sys.argv[1] == '_act':
        a = json.loads(sys.argv[3])
        try:
            res = ACTS[sys.argv[2]](a)
        except Exception as e:
            res = (False, ['исключение: %s' % str(e)[:200]], '')
        print(json.dumps(list(res), ensure_ascii=False))
        return
    ap = argparse.ArgumentParser()
    ap.add_argument('what', choices=['element', 'call', 'series'])
    ap.add_argument('name', nargs='?')
    ap.add_argument('--dev')
    ap.add_argument('--method', default='adb', choices=['adb', 'maestro'])
    ap.add_argument('--file')
    ap.add_argument('--preset')
    ap.add_argument('--expect')
    ap.add_argument('--caller')
    ap.add_argument('--callee')
    ap.add_argument('--peer')
    ap.add_argument('--peer-id', dest='peer_id')
    ap.add_argument('--query')
    ap.add_argument('--hang', default='caller', choices=['caller', 'callee'])
    ap.add_argument('--hold', type=int, default=60)
    ap.add_argument('--calls', type=int, default=1)
    ap.add_argument('--out')
    o = ap.parse_args()
    out = o.out or os.path.join(ROOT, 'doc_add', 'тесты-звонков', 'сценарии', dt.datetime.now().strftime('%Y-%m-%d_%H%M%S'))
    report = Report(out)
    print('отчёт:', report.path, flush=True)
    if o.what == 'element':
        args = {k: v for k, v in vars(o).items() if v is not None and k not in ('what', 'name')}
        ok = run(o.name, args, report, devs=[o.dev] if o.dev else [])
        sys.exit(0 if ok else 1)
    a = vars(o)
    fails = 0
    for no in range(1, o.calls + 1):
        ok = call(a, report, no)
        fails = 0 if ok else fails + 1
        if fails >= 2:
            print('два звонка подряд не достигнуты — серия остановлена на №%d' % no, flush=True)
            sys.exit(1)
        time.sleep(4)
    sys.exit(0)


if __name__ == '__main__':
    main()
