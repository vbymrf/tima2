"""Проба кодеров на телефоне: открыть стенд, нажать «Проверить кодеры», дождаться отчёта, забрать.

    python -X utf8 maestro/phone/probe_run.py samsung [redmi ...] --out <папка>

Сторож — на телефон не дольше LIMIT секунд. Отчёт — `<папка>/probe-<телефон>.md`.
"""
import argparse
import os
import re
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone as ph  # noqa: E402

LIMIT = 900


def open_bench(dev):
    ph.wake(dev)
    ph.stop_drivers(dev)
    ph.to_front(dev)
    # «Телефон» есть и на рабочем столе — системная звонилка; жать только в своём приложении
    for _ in range(3):
        if ph.APP in ph.focus(dev):
            break
        ph.to_front(dev)
    else:
        return False
    ph.hide_keyboard(dev)
    ns = ph.nodes(dev)
    if ph.find(ns, 'Закрыть') and ph.find(ns, 'Перезвонить'):
        ph.tap(dev, 'Закрыть', wait=3)
        time.sleep(1.5)
        ns = ph.nodes(dev)
    if not (ph.find(ns, 'Остановить прогон') or ph.find(ns, 'Начать прогон') or ph.find(ns, 'Наборы')):
        if not ph.tap(dev, 'Телефон', wait=5):
            return False
        time.sleep(1)
        item = ph.scroll_to(dev, 'Стенд звонков', tries=4)
        if not item:
            return False
        ph.tap_node(dev, item)
        time.sleep(2)
    return True


def run_one(name, out, log):
    dev = ph.PHONES[name]
    start = time.time()
    # экран не должен гаснуть посреди пробы; прежнее значение возвращается в конце
    was = ph.sh(dev, 'settings get system screen_off_timeout').strip()
    ph.sh(dev, 'settings put system screen_off_timeout 600000')
    try:
        _run(name, dev, out, log, start)
    finally:
        if was.isdigit():
            ph.sh(dev, 'settings put system screen_off_timeout ' + was)


def _run(name, dev, out, log, start):
    if not open_bench(dev):
        log(name, 'окно стенда не открылось')
        return
    button = ph.scroll_to(dev, 'Проверить кодеры', tries=15)
    if not button:
        log(name, 'кнопки «Проверить кодеры» нет')
        return
    ph.tap_node(dev, button)
    log(name, 'проба запущена')
    path = None
    while time.time() - start < LIMIT:
        time.sleep(10)
        try:
            ns = ph.nodes(dev)
        except Exception:
            continue
        done = ph.find(ns, r'Отчёт пробы: .*')
        going = ph.find(ns, r'Идёт проба: .*')
        if done:
            path = done['text'].split(': ', 1)[1]
            break
        if going:
            log(name, going['text'])
    if not path:
        log(name, 'время вышло — отчёта нет')
        return
    text = ph.adb(dev, 'exec-out', 'run-as', ph.APP, 'cat', 'files/test/' + os.path.basename(path), limit=60)
    target = os.path.join(out, 'probe-%s.md' % name)
    open(target, 'w', encoding='utf-8').write(text)
    log(name, 'отчёт: %s, %d с' % (target, time.time() - start))
    ph.sh(dev, 'input keyevent KEYCODE_BACK')
    time.sleep(1)
    ph.to_front(dev)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('phones', nargs='+')
    ap.add_argument('--out', required=True)
    o = ap.parse_args()
    os.makedirs(o.out, exist_ok=True)
    lock = threading.Lock()

    def log(name, msg):
        with lock:
            print(time.strftime('%H:%M:%S'), name, msg, flush=True)

    threads = [threading.Thread(target=run_one, args=(n, o.out, log)) for n in o.phones]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


if __name__ == '__main__':
    main()
