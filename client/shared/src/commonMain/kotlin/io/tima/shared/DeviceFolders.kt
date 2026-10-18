package io.tima.shared

import io.tima.core.media.MediaFolder

/**
 * Папка медиа аккаунта (Решения §6) — по имени его базы: у аккаунтов разные ключи покоя, и файлы
 * одного не должны лежать среди файлов другого. Каталог — платформенный, поэтому задаёт его
 * платформа, один раз на процесс, до первой сборки: так же, как сборка живёт в [ChannelHost], а не
 * в окне, — службе после перезагрузки телефона папка нужна без окна.
 */
object DeviceFolders {
    /** Имя базы аккаунта → его папка медиа. `null` — платформа папки не дала. */
    var provider: ((String) -> MediaFolder)? = null

    fun of(databaseName: String): MediaFolder = provider?.invoke(databaseName) ?: MediaFolder.NONE
}
