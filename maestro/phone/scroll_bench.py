"""Плавность прокрутки «Звонков» — методика doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/ПЛАВНОСТЬ-ПРОКРУТКИ.md.

    python -X utf8 maestro/phone/scroll_bench.py --dev redmi [--speeds 2000,3000] [--repeats 5]

Что делает: прогрев по правилу → вилка скоростей (сдвигается за порогом) → на каждой скорости N
росчерков вниз от начала списка → по каждому росчерку время кадра в выбеге (SurfaceFlinger) → медиана и
разброс. Итог — строка для таблицы версий: «2000: 16,5 (16,3–16,8) · 3000: 18,4 (17,9–19,1)».

Записи не считаются: число проехавших строк — физика броска и высота строки, а не скорость приложения.
От списка нужно одно — чтобы он не кончался раньше остановки броска.

Навык: .cursor/skills/phone-scenario-runner/ (PH-48 — кадры только у SurfaceFlinger; PH-49 — росчерк
короче ~80 мс бывает принят за касание; PH-6 — драйвер Maestro не даёт читать дерево экрана).
"""
import argparse
import json
import re
import statistics
import time

import phone as ph

PKG = 'io.tima.app.v2'
# Росчерк — 45 % высоты экрана, но не короче, чем на 80 мс при данной скорости (PH-49).
SHARE = 0.45
MIN_MS = 80
# «Плавно»: среднее время кадра выбега не больше периода экрана + 5 % и пропущенных кадров
# (интервал > 1,5 периода) не больше 5 %.
SMOOTH_SLACK = 1.05
MISSED_SHARE = 0.05


def layer(dev):
    lines = ph.sh(dev, 'dumpsys SurfaceFlinger --list').splitlines()
    old = [x.strip() for x in lines if 'MainActivity$' in x]
    if old:
        return old[0]
    new = [re.search(r'\{(io\.tima\.app\.v2/io\.tima\.app\.MainActivity#\d+) parentId', l) for l in lines]
    return [m.group(1) for m in new if m][0]


def presents(dev, lay):
    out = ph.sh(dev, "dumpsys SurfaceFlinger --latency '%s'" % lay).splitlines()
    period = int(out[0]) / 1e6 if out and out[0].strip().isdigit() else 16.67
    rows = [list(map(int, l.split())) for l in out[1:] if len(l.split()) == 3]
    return period, [r[1] for r in rows if 0 < r[1] < 9e18]


def on_calls(dev):
    return any(re.fullmatch(r'\d\d:\d\d', n['text']) for n in ph.nodes(dev))


def open_calls(dev):
    for _ in range(4):
        if ph.tap(dev, rid='tab:Calls', wait=4) or ph.tap(dev, 'Звонки', wait=2):
            return True
        ph.sh(dev, 'input keyevent KEYCODE_BACK')
        time.sleep(1.5)
        if ph.APP not in ph.focus(dev):
            ph.to_front(dev)
            time.sleep(3)
    return False


