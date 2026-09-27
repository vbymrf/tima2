package io.tima.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File

/**
 * Каким окно оставили — чтобы открыть его таким же (заказчик 2026-09-27).
 *
 * Файл в одну строку рядом с базой: `ширина высота x y развёрнуто`. Не база — окно
 * поднимается раньше, чем она открыта. Сломан или от другой версии — окно по умолчанию,
 * а не отказ: помнить размер — удобство, не то, ради чего стоит не открыться.
 */
internal data class WindowMemory(val size: DpSize, val position: WindowPosition?, val maximized: Boolean) {

    companion object {

        fun load(file: File): WindowMemory? = runCatching {
            val parts = file.readText().trim().split(' ')
            val width = parts[0].toInt()
            val height = parts[1].toInt()
            val x = parts[2].toInt()
            val y = parts[3].toInt()
            WindowMemory(
                size = DpSize(width.dp, height.dp),
                // Монитор, на котором окно стояло, могли отключить — тогда окно ушло бы за
                // край, и человек его не нашёл бы. Такое место забывается, размер остаётся.
                position = WindowPosition(x.dp, y.dp).takeIf { onScreen(Rectangle(x, y, width, height)) },
                maximized = parts[4] == "1",
            )
        }.getOrNull()

        /**
         * @param fallback размер, если окно развёрнуто, а обычного размера ещё не помним: без
         *   него развёрнутое при первом запуске окно не запоминалось вовсе.
         */
        fun save(file: File, state: WindowState, fallback: DpSize) {
            // Развёрнутое окно хранит размер экрана — его запоминать незачем: иначе после
            // «свернуть в окно» оно осталось бы во весь экран. Помнится только отметка.
            val maximized = state.placement == WindowPlacement.Maximized
            val previous = load(file)
            val floating = state.placement == WindowPlacement.Floating
            val size = if (floating) state.size else previous?.size ?: fallback
            val position = (if (floating) state.position else previous?.position) as? WindowPosition.Absolute
            val line = listOf(
                size.width.value.toInt(),
                size.height.value.toInt(),
                position?.x?.value?.toInt() ?: NO_PLACE,
                position?.y?.value?.toInt() ?: NO_PLACE,
                if (maximized) 1 else 0,
            ).joinToString(" ")
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(line)
            }
        }

        /** Виден ли хотя бы угол окна с заголовком хоть на одном экране. */
        private fun onScreen(window: Rectangle): Boolean = runCatching {
            val grip = Rectangle(window.x, window.y, window.width.coerceAtMost(GRIP), GRIP)
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .any { it.defaultConfiguration.bounds.intersects(grip) }
        }.getOrDefault(false)

        /** Места нет — окно по центру. Число вне любого экрана, проверка его отбросит. */
        private const val NO_PLACE = -100000
        private const val GRIP = 80
    }
}
