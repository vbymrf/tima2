package io.tima.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * QR-код текста (привязка нового устройства, key-lifecycle.md §2). Реализация
 * общая для Android и Desktop (jvmCommon/QrCodeImage.jvm.kt, ZXing) — оба JVM,
 * второй платформы-actual не нужно.
 */
@Composable
expect fun QrCodeImage(text: String, modifier: Modifier = Modifier, sizeDp: Dp = 240.dp)
