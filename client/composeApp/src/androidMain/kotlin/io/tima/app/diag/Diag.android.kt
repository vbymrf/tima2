package io.tima.app.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun diagNow(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

actual fun deviceModel(): String {
    val m = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL.orEmpty()
    // MODEL у части производителей уже содержит имя бренда — не дублируем.
    val name = if (model.startsWith(m, ignoreCase = true)) model else "$m $model"
    return "$name, Android ${android.os.Build.VERSION.RELEASE}"
}
