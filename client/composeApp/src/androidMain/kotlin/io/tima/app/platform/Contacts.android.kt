package io.tima.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun contactsSupported(): Boolean = true

actual suspend fun readDeviceContacts(): List<DeviceContact> {
    val ctx = AndroidAppContext.app
    // Разрешение READ_CONTACTS — через тот же мост, что микрофон/камера
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        val granted = AndroidPermissions.request?.invoke(listOf(Manifest.permission.READ_CONTACTS)) ?: false
        if (!granted) return emptyList()
    }
    return withContext(Dispatchers.IO) {
        val out = ArrayList<DeviceContact>()
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )
        cursor?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val num = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                if (num.isNotBlank()) out.add(DeviceContact(name, num))
            }
        }
        out
    }
}
