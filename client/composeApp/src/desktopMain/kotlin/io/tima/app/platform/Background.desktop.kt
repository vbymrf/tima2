package io.tima.app.platform

// Desktop: окно живёт, пока запущено приложение — фоновый сервис не нужен,
// как и борьба с оптимизацией батареи.
actual fun startBackgroundService() = Unit

actual fun stopBackgroundService() = Unit

actual fun batteryOptimizationIgnored(): Boolean = true

actual fun requestIgnoreBatteryOptimization() = Unit
