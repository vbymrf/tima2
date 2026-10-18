package io.tima.core.media

import androidx.compose.runtime.Composable

/** Выбранный контакт: имя и номер (ВЫБОР-РЕАЛИЗАЦИИ §11.2а: один человек из системной книги). */
class PickedContact(val name: String, val phone: String)

/**
 * Снимок системной камерой (ВЫБОР-РЕАЛИЗАЦИИ §11.2а): на Android — `ACTION_IMAGE_CAPTURE`, снимок
 * ложится во временный файл приложения и сразу читается и стирается; разрешение на камеру
 * спрашивается перед вызовом. `null` у функции — камеры на этой платформе нет (ПК): пункта нет.
 */
@Composable
expect fun rememberCameraShot(onShot: (PickedImage?) -> Unit): (() -> Unit)?

/** Один человек из системной книги; `null` у функции — книги нет (ПК): ввод руками. */
@Composable
expect fun rememberContactPicker(onPicked: (PickedContact?) -> Unit): (() -> Unit)?

/**
 * Где я (ВЫБОР-РЕАЛИЗАЦИИ §11.2а): координаты от системы, без служб Google — на Honor их нет;
 * разрешение — при нажатии. `null` у функции — места на платформе нет (ПК): ввод руками.
 * В [onLocated] — широта и долгота; `null` — не дали доступа или система не знает.
 */
@Composable
expect fun rememberMyPlace(onLocated: (Pair<Double, Double>?) -> Unit): (() -> Unit)?

/**
 * Координаты из того, что вставил человек: «55.75, 37.61» или ссылка Яндекс- и Google-карт.
 * Не нашлось — `null`.
 */
object PlaceText {
    private val PAIR = Regex("""(-?\d{1,3}\.\d+)\s*[,; ]\s*(-?\d{1,3}\.\d+)""")
    private val YANDEX_PT = Regex("""[?&](?:pt|ll)=(-?\d{1,3}\.\d+)(?:%2C|,)(-?\d{1,3}\.\d+)""")

    fun parse(text: String): Pair<Double, Double>? {
        // Яндекс пишет долготу первой: pt=lon,lat.
        YANDEX_PT.find(text)?.let { m ->
            val lon = m.groupValues[1].toDouble()
            val lat = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return lat to lon
        }
        PAIR.find(text)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return lat to lon
        }
        return null
    }

    private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
}
