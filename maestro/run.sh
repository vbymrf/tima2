#!/bin/sh
# Прогон сценария Maestro на стендовых телефонах: подготовка, задача, завершение.
#
# ── ПОЧЕМУ СКРИПТ, А НЕ КОМАНДА РУКАМИ ──────────────────────────────────────
#
# Команда, набранная руками, каждый раз чуть другая, и знание о том, чем она должна
# отличаться, живёт в голове того, кто её набирает. За два дня это стоило двух неверных
# выводов, записанных в документацию как правила: «Maestro не умеет параллельно» и
# «таймаут драйвера обязателен». Причина у обоих одна — незамеченный погасший экран.
#
# Поэтому подготовка пришита к запуску. Забыть её теперь нельзя.
#
# ── ТРИ ФАЗЫ ────────────────────────────────────────────────────────────────
#
# ПОДГОТОВКА   запомнить, как телефон был настроен, ДО первой правки;
#              разбудить и не дать гаснуть; проверить Play Защиту;
#              сверить версию драйвера с версией Maestro.
# ЗАДАЧА       собственно прогон, по ветке на телефон.
# ЗАВЕРШЕНИЕ   вернуть настройки, какими были; сказать, где артефакты.
#
# Завершение выполняется ВСЕГДА, в том числе когда прогон упал или его прервали:
# иначе телефон остаётся с суточным экраном и садится за ночь. Samsung за час
# прогонов 2026-09-16 ушёл с 48 % до 38 %.
#
# ── СОСТОЯНИЕ ───────────────────────────────────────────────────────────────
#
# ~/.maestro/tima-stand/<серийный>.env — как было до нас. Ключ серийный, а НЕ адрес:
# адрес по сетевому adb содержит случайный порт и меняется при переподключении, так что
# файл, названный адресом, потерялся бы вместе с ним.
#
# Файл пишется ОДИН раз, при первом знакомстве с телефоном. Перезапись убила бы смысл:
# на втором прогоне «как было» стало бы уже нашей собственной правкой.
#
# Использование:  sh maestro/run.sh update.yaml                     — все телефоны
#                 sh maestro/run.sh update.yaml 192.168.3.226:35273 — один
#                 KEEP_AWAKE=1 sh maestro/run.sh update.yaml        — не возвращать
#                                                                     (серия прогонов)
set -u

FLOW="${1:?нужен сценарий, например: sh maestro/run.sh update.yaml}"
shift

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
export ADB
STATE="$HOME/.maestro/tima-stand"
mkdir -p "$STATE"

MAESTRO_VERSION=$(maestro -v 2>/dev/null | tr -d '\r' | head -1)
[ -n "$MAESTRO_VERSION" ] || { echo "maestro не отвечает на -v" >&2; exit 1; }