def swipe(dev, speed, down=True, dist=None):
    w, h = ph.size(dev)
    dist = dist or max(int(h * SHARE), int(speed * MIN_MS / 1000))
    dist = min(dist, int(h * 0.7))
    ms = max(MIN_MS, int(dist / speed * 1000))
    y0 = int(h * 0.85)
    a, b = (y0, y0 - dist) if down else (y0 - dist, y0)
    ph.sh(dev, 'input swipe %d %d %d %d %d' % (w // 2, a, w // 2, b, ms))
    return ms


def still(dev, lay, limit=10.0):
    end = time.time() + limit
    last, since = None, time.time()
    while time.time() < end:
        _, p = presents(dev, lay)
        cur = p[-1] if p else None
        if cur != last:
            last, since = cur, time.time()
        elif time.time() - since > 0.6:
            return
        time.sleep(0.2)


def to_top(dev, lay):
    for _ in range(4):
        swipe(dev, 8000, down=False)
        time.sleep(0.3)
    still(dev, lay)


def measure(dev, lay, speed):
    """Один росчерк вниз от начала: время кадра выбега и доля пропущенных кадров."""
    to_top(dev, lay)
    ph.sh(dev, 'dumpsys SurfaceFlinger --latency-clear')
    ms = swipe(dev, speed)
    time.sleep(0.3)
    still(dev, lay)
    period, p = presents(dev, lay)
    if len(p) < 3:
        return None
    finger_end = p[0] + ms * 1e6
    tail = [x for x in p if x > finger_end]
    if len(tail) < 6:
        return None  # бросок не случился (касание или остановка) — не в счёт
    gaps = [(b - a) / 1e6 for a, b in zip(tail, tail[1:])]
    return {'мс_на_кадр': statistics.mean(gaps), 'пропущено': sum(g > 1.5 * period for g in gaps) / len(gaps),
            'кадров': len(tail), 'выбег_мс': round((tail[-1] - finger_end) / 1e6), 'период': period}


def warm(dev, lay, check_speed, log):
    """Прогрев: проход вниз и вверх; затем два росчерка на check_speed — расхождение не больше 10 %,
    иначе ещё проход (до трёх раз)."""
    for attempt in range(3):
        for down in (True, False):
            for _ in range(15):
                swipe(dev, 6000, down=down)
                time.sleep(0.4)
            still(dev, lay)
        a, b = measure(dev, lay, check_speed), measure(dev, lay, check_speed)
        if a and b and abs(a['мс_на_кадр'] - b['мс_на_кадр']) <= 0.10 * min(a['мс_на_кадр'], b['мс_на_кадр']):
            log('прогрет за %d прохода(ов): %.1f и %.1f мс на кадр' % (attempt + 1, a['мс_на_кадр'], b['мс_на_кадр']))
            return True
        log('ещё не прогрет: %s / %s' % (a and round(a['мс_на_кадр'], 1), b and round(b['мс_на_кадр'], 1)))
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dev', required=True)
    ap.add_argument('--speeds', default='2000,3000')
    ap.add_argument('--repeats', type=int, default=5)
    ap.add_argument('--step', type=int, default=1000)
    ap.add_argument('--label', default='')
    a = ap.parse_args()
    dev = ph.PHONES.get(a.dev, a.dev)

    def log(*x):
        print(a.dev, *x, flush=True)

    ph.stop_drivers(dev)  # PH-6
    ph.wake(dev)
    ph.to_front(dev)
    time.sleep(3)
    if not open_calls(dev):
        log('«Звонки» не открылись — стоп')
        return
    version = re.search(r'versionName=(\S+)', ph.sh(dev, 'dumpsys package %s' % PKG))
    lay = layer(dev)
    speeds = sorted(int(s) for s in a.speeds.split(','))
    warm(dev, lay, speeds[0], log)
    res = {}

    def run(speed):
        vals = []
        for _ in range(a.repeats * 2):
            if len(vals) >= a.repeats:
                break
            r = measure(dev, lay, speed)
            if not on_calls(dev):
                open_calls(dev)  # росчерк принят за касание строки — открылась переписка (PH-49)
                continue
            if r:
                vals.append(r)
        if not vals:
            return None
        msf = sorted(v['мс_на_кадр'] for v in vals)
        miss = statistics.median(v['пропущено'] for v in vals)
        res[speed] = {'медиана': statistics.median(msf), 'от': msf[0], 'до': msf[-1], 'пропущено': miss,
                      'n': len(vals), 'период': vals[0]['период']}
        r = res[speed]
        log('%d пк/с: %.1f мс на кадр (%.1f–%.1f), пропущено %.0f %%, n=%d' % (
            speed, r['медиана'], r['от'], r['до'], 100 * miss, r['n']))
        return r

    def smooth(r):
        return r['медиана'] <= r['период'] * SMOOTH_SLACK and r['пропущено'] <= MISSED_SHARE

    for s in speeds:
        run(s)
    # вилка за порогом: всё плавно — ступень выше; всё с потерями — ступень ниже (до трёх ступеней)
    for _ in range(3):
        got = [res[s] for s in sorted(res) if res.get(s)]
        if not got:
            break
        if all(smooth(r) for r in got):
            run(max(res) + a.step)
        elif not any(smooth(r) for r in got) and min(res) - a.step >= 500:
            run(min(res) - a.step)
        else:
            break
    line = ' · '.join('%d: %.1f (%.1f–%.1f)' % (s, res[s]['медиана'], res[s]['от'], res[s]['до'])
                      for s in sorted(res) if res.get(s))
    log('ИТОГ', a.label or (version.group(1) if version else '?'), '|', line)
    print(json.dumps({'телефон': a.dev, 'версия': version.group(1) if version else None, 'итог': res},
                     ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
