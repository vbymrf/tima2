package io.tima.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Что сервер предлагает поставить.
 *
 * @param stream какому потоку сборок принадлежит предложение. Пусто — сервер поток не
 *   называет; см. [UpdateStore], почему тогда мы ничего не предлагаем.
 */
data class UpdateOffer(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val stream: String = "",
)

/**
 * Откуда берётся предложение. Узкий порт, объявленный **потребителем**.
 *
 * Оболочка не знает ни про Ktor, ни про адрес сервера: у неё нет зависимости на
 * core-network и не будет. Реализацию подставляет приложение.
 */
fun interface AppVersionPort {
    /** `null` — обновления на сервере не настроены (204). Ошибка — исключение. */
    suspend fun latest(): UpdateOffer?
}

/** Что видно на вкладке «Обновление». */
data class UpdateState(
    /** Версия, которая сейчас работает. */
    val installed: String = "",
    val installedCode: Int = 0,
    /** Поток этой сборки: `v1`, `v2`. Сравнивать номера можно только внутри потока. */
    val stream: String = "",
    val offer: UpdateOffer? = null,
    val expect: Boolean = false,
    val trouble: String? = null,
    /** Проверка прошла и вернула 204: обновления просто не настроены. */
    val notConfigured: Boolean = false,
) {
    /**
     * Есть ли что ставить.
     *
     * **Номера версий сравнимы только внутри одного потока сборок.** У v1 сейчас
     * `version_code` 24, у v2 — 2, и наивное «24 больше 2» предложило бы человеку с v2
     * поставить поверх неё прошлогоднюю v1. Это не гипотеза: именно такие числа отдаёт
     * стенд на 2026-08-26.
     *
     * Поэтому правило обратное обычному: **молчим, пока не доказано, что поток наш**.
     * Сервер, не называющий поток, — старый сервер, и его предложение к нам не относится.
     */
    val updateAvailable: Boolean
        get() {
            val offer = offer ?: return false
            if (stream.isBlank() || offer.stream.isBlank()) return false
            if (offer.stream != stream) return false
            return offer.versionCode > installedCode
        }

    /** Сервер что-то предлагает, но не нам. Человеку это надо сказать, а не спрятать. */
    val alienStream: Boolean
        get() = offer != null && !updateAvailable &&
            (offer.stream.isBlank() || offer.stream != stream)
}

/**
 * Проверка обновлений.
 *
 * Спрашивает по требованию, а не в цикле: раздел открывают, когда о нём подумали, и
 * фоновый опрос здесь ничего не ускоряет, зато будит сеть.
 */
class UpdateStore(
    private val versions: AppVersionPort,
    private val scope: CoroutineScope,
    installed: String,
    installedCode: Int,
    stream: String,
) {
    private val _state = MutableStateFlow(
        UpdateState(installed = installed, installedCode = installedCode, stream = stream),
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    init {
        check()
    }

    fun check() {
        _state.value = _state.value.copy(expect = true, trouble = null, notConfigured = false)
        scope.launch {
            _state.value = try {
                val offer = versions.latest()
                _state.value.copy(offer = offer, expect = false, notConfigured = offer == null)
            } catch (e: Throwable) {
                // Сообщение исключения человеку не показываем: там адрес сервера и класс
                // ошибки Ktor. Ему нужно одно — что делать дальше.
                _state.value.copy(expect = false, trouble = "Не удалось спросить сервер — проверьте связь")
            }
        }
    }
}

/**
 * Вкладка «Обновление».
 *
 * Чистый рендер [UpdateState]; решения — в [UpdateStore].
 */
@Composable
fun UpdateSection(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) = Column(
    modifier.fillMaxSize().padding(TimaSpacing.about4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    ListLine(
        middle = { Name("Установлена " + state.installed.ifBlank { "—" }) },
        right = { Secondary(state.stream.ifBlank { "поток не объявлен" }) },
    )

    when {
        state.expect -> Secondary("Спрашиваем сервер…")

        state.trouble != null -> Trouble(state.trouble)

        state.notConfigured -> Secondary("Сервер обновлений не раздаёт")

        state.updateAvailable -> {
            val offer = requireNotNull(state.offer)
            Caption(
                "Доступна " + offer.versionName,
                fontSize = TimaType.sz3,
                weight = FontWeight.ExtraBold,
            )
            if (offer.notes.isNotBlank()) Secondary(offer.notes)
            Button(label = "Скачать", onClick = { onInstall(offer.url) })
        }

        state.alienStream -> {
            val offer = requireNotNull(state.offer)
            // Прямо и словами. Спрятать предложение значило бы, что человек, знающий про
            // «версию 0.6.5 на сайте», решит, будто приложение сломано.
            Secondary("Сервер предлагает " + offer.versionName + " — это другая сборка, не для этой версии")
        }

        else -> Secondary("Установлена последняя версия")
    }

    Button(label = "Проверить ещё раз", onClick = onCheck)
}
