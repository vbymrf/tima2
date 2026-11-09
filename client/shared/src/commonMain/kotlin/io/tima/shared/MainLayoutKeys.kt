package io.tima.shared

/**
 * Ключи настроек вида главного окна и переходов (ПЛАН-(ВО), ПЛАН-(МП) М5) — в таблице `settings` базы устройства.
 * Пишет их пункт настроек «Главное окно» (ВО5); до него действуют значения по умолчанию.
 */
internal object MainLayoutKeys {
    /** Панель перехода: «0» — скрыта, иначе показана (по умолчанию). */
    const val NAV = "shell.layout.nav"

    /** Порядок областей окна: коды через запятую, `content` — список (ВО4). */
    const val ORDER = "shell.layout.order"

    /** Куда ведёт вход в окно способом: `shell.arrive.<код способа>` = `point` или `start` (МП М5). */
    const val ARRIVE = "shell.arrive."

    /** Цепочки переходов (МП4). */
    const val CHAINS = "nav.chains"

    /** Подсказка к панели перехода показана (ВО8). */
    const val NAV_TIP = "shell.tip.nav"
}
