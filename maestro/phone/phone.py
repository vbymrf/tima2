"""Телефон через adb: команды с пределом времени, дерево экрана, журнал приложения.

Общий слой для элементов сценария (`scenario.py`). Каждая команда adb идёт со своим
пределом времени: зависание одной команды не должно вешать элемент — его остановит
сторож, но лучше, чтобы до сторожа не доходило.

Навык: `.cursor/skills/phone-scenario-runner/`.
"""
import datetime as dt
import hashlib
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

APP = 'io.tima.app.v2'
ACTIVITY = APP + '/io.tima.app.MainActivity'

# Имена телефонов стенда. Адреса беспроводных меняются при переподключении — тогда
# правится здесь; серийный номер Honor (провод) постоянен.
PHONES = {
    'samsung': '192.168.3.226:46013',
    'redmi': '192.168.3.235:36727',
    'realme': '192.168.3.193:43521',
    'honor': '3PQNU19B13323918',
}


class AdbTimeout(Exception):
    pass


def adb(dev, *args, binary=False, limit=30):
    try:
        r = subprocess.run(['adb', '-s', dev, *args], capture_output=True, timeout=limit)
    except subprocess.TimeoutExpired:
        raise AdbTimeout('adb %s: дольше %d с' % (' '.join(args[:3]), limit))
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def sh(dev, cmd, limit=30):
    return adb(dev, 'shell', cmd, limit=limit)


# ── ЭКРАН ───────────────────────────────────────────────────────────────────

def dump(dev):
    """Дерево экрана. Не работает, пока драйвер Maestro держит доступ к экрану
    (правило PH-6) и на Honor во время видео (PH-4)."""
    out = ''
    for _ in range(3):
        out = adb(dev, 'exec-out', 'uiautomator dump /dev/tty', limit=30)
        i, j = out.find('<?xml'), out.rfind('</hierarchy>')
        if i >= 0 and j > 0:
            return ET.fromstring(out[i:j + len('</hierarchy>')])
        time.sleep(1)
    raise RuntimeError('дерево экрана не снято: ' + out.strip()[-120:])


