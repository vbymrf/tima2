"""Свод прогона стенда по журналам телефонов: по каждому набору и телефону — что ушло.

    python -X utf8 maestro/phone/bench_summary.py --since 2026-09-29T21:40 samsung redmi realme honor

Берёт отрезки «прогон стенда начат пресет=…» … «прогон стенда окончен» из журналов (с
предварительным сбросом, PH-17) и считает: кодек и размеры уходящего видео, провалы строк
«уходящее видео» дольше 5 с, заведения кодера и отказы (строки 1а «кодер заведён / не
завёлся / закрыт» — только при «Кратность 16»), обрезки, «не приходит», «Lost».
Печатает таблицу markdown.
"""
import argparse
import collections
import datetime as dt
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone as ph  # noqa: E402


def ts(line):
    return dt.datetime.strptime(line[:19], '%Y-%m-%dT%H:%M:%S')


def runs(lines):
    cur = None
    for l in lines:
        if 'прогон стенда начат' in l:
            cur = dict(preset=l.split('пресет=')[1].strip(), start=ts(l), lines=[])
        elif cur is not None:
            cur['lines'].append(l)
            if 'прогон стенда окончен' in l:
                cur['end'] = ts(l)
                yield cur
                cur = None


def summarize(run):
    sizes, coders, kb, gaps = [], collections.Counter(), [], []
    prev = None
    for l in run['lines']:
        m = re.search(r'уходящее видео\s+кадр=(\S+) ужато=(\S+) кодер=(.+?) кбит/с=(\S+)', l)
        if not m:
            continue
        t = ts(l)
        if prev and (t - prev).total_seconds() > 5:
            gaps.append(int((t - prev).total_seconds()))
        prev = t
        if not sizes or sizes[-1] != m.group(1):
            sizes.append(m.group(1))
        coders[m.group(3).strip()] += 1
        if m.group(4).isdigit():
            kb.append(int(m.group(4)))
    count = lambda pat: sum(1 for l in run['lines'] if pat in l)
    fails = sorted({re.search(r'размер=(\S+)', l).group(1) for l in run['lines'] if 'кодер не завёлся' in l})
    return dict(
        seconds=int((run['end'] - run['start']).total_seconds()),
        sizes=' → '.join(sizes) or 'нет видео',
        coders=', '.join(sorted(coders)) or '—',
        kbit='%d–%d' % (min(kb), max(kb)) if kb else '—',
        gaps=gaps,
        inits=count('кодер заведён'),
        fails=count('кодер не завёлся'),
        fail_sizes=', '.join(fails),
        crops=count('кодер: обрезка до кратного 16'),
        not_arriving=count('видео собеседника не приходит'),
        lost=count('стала=Lost'),
        mismatch=count('уходит не тот кодек'),
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--since', required=True)
    ap.add_argument('--prefix', default='T ')
    ap.add_argument('phones', nargs='+')
    o = ap.parse_args()
    print('| набор | телефон | с | ушло: размеры | кодер | кбит/с | провалы, с | заведён / отказ | отказ на | обрезок | «не приходит» у него | Lost |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|')
    rows = []
    for name in o.phones:
        lines = ph.journal(ph.PHONES[name], o.since, fresh=True)
        for r in runs(lines):
            if r['preset'].startswith(o.prefix):
                rows.append((r['preset'], name, summarize(r)))
    for preset, name, s in sorted(rows, key=lambda x: (x[0], x[1])):
        print('| %s | %s | %d | %s | %s | %s | %s | %d / %d | %s | %d | %d | %d |' % (
            preset, name, s['seconds'], s['sizes'], s['coders'], s['kbit'],
            ', '.join(map(str, s['gaps'])) or '—', s['inits'], s['fails'], s['fail_sizes'] or '—',
            s['crops'], s['not_arriving'], s['lost']))


if __name__ == '__main__':
    main()
