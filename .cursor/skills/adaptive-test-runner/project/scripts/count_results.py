"""Итог тестов клиента — только из свежих отчётов (правила BASE-2 и TEST-006 навыка).

    python .cursor/skills/adaptive-test-runner/project/scripts/count_results.py start
    cd client && ./gradlew jvmTest --rerun :architecture-tests:test --continue
    python .cursor/skills/adaptive-test-runner/project/scripts/count_results.py count

`start` ставит отметку начала прогона. `count` сверяет с ней время **каждого** отчёта
JUnit (`client/**/build/test-results/<вид>/TEST-*.xml`): старые — отдельным списком и не в
сумму. Выход: 0 — всё свежее и зелёное; 1 — есть упавшие или устаревшие; 2 — отчётов нет
или нет отметки начала.

Зачем: прежний подсчёт складывал все XML подряд, а его проверка свежести была заглушкой и
всегда печатала «устаревших 0» (TEST-006). Тесты архитектуры при этом не перезапускались
(TEST-005) — и «N из N» включало старые результаты.
"""
import argparse
import os
import re
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', '..', '..'))
CLIENT = os.path.join(ROOT, 'client')
MARK = os.path.join(CLIENT, 'build', 'test-run-start.txt')
# Какие отчёты входят в итог: jvmTest модулей и test модуля architecture-tests — те задачи,
# что гоняет ворота клиента. Прочие (androidUnitTest и т. п.) лежат от старых прогонов и в
# итог этих ворот не входят.
KINDS = ('jvmTest', 'test')
COUNTS = re.compile(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"')


def reports(kinds):
    for base, dirs, files in os.walk(CLIENT):
        dirs[:] = [d for d in dirs if d not in ('.gradle', 'node_modules', 'src')]
        parts = base.replace('\\', '/').split('/')
        if len(parts) < 3 or parts[-3:-1] != ['build', 'test-results'] or parts[-1] not in kinds:
            continue
        for f in files:
            if f.startswith('TEST-') and f.endswith('.xml'):
                yield os.path.join(base, f)


def start():
    os.makedirs(os.path.dirname(MARK), exist_ok=True)
    with open(MARK, 'w', encoding='utf-8') as out:
        out.write('%.3f\n' % time.time())
    print('отметка начала прогона: %s' % time.strftime('%H:%M:%S'))
    return 0


def count(kinds):
    if not os.path.exists(MARK):
        print('нет отметки начала прогона — сначала `count_results.py start`')
        return 2
    since = float(open(MARK, encoding='utf-8').read().strip())
    tests = skipped = failed = 0
    fresh, stale, broken = [], [], []
    for path in sorted(reports(kinds)):
        rel = os.path.relpath(path, CLIENT).replace('\\', '/')
        if os.path.getmtime(path) < since:
            stale.append(rel)
            continue
        m = COUNTS.search(open(path, encoding='utf-8').read())
        if not m:
            broken.append(rel)
            continue
        fresh.append(rel)
        tests += int(m[1])
        skipped += int(m[2])
        failed += int(m[3]) + int(m[4])
    print('отчётов свежих %d, устаревших %d, нечитаемых %d' % (len(fresh), len(stale), len(broken)))
    print('тестов %d, упало %d, пропущено %d' % (tests, failed, skipped))
    for rel in stale:
        print('  устарел: ' + rel)
    for rel in broken:
        print('  не читается: ' + rel)
    if not fresh:
        return 2
    return 0 if not stale and not broken and failed == 0 else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('what', choices=['start', 'count'])
    ap.add_argument('--kinds', default=','.join(KINDS), help='виды отчётов через запятую')
    a = ap.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(start() if a.what == 'start' else count(tuple(a.kinds.split(','))))


if __name__ == '__main__':
    main()
