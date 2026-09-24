# -*- coding: utf-8 -*-
"""
Кириллица в именах КОДА — проверка.

Правило CLAUDE.md: «Кириллицы нет в именах файлов кода, переменных, функций и методов»
(решение заказчика 2026-08-25). Проверка `architecture-tests/LatinTest` смотрит только
имена ФАЙЛОВ, и за месяц после перехода 2026-08-26 в коде набралось 37 кириллических
имён — никто не заметил. Этот скрипт смотрит внутрь.

Что проверяется
    Имена в коде: переменные, функции, классы, параметры, константы и их упоминания.

Что НЕ проверяется
    - строки, символы, комментарии и KDoc — там русский и должен быть;
    - тесты целиком (решение заказчика 2026-09-24): тестовые наборы, каталог
      `client/test/`, `architecture-tests`, `*_test.go`.

Запуск
    python .claude/hooks/latin_names.py ФАЙЛ...     проверить названные файлы
    python .claude/hooks/latin_names.py --all       весь код репозитория
    python .claude/hooks/latin_names.py --hook      как хук Claude Code: путь из stdin

Выход: 0 — чисто, 2 — нашлось (список `файл:строка: имя` в stderr).
Код 2 выбран ради хука: Claude Code показывает агенту stderr именно при нём.
"""
import io
import json
import os
import re
import sys

CYR = re.compile(r'[Ѐ-ӿ]')
IDENT = re.compile(r'[A-Za-z_Ѐ-ӿ][\wЀ-ӿ]*')

CODE = ('.kt', '.kts', '.go', '.sq', '.sqm', '.proto')

# Тестовые наборы и тестовые модули. Решение заказчика 2026-09-24: тесты не проверяются.
TEST_PARTS = (
    '/commontest/', '/jvmtest/', '/androidtest/', '/iostest/', '/androidinstrumentedtest/',
    '/androidunittest/', '/src/test/', '/client/test/', '/architecture-tests/',
)

SKIP_DIRS = {'.git', 'build', '.gradle', '.kotlin', 'node_modules', 'third-party', 'fixtures'}

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))


def is_test(path):
    p = '/' + os.path.abspath(path).replace('\\', '/').lower() + '/'
    if p.rstrip('/').endswith('_test.go'):
        return True
    return any(part in p for part in TEST_PARTS)


def checked(path):
    return path.lower().endswith(CODE) and not is_test(path)


# ── Разметка: код отдельно, строки и комментарии отдельно ───────────────────
#
# Возвращает список (это_код, текст, номер_строки_начала). Шаблоны строк Kotlin
# (`$имя`, `${...}`) возвращаются как КОД: внутри шаблона стоит имя, и оно под правилом.

