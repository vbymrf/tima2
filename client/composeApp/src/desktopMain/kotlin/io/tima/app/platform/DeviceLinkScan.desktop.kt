package io.tima.app.platform

actual fun deviceLinkScanSupported(): Boolean = false

actual suspend fun scanDeviceLinkQr(): String? = null
