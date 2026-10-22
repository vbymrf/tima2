# -*- coding: utf-8 -*-
"""
Навык телефонов — до первой команды к телефону.

Решение заказчика 2026-10-22 («Делай хук»). Повод: 2026-10-21 агент гонял сценарии Maestro на трёх
телефонах, не открыв навык `.cursor/skills/phone-scenario-runner`: после сжатия разговора навык выпал
из контекста. Наступил на правила, которые там уже были (PH-15 переменные, PH-20 клавиатура, PH-40…42
метки поля и кнопки), сделал ложный вывод и выбросил сценарии. Указание в CLAUDE.md — текст, его можно
не прочитать; хук — шаг, его не пропустить.

Что делает
    PreToolUse (Bash, PowerShell): первая за сессию команда к телефону — adb, `maestro/run.sh`,
    Maestro, `wake-phone.sh`, скрипты `maestro/phone` — **останавливается**, агенту приходит
    главное навыка и строки `preventive` из его INDEX.md. Повтор той же команды проходит.
    SessionStart (compact): после сжатия разговора отметка снимается — навык снова выпал из
    контекста, и первая команда к телефону снова остановится.

Запуск
    python .claude/hooks/phone_skill.py --hook     PreToolUse: команда из stdin
    python .claude/hooks/phone_skill.py --reset    SessionStart: снять отметку сессии
    python .claude/hooks/phone_skill.py --show     напечатать то, что видит агент

Выход: 0 — пропустить, 2 — остановить (текст в stderr: Claude Code показывает его агенту при коде 2).
"""
import io
import json
import os
import re
import sys
import tempfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
SKILL = os.path.join(ROOT, '.cursor', 'skills', 'phone-scenario-runner')

# Команда к телефону. Упоминание в тексте коммита или в поиске — не команда: такие строки отсекает
# NOT_PHONE ниже.
PHONE = re.compile(
    r'(\badb(\.exe)?["\']?\s+(-s\b|shell\b|install\b|exec-out\b|connect\b|devices\b|pull\b|push\b|uninstall\b|logcat\b))'
    r'|platform-tools[/\\]+adb'  # A=".../platform-tools/adb.exe"; "$A" -s … — так зовут adb в этом проекте
    # Запуск, а не упоминание пути: `sed -n 1,80p maestro/run.sh` читает файл, а не телефон.
    r'|\b(sh|bash)\s+(-\w+\s+)*\S*(maestro/run\.sh|wake-phone\.sh)'
    r'|\bpython3?\s+(-\w+\s+)*\S*(maestro/phone/|scenario\.py|phone\.py)'
    r'|(^|[;&|(]\s*)maestro(\.bat|\.cmd)?\s+(test|hierarchy|--device|-v\b)'
)
# Текстовые команды целиком: сообщение коммита и поиск по тексту упоминают adb, но не зовут его.
NOT_PHONE = re.compile(r'^\s*(git|grep|rg)\b')

# Текст внутри `<<'EOF' … EOF` — данные команды (скрипт правки документа), а не команда: 2026-10-22
# правка SKILL.md с «maestro/run.sh» в тексте была остановлена как команда к телефону.
HEREDOC = re.compile(r'<<-?\s*([\'"]?)(\w+)\1[^\n]*\n.*?\n\s*\2\b', re.S)


def flag(session):
    name = re.sub(r'[^A-Za-z0-9_-]', '', session or 'none')[:64] or 'none'
    return os.path.join(tempfile.gettempdir(), 'tima-phone-skill-' + name + '.flag')


def read(path):
    try:
        with io.open(path, encoding='utf-8') as f:
            return f.read()
    except OSError:
        return ''


def message():
    rows = [l for l in read(os.path.join(SKILL, 'INDEX.md')).splitlines()
            if l.startswith('| PH-') and '| preventive |' in l]
    lines = [
        'ОСТАНОВЛЕНО хуком phone_skill: первая команда к телефону в этой сессии (или после сжатия разговора).',
        '',
        'Перед ней — навык .cursor/skills/phone-scenario-runner:',
        '  1. SKILL.md — главное, подход, где что лежит;',
        '  2. INDEX.md — строки preventive ниже выполнить до запуска; симптом совпал — открыть правило в knowledge/phone.md;',
        '  3. doc_mig/ЖУРНАЛ-И-ОТЛАДКА/ПОДКЛЮЧЕНИЕ.md — до первой команды adb;',
        '  4. Maestro — doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/ИНСТРУКЦИЯ-MAESTRO/.',
        '',
        'Подход (заказчик 2026-10-22): сценарии делаются в задаче по тестированию и сохраняются в flows/ навыка, в git;',
        'на экранах приложения — метки, нет метки — ставится в код и в учёт СЦЕНАРИИ-ПО-ОКНАМ.md; клавиатура — PH-59…PH-62.',
        '',
        'Прочитай, затем повтори команду — второй раз хук её пропустит.',
        '',
        'preventive из INDEX.md (' + str(len(rows)) + '):',
    ] + rows
    return '\n'.join(lines) + '\n'


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    mode = sys.argv[1] if len(sys.argv) > 1 else '--hook'
    if mode == '--show':
        sys.stdout.write(message())
        return 0
    try:
        data = json.loads(sys.stdin.buffer.read().decode('utf-8') or '{}')
    except ValueError:
        return 0
    path = flag(data.get('session_id'))
    if mode == '--reset':
        try:
            os.remove(path)
        except OSError:
            pass
        return 0
    command = HEREDOC.sub('<<…', (data.get('tool_input') or {}).get('command') or '')
    if not PHONE.search(command) or NOT_PHONE.search(command):
        return 0
    if os.path.exists(path):
        return 0
    with io.open(path, 'w', encoding='utf-8') as f:
        f.write(command[:200])
    sys.stderr.write(message())
    return 2


if __name__ == '__main__':
    sys.exit(main())
