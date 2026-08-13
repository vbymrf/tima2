package io.tima.app.platform

/** Сканирование QR камерой (привязка нового устройства, key-lifecycle.md §2) — только там, где есть камера. */
expect fun deviceLinkScanSupported(): Boolean

/** Открывает камеру и возвращает содержимое QR; null — отмена, нет разрешения или платформа без камеры. */
expect suspend fun scanDeviceLinkQr(): String?
