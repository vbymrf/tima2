package io.tima.core.notify

/**
 * Какой звук играть — ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md §2 и §4 (решения заказчика 2026-09-26).
 *
 * Выбор **не синхронизируется**: каждое устройство хранит свой. Системной мелодии на
 * другом телефоне может не быть, а свой файл пришлось бы переносить.
 */
sealed interface SoundChoice {
    /** Как в системе: мелодия звонка или звук уведомления телефона по умолчанию. */
    data object Default : SoundChoice

    /** Без звука — только вибрация (если телефон не в беззвучном) и показ. */
    data object Silent : SoundChoice

    /** Системная мелодия телефона: её адрес и имя, каким его показал выбор. */
    data class System(val uri: String, val title: String) : SoundChoice

    /** Свой файл, скопированный в папку приложения: путь и имя для показа. */
    data class File(val path: String, val title: String) : SoundChoice
}

/**
 * Строка настройки. Разделитель `|` не встречается ни в адресе `content://`, ни в пути
 * папки приложения; имя идёт последним и может содержать что угодно.
 */
fun SoundChoice.wire(): String = when (this) {
    SoundChoice.Default -> "default"
    SoundChoice.Silent -> "silent"
    is SoundChoice.System -> "system|$uri|$title"
    is SoundChoice.File -> "file|$path|$title"
}

/** Разобрать строку настройки; пусто или непонятно — «как в системе». */
fun soundChoiceOf(wire: String?): SoundChoice {
    if (wire.isNullOrBlank()) return SoundChoice.Default
    if (wire == "silent") return SoundChoice.Silent
    val parts = wire.split("|", limit = 3)
    if (parts.size == 3 && parts[1].isNotEmpty()) {
        return when (parts[0]) {
            "system" -> SoundChoice.System(parts[1], parts[2])
            "file" -> SoundChoice.File(parts[1], parts[2])
            else -> SoundChoice.Default
        }
    }
    return SoundChoice.Default
}

/**
 * Входящий звонок в строке уведомления: чем он отличается от сообщения.
 *
 * @param ring мелодия: своя у контакта, иначе общая из настроек (§4). Своя важнее общей.
 */
data class CallAlert(
    val callId: String,
    val video: Boolean,
    val ring: SoundChoice = SoundChoice.Default,
)

/** Где лежит выбор в настройках устройства. */
object SoundKeys {
    /** Общая мелодия звонка. */
    const val RING = "sound.ring"

    /** Общий звук уведомления о сообщении. */
    const val MESSAGE = "sound.message"

    /** Своя мелодия звонка у контакта — по `user_id` (журнал контактов, ВЗ8). */
    fun ringOf(userId: String): String = "sound.ring.$userId"
}

/** Свой файл: какие расширения принимаем и сколько весит (§4, п. 4). */
object SoundFiles {
    val EXTENSIONS = setOf("mp3", "ogg", "m4a", "wav")
    const val MAX_BYTES = 5L * 1024 * 1024
}
