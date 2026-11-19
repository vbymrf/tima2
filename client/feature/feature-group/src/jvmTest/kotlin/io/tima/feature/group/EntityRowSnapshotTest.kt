package io.tima.feature.group

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import io.tima.core.ui.TimaColors
import io.tima.domain.chat.EntityKind
import io.tima.domain.chat.EntityReach
import io.tima.domain.chat.EntityType
import io.tima.domain.chat.GroupRole
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Строка Социума (ПЛАН-(СЦ) СЦ3) — настоящий компонент, не макет: число новых оранжевое при включённых уведомлениях и
 * серое при выключенных; название своей роли — зелёным. Снимки — `build/снимки/социум-строки-*.png`.
 */
class EntityRowSnapshotTest {

    private fun rows() = @androidx.compose.runtime.Composable {
        Column {
            EntityRow("Семья", EntityType(EntityKind.Group, EntityReach.Personal, secret = true), GroupRole.Owner,
                RowLook("Мама: Ужин в семь, не опаздывайте. Купите по дороге хлеб и молоко", 1L, "14:05", fresh = 2), onClick = {})
            EntityRow("Клуб садоводов", EntityType(EntityKind.Community, EntityReach.Public), GroupRole.Member,
                RowLook("Ольга: у кого есть лишние томаты черри?", 1L, "12:40", fresh = 4, element = EntityKind.Group to "Рассада и семена"), onClick = {})
            EntityRow("Бег по утрам", EntityType(EntityKind.Group, EntityReach.Public), GroupRole.Moderator,
                RowLook("Анна: Завтра в 6:30 у фонтана", 1L, "11:02", fresh = 15, muted = true), onClick = {})
            EntityRow("Новости района", EntityType(EntityKind.Channel, EntityReach.Public), GroupRole.Member,
                RowLook("Улицу Ленина перекрывают до пятницы", 1L, "09:12", fresh = 128), onClick = {})
            EntityRow("Очень длинное название группы, которое не помещается в строку целиком", EntityType(EntityKind.Group, EntityReach.Public), null,
                RowLook("Сообщений пока нет"), onClick = {})
        }
    }

    @Test
    fun строки_в_трёх_темах() {
        for ((name, colors) in listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark, "олива" to TimaColors.olive)) {
            val shot = capture("социум-строки-$name", 380, 420, dark = false, colors = colors) { rows()() }
            // Оранжевое число (янтарь темы) есть: у включённых уведомлений.
            assertTrue(shot.has(colors.activity), "$name: нет оранжевого числа")
            // Зелёное название своей роли есть.
            assertTrue(shot.has(colors.confirmed), "$name: нет зелёного названия своей роли")
        }
    }

    private fun io.tima.testui.Snapshot.has(color: Color): Boolean {
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            val c = color(x, y)
            if (kotlin.math.abs(c.red - color.red) < 0.03f && kotlin.math.abs(c.green - color.green) < 0.03f && kotlin.math.abs(c.blue - color.blue) < 0.03f) return true
        }
        return false
    }
}
