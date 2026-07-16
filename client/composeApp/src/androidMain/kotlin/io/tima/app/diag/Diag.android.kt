package io.tima.app.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun diagNow(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
