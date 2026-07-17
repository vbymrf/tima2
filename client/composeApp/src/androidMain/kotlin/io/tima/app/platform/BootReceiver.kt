package io.tima.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.tima.app.session.SessionCodec
import io.tima.app.session.initSessionDir

/** После перезагрузки телефона поднимаем соединение, не дожидаясь открытия приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AndroidAppContext.app = context.applicationContext
        initSessionDir(context.applicationContext.filesDir)
        // Не вошёл — будить нечего
        if (SessionCodec.load() == null) return
        TimaService.start(context.applicationContext)
    }
}
