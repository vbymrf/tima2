package io.tima.app.platform

import android.view.WindowManager

actual fun keepScreenOn(on: Boolean) {
    val a = AndroidAppContext.activity ?: return
    // Флаги окна трогаем только с главного потока
    a.runOnUiThread {
        if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