def segments(src, kind):
    out, buf = [], []
    i, n, line = 0, len(src), 1
    buf_line = 1

    def flush():
        nonlocal buf
        if buf:
            out.append((True, ''.join(buf), buf_line))
            buf = []

    def emit_text(text, at):
        out.append((False, text, at))

    while i < n:
        c = src[i]
        start_line = line

        # комментарии
        if (kind in ('kt', 'go', 'proto') and src.startswith('//', i)) or (kind == 'sq' and src.startswith('--', i)):
            flush()
            j = src.find('\n', i)
            j = n if j < 0 else j
            emit_text(src[i:j], start_line)
            i = j
            continue
        if src.startswith('/*', i):
            flush()
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            text = src[i:j]
            emit_text(text, start_line)
            line += text.count('\n')
            i = j
            continue

        # строки Kotlin с шаблонами
        if kind == 'kt' and (src.startswith('"""', i) or c == '"'):
            flush()
            raw = src.startswith('"""', i)
            q = '"""' if raw else '"'
            i += len(q)
            while i < n:
                if not raw and src[i] == '\\':
                    i += 2
                    continue
                if src.startswith(q, i):
                    i += len(q)
                    break
                if src.startswith('${', i):
                    depth, j = 1, i + 2
                    while j < n and depth:
                        if src[j] == '{':
                            depth += 1
                        elif src[j] == '}':
                            depth -= 1
                        j += 1
                    # Внутри шаблона — код, но в нём бывают свои строки:
                    # `"${reason ?: "не сохранена"}"`. Поэтому содержимое размечается
                    # той же разметкой, а не считается кодом целиком.
                    inner = src[i + 2:j - 1]
                    for part_code, part_text, part_line in segments(inner, kind):
                        out.append((part_code, part_text, line + part_line - 1))
                    line += src[i:j].count('\n')
                    i = j
                    continue
                if src[i] == '$' and i + 1 < n and (src[i + 1] == '_' or src[i + 1].isalpha()):
                    j = i + 1
                    while j < n and (src[j] == '_' or src[j].isalnum()):
                        j += 1
                    out.append((True, src[i + 1:j], line))
                    i = j
                    continue
                if src[i] == '\n':
                    line += 1
                i += 1
            buf_line = line
            continue

        # строки и символы остальных языков
        if c == '"' or (c == "'" and kind in ('kt', 'go', 'sq')) or (c == '`' and kind == 'go'):
            flush()
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\' and q != '`' and kind != 'sq':
                    j += 2
                    continue
                if src[j] == q:
                    # в SQL '' внутри строки — это кавычка, а не конец
                    if kind == 'sq' and j + 1 < n and src[j + 1] == q:
                        j += 2
                        continue
                    j += 1
                    break
                j += 1
            text = src[i:j]
            emit_text(text, start_line)
            line += text.count('\n')
            i = j
            buf_line = line
            continue

        if not buf:
            buf_line = line
        buf.append(c)
        if c == '\n':
            line += 1
        i += 1
    flush()
    return out


def kind_of(path):
    p = path.lower()
    if p.endswith(('.kt', '.kts')):
        return 'kt'
    if p.endswith('.go'):
        return 'go'
    if p.endswith(('.sq', '.sqm')):
        return 'sq'
    return 'proto'


def hits(path):
    """Кириллические имена в коде файла: список (строка, имя)."""
    try:
        src = io.open(path, encoding='utf-8').read()
    except (OSError, UnicodeDecodeError):
        return []
    found = []
    for is_code, text, at in segments(src, kind_of(path)):
        if not is_code:
            continue
        # Имена в обратных кавычках Kotlin — тоже имена: обратные кавычки лишь разрешают
        # в имени то, что иначе нельзя, а кириллицу правило запрещает и так.
        line = at
        pos = 0
        for m in IDENT.finditer(text):
            line += text.count('\n', pos, m.start())
            pos = m.start()
            if CYR.search(m.group(0)):
                found.append((line, m.group(0)))
    return found


def rel(path):
    try:
        return os.path.relpath(os.path.abspath(path), ROOT).replace('\\', '/')
    except ValueError:
        return path


def check(paths):
    problems = []
    for path in paths:
        if not os.path.isfile(path) or not checked(path):
            continue
        for line, name in hits(path):
            problems.append('%s:%d: %s' % (rel(path), line, name))
    return problems


def all_code():
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            path = os.path.join(base, f)
            if checked(path):
                yield path


def main(argv):
    # Консоль Windows не обязана уметь в UTF-8; вывод — всегда в нём.
    out = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

    if argv[:1] == ['--hook']:
        try:
            event = json.load(io.TextIOWrapper(sys.stdin.buffer, encoding='utf-8'))
        except ValueError:
            return 0
        tool = event.get('tool_input') or {}
        path = tool.get('file_path') or tool.get('path') or ''
        paths = [path] if path else []
    elif argv[:1] == ['--all']:
        paths = list(all_code())
    else:
        paths = argv

    problems = check(paths)
    if not problems:
        return 0
    out.write('Кириллица в именах кода — правило CLAUDE.md «Соглашения репозитория»:\n')
    for p in problems:
        out.write('  ' + p + '\n')
    out.write(
        'Имена — латиницей и по смыслу, не транслитом. Русский остаётся в строках и '
        'комментариях. Тесты не проверяются (решение заказчика 2026-09-24).\n'
    )
    out.flush()
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
