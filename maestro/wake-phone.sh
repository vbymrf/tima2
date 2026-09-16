#!/bin/sh
# Не дать телефону уснуть и убедиться, что экран блокировки ушёл.
#
# ── ЗАЧЕМ ЭТО ЕСТЬ ──────────────────────────────────────────────────────────
#
# Спящий телефон — самая дорогая беда прогонов на этом стенде, и она умеет
# притворяться другими бедами. 2026-09-16 три параллельные ветки Maestro упали, и это
# выглядело как «параллельно не умеет»; на снимке падения оказался ЧЁРНЫЙ ЭКРАН, а
# `screen_off_timeout` — 30 000 у Redmi и 60 000 у realme. После этого скрипта та же
# параллельная пара прошла 2 из 2. Вывод, сделанный без снимка, был бы неверным.
#
# ── ЧТО СТАВИТСЯ ────────────────────────────────────────────────────────────
#
# `stay_on_while_plugged_in 7` — «не гасить экран при питании» (USB + сеть +
# беспроводная зарядка), та же галка из меню разработчика. **Переживает перезагрузку**,
# и это правка чужого устройства: вернуть — тем же с `0`.
#
# `screen_off_timeout` — сутки. Нужен отдельно: телефон на стенде может и не стоять на
# зарядке, а по сетевому adb «подключён» не значит «питается».
#
# `dumpsys deviceidle disable` — Doze не должен усыплять драйвер Maestro. До перезагрузки.
#
# ── ПОЧЕМУ С ПРОВЕРКОЙ, А НЕ ПРОСТО КОМАНДЫ ────────────────────────────────
#
# `wm dismiss-keyguard` срабатывает не всегда с первого раза: если экран ещё гаснет или
# открыта шторка, команда проходит, а замок остаётся, и прогон падает на первом шаге.
# Поэтому здесь «послать и убедиться»: пока в фокусе замок или шторка — повторяем. Три
# попытки, дальше пусть падает громко.
#
# Имена переменных латиницей — правило репозитория (CLAUDE.md). Первая редакция была
# кириллицей и не работала вовсе: `sh` такие имена за переменные не считает.
#
# Использование:  sh maestro/wake-phone.sh <адрес>          — один телефон
#                 sh maestro/wake-phone.sh --all            — все, что отвечают
set -u

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"

keep_awake() {
    DEVICE="$1"
    "$ADB" connect "$DEVICE" >/dev/null 2>&1
    "$ADB" -s "$DEVICE" shell "settings put global stay_on_while_plugged_in 7" >/dev/null 2>&1
    "$ADB" -s "$DEVICE" shell "settings put system screen_off_timeout 86400000" >/dev/null 2>&1
    "$ADB" -s "$DEVICE" shell "dumpsys deviceidle disable" >/dev/null 2>&1
}

focus() {
    "$ADB" -s "$1" shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r'
}

wake() {
    DEVICE="$1"
    keep_awake "$DEVICE"
    try=1
    while [ "$try" -le 3 ]; do
        "$ADB" -s "$DEVICE" shell "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
        sleep 1
        "$ADB" -s "$DEVICE" shell "wm dismiss-keyguard" >/dev/null 2>&1
        sleep 1
        # Свайп вверх — то же, что делает палец: на части прошивок замок снимается им.
        "$ADB" -s "$DEVICE" shell "input swipe 360 1200 360 300 200" >/dev/null 2>&1
        sleep 1
        # HOME закрывает шторку, если она открылась от свайпа.
        "$ADB" -s "$DEVICE" shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1
        sleep 1

        F=$(focus "$DEVICE")
        case "$F" in
            *Keyguard*|*NotificationShade*|*"mCurrentFocus=null"*)
                try=$((try + 1))
                ;;
            *)
                echo "$DEVICE готов: $(echo "$F" | sed 's/.*mCurrentFocus=//' | cut -c1-52)"
                return 0
                ;;
        esac
    done
    echo "$DEVICE НЕ разбудился за три попытки, в фокусе: $(focus "$DEVICE")" >&2
    return 1
}

if [ "${1:---all}" = "--all" ]; then
    bad=0
    for d in $("$ADB" devices | grep -v "List of" | awk '$2 == "device" {print $1}'); do
        wake "$d" || bad=1
    done
    exit "$bad"
fi

wake "$1"
