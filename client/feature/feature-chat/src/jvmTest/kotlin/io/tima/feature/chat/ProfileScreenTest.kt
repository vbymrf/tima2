package io.tima.feature.chat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.tima.core.media.decodeImage
import io.tima.core.media.encodeJpeg
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Экран профиля с аватаром — ПЛАН-КОНТАКТОВ.md, Д8 (2026-09-15).
 *
 * Проверяется весь путь картинки на экран: байты JPEG → раскодировать → нарисовать в
 * квадрате аватара. Путь платформенный (Skia под JVM, Bitmap на Android), и сломаться
 * он может молча — буквы вместо картинки никакой сборкой не ловятся.
 */
class ProfileScreenTest {

    @Test
    fun аватар_из_байтов_рисуется_картинкой_а_не_буквами() {
        // Ярко-пурпурный квадрат: такого цвета в палитре нет, спутать с подложкой нельзя.
        //
        // Берём его из `testui`, а не числом в коде: зашитый цвет ловится правилом
        // архитектуры, и оно право даже здесь — цвет, которого «нет в палитре», обязан
        // быть назван в одном месте, иначе однажды он в палитре появится.
        val purple = FOREIGN_BACKGROUND
        val square = ImageBitmap(64, 64)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(square), Size(64f, 64f)) {
            drawRect(purple)
        }
        val jpeg = encodeJpeg(square, quality = 95)
        assertTrue(decodeImage(jpeg) != null, "JPEG, который мы же и закодировали, обязан раскодироваться")

        val shot = capture("профиль-с-аватаром", 360, 640, dark = false) {
            ProfileScreen(
                state = ProfileState(phone = "+7 916 000-11-22", name = "Пётр", nickname = "petr_smirnov", avatarBytes = jpeg),
                onName = {},
                onNickname = {},
                onSave = {},
                onBack = {},
                onAvatar = {},
                onAvatarRemove = {},
            )
        }
        // JPEG чуть размывает цвет, поэтому допуск шире обычного.
        assertTrue(shot.has(purple, tolerance = 0.12), "на экране нет пурпурного — аватар нарисован буквами, а не картинкой")
    }

    @Test
    fun без_картинки_буквы_как_и_были() {
        val shot = capture("профиль-без-аватара", 360, 640, dark = false) {
            ProfileScreen(
                state = ProfileState(phone = "+7", name = "Пётр"),
                onName = {}, onNickname = {}, onSave = {}, onBack = {},
            )
        }
        assertTrue(!shot.has(FOREIGN_BACKGROUND, tolerance = 0.12), "пурпурного быть не должно: картинки нет")
    }
}