get() { "$ADB" -s "$1" shell "settings get $2" 2>/dev/null | tr -d '\r\n'; }
put() { "$ADB" -s "$1" shell "settings put $2" >/dev/null 2>&1; }
serial_of() { "$ADB" -s "$1" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n'; }

# ── ПОДГОТОВКА ──────────────────────────────────────────────────────────────
prepare_one() {
    dev="$1"
    "$ADB" connect "$dev" >/dev/null 2>&1
    serial=$(serial_of "$dev")
    [ -n "$serial" ] || { echo "$dev не отвечает" >&2; return 1; }
    f="$STATE/$serial.env"

    if [ ! -f "$f" ]; then
        printf 'screen_off_timeout=%s\nstay_on_while_plugged_in=%s\n'             "$(get "$dev" 'system screen_off_timeout')"             "$(get "$dev" 'global stay_on_while_plugged_in')" > "$f"
        echo "  $dev ($serial): запомнено как было -> $f"
    fi

    # Play Защита — решение заказчика 2026-09-15, стоит постоянно. Здесь только
    # проверка: молча вернувшееся окно проверки останавливает прогон в середине.
    if [ "$(get "$dev" 'global package_verifier_user_consent')" != "-1" ]; then
        echo "  $dev: ВНИМАНИЕ, Play Защита включена — прогон встанет на её окне" >&2
    fi

    put "$dev" "system screen_off_timeout 86400000"
    put "$dev" "global stay_on_while_plugged_in 7"
    "$ADB" -s "$dev" shell "dumpsys deviceidle disable" >/dev/null 2>&1

    # Драйвер Maestro оставляем на телефоне (--no-reinstall-driver), поэтому его версия
    # может разойтись с версией Maestro после её обновления. Разошлась — сносим руками,
    # и флаг поставит свежий. Проверено: на телефоне без драйвера флаг его ставит.
    d="$STATE/$serial.driver"
    if [ ! -f "$d" ] || [ "$(cat "$d" 2>/dev/null)" != "$MAESTRO_VERSION" ]; then
        echo "  $dev: драйвер ставился другой версией Maestro — сношу, флаг поставит свежий"
        "$ADB" -s "$dev" uninstall dev.mobile.maestro >/dev/null 2>&1
        "$ADB" -s "$dev" uninstall dev.mobile.maestro.test >/dev/null 2>&1
        printf '%s' "$MAESTRO_VERSION" > "$d"
    fi

    sh "$HERE/wake-phone.sh" "$dev" || return 1
}

# ── ЗАВЕРШЕНИЕ ──────────────────────────────────────────────────────────────
finish_one() {
    dev="$1"
    serial=$(serial_of "$dev")
    f="$STATE/$serial.env"
    [ -f "$f" ] || return 0
    was_off=$(sed -n 's/^screen_off_timeout=//p' "$f")
    was_stay=$(sed -n 's/^stay_on_while_plugged_in=//p' "$f")
    case "$was_off" in ''|null|*[!0-9]*) was_off="" ;; esac
    case "$was_stay" in ''|null|*[!0-9]*) was_stay="" ;; esac
    [ -n "$was_off" ] && put "$dev" "system screen_off_timeout $was_off"
    [ -n "$was_stay" ] && put "$dev" "global stay_on_while_plugged_in $was_stay"
    "$ADB" -s "$dev" shell "dumpsys deviceidle enable" >/dev/null 2>&1
    echo "  $dev: экран возвращён ($was_off мс), Doze включён"
}

finish_all() {
    if [ "${KEEP_AWAKE:-0}" = "1" ]; then
        echo "── ЗАВЕРШЕНИЕ: пропущено, KEEP_AWAKE=1 — телефоны останутся бодрыми"
        return 0
    fi
    echo "── ЗАВЕРШЕНИЕ"
    for dev in $LIST; do finish_one "$dev"; done
}

if [ "$#" -gt 0 ]; then
    LIST=$(echo "$1" | tr ',' ' ')
else
    LIST=$("$ADB" devices | grep -v "List of" | awk '$2 == "device" {print $1}')
fi
[ -n "$LIST" ] || { echo "ни один телефон не отвечает" >&2; exit 1; }

echo "── ПОДГОТОВКА (Maestro $MAESTRO_VERSION)"
READY=""
for dev in $LIST; do
    prepare_one "$dev" && READY="$READY $dev"
done
LIST=$(echo "$READY" | tr -s ' ' | sed 's/^ //')
[ -n "$LIST" ] || { echo "ни один телефон не подготовился" >&2; exit 1; }

# Завершение обязано случиться и при падении, и при Ctrl-C — отсюда ловушка.
trap 'finish_all' EXIT INT TERM

SHARDS=$(echo "$LIST" | wc -w | tr -d ' ')
DEVICES=$(echo "$LIST" | tr ' ' ',')

# Таймаут подъёма драйвера — потолок ожидания, а не пауза: обмерено 2026-09-16, на
# разбуженном телефоне прогон без него идёт 123 с, с ним 109 и 120 с. Оставлен как
# страховка медленного случая, стоит ноль.
export MAESTRO_DRIVER_STARTUP_TIMEOUT="${MAESTRO_DRIVER_STARTUP_TIMEOUT:-240000}"

echo "── ЗАДАЧА: $FLOW, телефонов $SHARDS: $DEVICES"
maestro --device "$DEVICES" test --shard-all="$SHARDS" --no-reinstall-driver "$HERE/$FLOW"
CODE=$?

echo "── Артефакты: $(ls -dt "$HOME"/.maestro/tests/*/ 2>/dev/null | head -1)"
exit "$CODE"
