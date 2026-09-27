package io.tima.app

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.feature.shell.LoginStart
import java.io.File

/**
 * Запуск при входе в Windows — «Настройки → Разрешения → Автозагрузка» (заказчик 2026-09-27).
 *
 * Раньше выключатель жил в меню значка в трее (У4) — там его не находили. Заказчик просил,
 * чтобы приложение **просило** автозапуск, а не ставило его молча: так и осталось, включает
 * человек.
 *
 * ── ЗАПИСЬ В РЕЕСТР, А НЕ ЯРЛЫК В «АВТОЗАГРУЗКЕ» ────────────────────────────
 *
 * Ярлык пришлось бы создавать средствами оболочки Windows (COM), а это зависимость и своя
 * порция отказов. Значение в `HKCU\...\Run` видно человеку в «Диспетчере задач →
 * Автозагрузка» и выключается им же оттуда — то есть у человека остаётся способ отменить
 * нашу настройку мимо нас, и это правильно. `HKCU`, а не `HKLM`: права администратора нам
 * не нужны и просить их не за что.
 *
 * **Выключение из «Диспетчера задач» значение в `Run` не стирает** — оно пишет отметку в
 * `StartupApproved\Run`. Поэтому «включено» — это значение есть **и** отметки «выключено»
 * нет, а включение снимает и отметку: иначе выключатель в TIMA показывал бы «включено»,
 * а Windows нас не запускала бы.
 *
 * Запуск идёт с ключом [HIDDEN]: при входе в систему окно никто не ждёт, нужен канал —
 * TIMA встаёт сразу в трей.
 */
object Autostart : LoginStart {

    /** Ключ командной строки: запущены автозагрузкой — окно не показывать. */
    const val HIDDEN = "--autostart"

    private const val RUN = """Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val APPROVED = """Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run"""
    private const val NAME = "TIMA"

    override val available: Boolean get() = executable() != null

    override fun enabled(): Boolean = runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, NAME) && !disabledByWindows()
    }.getOrDefault(false)

    override fun set(on: Boolean) {
        runCatching {
            if (on) {
                val command = command() ?: run {
                    Journal.trouble(LogCode.APP_START, "автозапуск: не нашли, что запускать")
                    return
                }
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, NAME, command)
            } else {
                deleteValue(RUN)
            }
            // Отметка «Диспетчера задач» снимается в обе стороны: при включении она бы нас
            // глушила, при выключении осталась бы висеть без значения.
            deleteValue(APPROVED)
            Journal.note(LogCode.APP_START, "автозапуск", "включён" to on)
        }.onFailure {
            Journal.trouble(LogCode.APP_START, "автозапуск не настроен", "почему" to it.message.orEmpty())
        }
    }

    /**
     * Поправить уже включённый автозапуск под эту установку: программа могла переехать, а
     * у включённого до 2026-09-27 нет ключа [HIDDEN] — такой запуск открывал окно.
     * Выключенный не трогается: решение человека.
     */
    fun refresh() {
        runCatching {
            val command = command() ?: return
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, NAME)) return
            if (Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN, NAME) == command) return
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, NAME, command)
            Journal.note(LogCode.APP_START, "автозапуск: строка запуска обновлена")
        }
    }

    /**
     * Первый байт отметки: чётный — включено, нечётный — выключено человеком. Так пишет
     * «Диспетчер задач» (02 и 03), а отметки нет вовсе у того, что он не трогал.
     */
    private fun disabledByWindows(): Boolean {
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, APPROVED, NAME)) return false
        val mark = Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, APPROVED, NAME)
        return mark.isNotEmpty() && mark[0].toInt() and 1 == 1
    }

    private fun deleteValue(key: String) {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, NAME)
        }
    }

    /** Путь в кавычках: установленная программа лежит в `Program Files`, с пробелом. */
    private fun command(): String? = executable()?.let { "\"$it\" $HIDDEN" }

    /**
     * Чем себя запускать.
     *
     * У упакованного приложения это `TIMA.exe` рядом с `runtime`; запущенное из Gradle его
     * не имеет, и тогда автозапуск не ставится — предлагать запускать Gradle при входе в
     * систему незачем.
     */
    private fun executable(): String? {
        val path = System.getProperty("jpackage.app-path")
        if (!path.isNullOrBlank() && File(path).exists()) return path
        return null
    }
}