def nodes(dev):
    res = []
    for n in dump(dev).iter('node'):
        b = re.findall(r'\d+', n.get('bounds', ''))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        res.append(dict(text=n.get('text', ''), desc=n.get('content-desc', ''), id=n.get('resource-id', ''),
                        x=(x1 + x2) // 2, y=(y1 + y2) // 2, b=(x1, y1, x2, y2)))
    return res


def find(ns, pattern=None, rid=None):
    for n in ns:
        if rid and n['id'] == rid:
            return n
        if pattern and (re.fullmatch(pattern, n['text']) or re.fullmatch(pattern, n['desc'])):
            return n
    return None


def tap_node(dev, n):
    sh(dev, 'input tap %d %d' % (n['x'], n['y']))


def tap(dev, pattern=None, rid=None, wait=10.0):
    """Найти и нажать; ждать появления до `wait` с. Узел с нулевой рамкой — не нажимаем
    (PH-5: такой пункт на экране есть, а координат у него нет)."""
    end = time.time() + wait
    while True:
        n = find(nodes(dev), pattern, rid)
        if n and n['b'][2] > n['b'][0]:
            tap_node(dev, n)
            return n
        if time.time() > end:
            return None
        time.sleep(0.7)


def size(dev):
    """Размер экрана **с учётом поворота** — по рамке корня дерева. `wm size` отдаёт
    размер без поворота, и на лежащем боком Redmi прокрутка уходила за край (2026-09-29)."""
    root = dump(dev)
    w = h = 0
    for n in root.iter('node'):
        b = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
        if len(b) == 4:
            w, h = max(w, b[2]), max(h, b[3])
    return w, h


def hide_keyboard(dev):
    """Спрятать клавиатуру: жест прокрутки по ней печатает буквы (2026-09-29, Redmi — в поле
    названия набора стенда попало «by»)."""
    if 'mInputShown=true' in sh(dev, 'dumpsys input_method | grep -m1 mInputShown'):
        sh(dev, 'input keyevent KEYCODE_BACK')
        time.sleep(0.8)


def scroll_to(dev, pattern=None, tries=10, rid=None):
    hide_keyboard(dev)
    w, h = size(dev)
    for _ in range(tries):
        n = find(nodes(dev), pattern, rid)
        if n and 0.12 * h < n['y'] < 0.9 * h:
            return n
        sh(dev, 'input swipe %d %d %d %d 300' % (w // 2, int(h * 0.75), w // 2, int(h * 0.35)))
        time.sleep(0.8)
    return None


def shot(dev, path):
    open(path, 'wb').write(adb(dev, 'exec-out', 'screencap -p', binary=True, limit=30))


# ── ПРИЛОЖЕНИЕ И ТЕЛЕФОН ────────────────────────────────────────────────────

def wake(dev):
    """Разбудить и снять блокировку. `dismiss-keyguard` снимает не везде: на Samsung нужен
    ещё жест вверх, как пальцем (так делает и `maestro/wake-phone.sh`)."""
    sh(dev, 'input keyevent KEYCODE_WAKEUP')
    time.sleep(0.5)
    sh(dev, 'wm dismiss-keyguard')
    time.sleep(0.5)
    if 'NotificationShade' in focus(dev) or 'Keyguard' in focus(dev):
        w, h = [int(v) for v in re.findall(r'(\d+)x(\d+)', sh(dev, 'wm size'))[-1]]
        sh(dev, 'input swipe %d %d %d %d 200' % (w // 2, int(h * 0.8), w // 2, int(h * 0.2)))
        time.sleep(1)


def to_front(dev):
    """Приложение на передний план — `am start`, а не `monkey` (PH-8)."""
    sh(dev, 'am start -n %s' % ACTIVITY)
    time.sleep(2)


def focus(dev):
    out = sh(dev, 'dumpsys window | grep -m1 mCurrentFocus')
    return out.strip()


def awake(dev):
    # Samsung пишет «getWakefulnessLocked()=Awake», другие — «mWakefulness=Awake»
    line = sh(dev, 'dumpsys power | grep -m1 -i "wakefulness.*="')
    return '=Awake' in line


def maestro_drivers(dev):
    """Процессы драйвера Maestro на телефоне: [(pid, порт)]."""
    out = sh(dev, 'for p in $(pidof app_process); do echo "$p $(tr "\\0" " " < /proc/$p/cmdline)"; done')
    res = []
    for line in out.splitlines():
        if 'dev.mobile.maestro' in line:
            m = re.search(r'-e port (\d+)', line)
            res.append((line.split()[0], m.group(1) if m else '?'))
    return res


def stop_drivers(dev):
    for pid, _ in maestro_drivers(dev):
        sh(dev, 'kill %s' % pid)


def remote_sha(dev, path):
    out = adb(dev, 'exec-out', 'run-as', APP, 'sha256sum', path, limit=30)
    return out.split()[0] if out.strip() else ''


def local_sha(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


# ── ЖУРНАЛ ПРИЛОЖЕНИЯ — ИСТОЧНИК ПРОВЕРОК ───────────────────────────────────

def now(dev):
    """Время телефона в формате строк журнала (UTC)."""
    return sh(dev, 'date -u +%Y-%m-%dT%H:%M:%S').strip()


def flush(dev):
    """Сбросить журнал приложения на диск. Журнал копит записи в памяти и пишет их при беде,
    каждые 50 записей, при уходе в фон и при отчёте (`Diary.kt`, PH-17). Уход в фон — штатный
    способ: «Домой» и обратно; звонок при этом не обрывается.

    Сброс **подтверждается**: в журнале должна появиться свежая «APP-BACKGROUND». Без этого
    на Redmi сброс в «Выходе» молча не срабатывал, и проверка не видела конца звонка (PH-25)."""
    for _ in range(3):
        mark = now(dev)
        sh(dev, 'input keyevent KEYCODE_HOME')
        done = False
        # ждём только свежую «APP-BACKGROUND» в хвосте журнала: `dumpsys window` на каждом
        # шаге на Honor и realme стоил секунды, и сброс пары не укладывался в предел
        for _ in range(10):
            time.sleep(0.8)
            tail = adb(dev, 'exec-out', 'run-as', APP, 'sh', '-c',
                       'tail -3 files/logs/$(ls files/logs | tail -1)', limit=20)
            if any('APP-BACKGROUND' in l and l[:19] >= mark for l in tail.splitlines()):
                done = True
                break
        to_front(dev)
        if done:
            return True
    return False


def journal(dev, since, fresh=False):
    """Строки журнала приложения, начиная с `since` (время телефона, UTC).
    `fresh` — сначала сбросить журнал на диск (иначе хвост может быть в памяти)."""
    if fresh:
        flush(dev)
    names = adb(dev, 'exec-out', 'run-as', APP, 'ls', 'files/logs', limit=20).split()
    names = sorted(n for n in names if n.endswith('.txt'))[-2:]
    lines = []
    for n in names:
        text = adb(dev, 'exec-out', 'run-as', APP, 'cat', 'files/logs/' + n, limit=60)
        lines += [l for l in text.splitlines() if l[:19] >= since]
    return sorted(lines)


def has(lines, *parts):
    return [l for l in lines if all(p in l for p in parts)]


def in_call(dev):
    """Идёт ли звонок — по последнему событию начала и конца в журнале (сброшенном)."""
    since = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%S')
    last = None
    for l in journal(dev, since, fresh=True):
        if 'CALL звонок начат' in l or 'CALL входящий звонок' in l:
            last = 'start'
        elif 'CALL звонок закончен' in l or 'CALL звонок кончился' in l:
            last = 'end'
    return last == 'start'


# ── МЕСТО И ПОДОКНО (ПЛАН-(МО), 2026-10-09) ─────────────────────────────────────
#
# Состояние экрана — пара меток: место `screen:<код>` (окно, вкладка, подэкран — всегда одно) и
# подокно `popup:<код>` (что открыто поверх; ничего — `popup:none`). Коды те же, что приложение
# пишет в журнал. Списки мест и подокон, как попасть и как закрыть, —
# doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/СЦЕНАРИИ-ПО-ОКНАМ.md, раздел «Места и подокна».


def screen_of(ns):
    """Пара меток экрана целиком: ('screen:Phone.Chats', 'popup:none'). Меток нет — None на их
    месте: вне приложения или сборка без меток окон. Целиком, а не кодами: так их находит тест
    `ScenarioTagsTest` — метка из сценария обязана быть в коде и в учёте."""
    place = next((n['id'] for n in ns if n['id'].startswith('screen:')), None)
    popup = next((n['id'] for n in ns if n['id'].startswith('popup:')), None)
    return place, popup


# Нулевая точка — главное окно, поверх ничего (ПЛАН-(МО) МО5).
HOME = ('screen:Phone.Chats', 'popup:none')

# Чем закрыть подокно — метка кнопки; нет в словаре — «Назад». Учёт, раздел «Места и подокна».
CLOSE_POPUP = {'popup:switcher': 'switcher:close', 'popup:search': 'search:close'}
# Подокна, которые сценарий закрыть не вправе или не умеет: пин-код, обязательное обновление.
STOP_POPUPS = ('popup:pin.lock', 'popup:update.required')


def to_home(dev, debug_dir=None, steps=6):
    """Стандартное действие до нулевой точки HOME (ПЛАН-(МО) МО5, решение заказчика 2026-10-09):
    не то подокно — закрыть; не то место — перейти заново; ни то, ни другое — стоп.

    Каждое подокно закрывается и каждое место покидается **по одному разу**: второй раз то же —
    действие не помогает, стоп (иначе ходим по кругу). При отладке (`debug_dir`) перед
    исправлением — запись: снимок и пара; при обычной работе — только исправление.

    Возвращает (True | False | None, заметки). None — меток экрана нет вовсе при приложении
    впереди: сборка без меток окон, решает вызывающий прежним способом."""
    notes, seen = [], set()
    for step in range(steps):
        # Открытая клавиатура съедает «Назад»: оно прячет её, а место не меняется — и правило
        # «тот же переход дважды — стоп» сработало бы зря (переписка после пачки, 2026-10-09).
        hide_keyboard(dev)
        place, popup = screen_of(nodes(dev))
        if (place, popup) == HOME:
            return True, notes
        if place is None:
            if APP not in focus(dev) and 'вне' not in seen:
                seen.add('вне')
                to_front(dev)
                continue
            return (None if APP in focus(dev) else False), notes + ['меток экрана нет']
        if debug_dir:
            shot(dev, os.path.join(debug_dir, 'пара-%d-%s.png' % (step, dev.replace(':', '_'))))
            notes.append('%s + %s' % (place, popup))
        if popup != 'popup:none':
            if popup in seen or popup in STOP_POPUPS:
                return False, notes + ['подокно %s не закрыть — стоп' % popup]
            seen.add(popup)
            if not (popup in CLOSE_POPUP and tap(dev, rid=CLOSE_POPUP[popup], wait=3)):
                sh(dev, 'input keyevent KEYCODE_BACK')
        else:
            if place in seen:
                return False, notes + ['переход из %s не помог — стоп' % place]
            seen.add(place)
            if place == 'screen:call.ended':
                tap(dev, 'Закрыть', rid='call:close', wait=3)
            elif place.startswith('screen:call.'):
                return False, notes + ['идёт звонок — трубку здесь не кладём, стоп']
            elif place.startswith('screen:Phone.'):
                tap(dev, rid='tab:Chats', wait=3)
            elif place[len('screen:')].isupper() and place != 'screen:Bench':
                # другое окно — через переключатель: «Назад» из окна закрывает приложение
                if tap(dev, 'Телефон', rid='window:switcher', wait=3):
                    time.sleep(1)
                    tap(dev, rid='switcher:Phone', wait=3)
            else:
                # подэкран — «Назад»; из стенда «Назад» закрывает приложение (PH-3) — вернуть
                sh(dev, 'input keyevent KEYCODE_BACK')
                time.sleep(1)
                if APP not in focus(dev):
                    to_front(dev)
        time.sleep(1.2)
    return False, notes + ['до главного окна не дошли за %d шагов — стоп' % steps]


# ── ЗВОНОК: ПОЛОЖИТЬ ТРУБКУ БЕЗОПАСНО ────────────────────────────────────────
#
# Значок «📞» есть и в окне звонка («Завершить»), и в каждой строке «Контактов» («позвонить»).
# Нажатие по одному значку на неизвестном экране однажды позвонило чужому контакту
# (2026-09-29, «Брат Игорь»). Поэтому трубка кладётся только в окне звонка, а окно звонка
# узнаётся по кнопке микрофона «🎤», которой в «Контактах» нет.

# ── МЕТКИ, А НЕ НАДПИСИ (ПЛАН-(МС), 2026-10-09) ────────────────────────────────
#
# Окно и кнопки ищутся **по метке** (`testTag`, в дереве — resource-id): вид кнопки меняется,
# метка — нет. 2026-10-09 окно звонка узнавалось по «🎤», а после смены вида панели его не
# стало — звонок висел, пока его не положили руками (PH-32). Надпись или значок остаются
# **запасным путём** (заказчик): `find` берёт узел с меткой ИЛИ с надписью — что есть.
# Учёт, какой сценарий на что опирается, — doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/СЦЕНАРИИ-ПО-ОКНАМ.md.

# Окно звонка в любой стадии: набор, входящий, разговор, конец.
CALL_WINDOW = ('call:panel', 'call:dialing', 'call:cancel', 'call:accept', 'call:close')


def call_screen(ns):
    seen = any(find(ns, rid=r) for r in CALL_WINDOW) or find(ns, '🎤') is not None
    return seen and find(ns, rid='tab:Contacts') is None


def open_call_window(dev):
    """Вернуть окно звонка на передний план: плашка окна → «Звонок» в переключателе."""
    ns = nodes(dev)
    if call_screen(ns):
        return True
    if not tap(dev, 'Телефон', rid='window:switcher', wait=3):
        return False
    time.sleep(1.2)
    if not tap(dev, 'Звонок', rid='switcher:Call', wait=3):
        tap(dev, '✕', rid='switcher:close', wait=2)
        return False
    time.sleep(1.5)
    return call_screen(nodes(dev))


def hang_up(dev):
    """Положить трубку, если идёт звонок; нажимается только «📞» окна звонка."""
    if not open_call_window(dev):
        return False
    ns = nodes(dev)
    phones = [n for n in ns if (n['id'] == 'call:hangup' or n['text'] == '📞') and n['b'][2] > n['b'][0]]
    if not phones:
        return False
    tap_node(dev, max(phones, key=lambda n: n['y']))  # «Завершить» — внизу окна
    return True
