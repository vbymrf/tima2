package io.tima.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.tima.core.model.ChatId

/**
 * Вход для ПК. Пусто по замыслу: этап К1.9 доказывает, что тулчейн Compose
 * собирается и окно открывается. Интерфейс приезжает в К5 — по макету и из
 * дизайн-системы, а не отсюда.
 *
 * Обращение к [ChatId] здесь неслучайно: оно проверяет, что общий модуль виден
 * из приложения, то есть что граф зависимостей собран, а не только объявлен.
 */
fun main() = application {
    val chat = ChatId("каркас")
    Window(onCloseRequest = ::exitApplication, title = "TIMA v2 — каркас") {
        MaterialTheme {
            Text("Каркас собран. Чат: $chat")
        }
    }
}
