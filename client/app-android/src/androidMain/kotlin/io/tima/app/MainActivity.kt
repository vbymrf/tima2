package io.tima.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.tima.core.model.ChatId

/**
 * Вход для Android. Пусто по замыслу: этап К1.9 доказывает, что тулчейн AGP +
 * Compose собирается. Интерфейс приезжает в К5 — по макету и из дизайн-системы.
 *
 * Обращение к [ChatId] неслучайно: оно проверяет, что общий модуль виден из
 * приложения, то есть что граф зависимостей собран, а не только объявлен.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chat = ChatId("каркас")
        setContent {
            MaterialTheme {
                Text("Каркас собран. Чат: $chat")
            }
        }
    }
}
