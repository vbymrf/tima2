package io.tima.core.media

import androidx.compose.runtime.Composable

/**
 * Выбор звука — мелодии звонка или звука уведомления (ПЛАН-ВХОДЯЩЕГО-ЗВОНКА ВЗ4).
 *
 * Два входа, как решил заказчик: **из стандартных** (системные мелодии телефона) и
 * **загрузить файл**. Свой файл копируется в папку приложения: исходник могут удалить, а
 * звонок звучать обязан.
 */
sealed interface SoundPick {
    /** Системная мелодия: адрес и имя, каким его показал выбор. */
    data class System(val uri: String, val title: String) : SoundPick

    /** Свой файл, уже скопированный к себе: путь и имя для показа. */
    data class File(val path: String, val title: String) : SoundPick

    /** «Как в системе» — выбор системного «по умолчанию». */
    data object Default : SoundPick

    /** Файл больше предела — не взят (§4: до 5 МБ). */
    data object TooBig : SoundPick

    /** Не звук или не тот формат — не взят (§4: mp3, ogg, m4a, wav). */
    data object BadType : SoundPick
}

/** Для чего звук: от этого зависит список стандартных и имя файла у себя. */
enum class SoundUse { Ring, Message }

/** Есть ли у платформы свой список стандартных мелодий. На ПК его нет. */
expect val systemSoundsAvailable: Boolean

/**
 * Выбор из стандартных. Ничего не выбрали — `null`.
 */
@Composable
expect fun rememberSystemSoundPicker(use: SoundUse, onPicked: (SoundPick?) -> Unit): () -> Unit

/**
 * Загрузить свой файл. [name] — имя файла у себя без расширения: у общей мелодии
 * «ring», у мелодии контакта — своё, чтобы выбор одного не затёр другой.
 */
@Composable
expect fun rememberSoundFilePicker(name: String, onPicked: (SoundPick?) -> Unit): () -> Unit

/** Какие файлы принимаем и сколько весит (§4, п. 4). */
internal object SoundLimits {
    val EXTENSIONS = setOf("mp3", "ogg", "m4a", "wav")
    const val MAX_BYTES = 5L * 1024 * 1024
}
