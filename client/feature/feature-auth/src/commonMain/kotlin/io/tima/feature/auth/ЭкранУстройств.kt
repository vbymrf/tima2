package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Беда
import io.tima.core.ui.ВидКнопки
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Кнопка
import io.tima.core.ui.Подпись
import io.tima.core.ui.ПустаяОбласть
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Тима
import io.tima.core.ui.Третьестепенное
import io.tima.core.ui.ШапкаПодокна
import io.tima.domain.account.AccountDevice

/**
 * Свои устройства: чем читаю и что можно отключить.
 *
 * **Своё устройство помечено.** Строки похожи — «Телефон» и «Телефон», — и без пометки
 * человек однажды отключит то, с которого смотрит. Отключение при этом спрашивает
 * подтверждение и называет цену: вернуть отозванное нельзя, на нём придётся заводиться
 * заново.
 */
@Composable
fun ЭкранУстройств(
    состояние: УстройстваState,
    onНазад: () -> Unit,
    onСпросить: (String) -> Unit,
    onПодтвердить: () -> Unit,
    onПередумал: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Номер сборки. Здесь он нужен уже после входа: экран входа человек видит один раз,
     * а «какая версия стоит» спрашивают, когда что-то пошло не так, — то есть изнутри
     * приложения. Пусто — версия не передана (проверки, снимки), строки нет.
     */
    версияСборки: String = "",
) = Column(modifier.fillMaxSize().background(Тима.цвета.поверхность)) {
    // Фон заливается явно. Экран без своего фона показывает то, что под ним, — на телефоне
    // это выглядело как тёмный экран внутри светлой темы, и найдено это было только глазами
    // на устройстве: снимки видят компонент, а не окно.
    ШапкаПодокна(название = "Устройства", onНазад = onНазад)

    // Сразу под шапкой, а не в конце списка: у экрана есть ранние выходы — вопрос об
    // отключении и пустой список, — и строка, поставленная после них, в этих состояниях
    // не показалась бы вовсе. А спрашивают версию как раз тогда, когда что-то не так.
    if (версияСборки.isNotBlank()) {
        Третьестепенное(
            "сборка $версияСборки",
            Modifier.padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
        )
    }

    состояние.беда?.let {
        Column(Modifier.padding(TimaSpacing.о4)) { Беда(it) }
    }

    val спрашиваем = состояние.спрашиваем
    if (спрашиваем != null) {
        Вопрос(
            имя = состояние.устройства.firstOrNull { it.deviceId == спрашиваем }?.name.orEmpty(),
            onПодтвердить = onПодтвердить,
            onПередумал = onПередумал,
        )
        return@Column
    }

    if (состояние.устройства.isEmpty()) {
        ПустаяОбласть(
            заголовок = if (состояние.ждём) "Смотрим…" else "Устройств нет",
            пояснение = if (состояние.ждём) null else "Список пуст — такого не бывает, если вы вошли",
        )
        return@Column
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(TimaSpacing.о4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
    ) {
        items(состояние.устройства, key = { it.deviceId }) { устройство ->
            Строка(устройство, onСпросить)
        }
    }
}

@Composable
private fun Строка(устройство: AccountDevice, onСпросить: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Подпись(
                текст = устройство.name.ifEmpty { "Без имени" },
                вес = FontWeight.Bold,
                однойСтрокой = true,
            )
            // Пометка своего устройства и дата — в одной строке: это про одно и то же,
            // «что это за железка».
            Третьестепенное(
                текст = listOfNotNull(
                    if (устройство.current) "это устройство" else null,
                    устройство.createdAt,
                ).joinToString(" · ").ifEmpty { "—" },
                однойСтрокой = true,
            )
        }
        // Своё устройство отключается не отсюда: «выйти» — это другое действие с другими
        // последствиями, и оно живёт в настройках аккаунта.
        if (!устройство.current) {
            Кнопка(
                надпись = "Отключить",
                onClick = { onСпросить(устройство.deviceId) },
                вид = ВидКнопки.Тихая,
            )
        }
    }
}

/**
 * Вопрос перед отключением.
 *
 * Занимает весь экран, а не всплывает над списком: отозванное устройство обратно не
 * вернуть, и решение должно выглядеть решением.
 */
@Composable
private fun Вопрос(имя: String, onПодтвердить: () -> Unit, onПередумал: () -> Unit) = Column(
    modifier = Modifier.fillMaxSize().padding(TimaSpacing.о4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
) {
    Подпись("Отключить устройство?", кегль = TimaType.щ3, вес = FontWeight.ExtraBold)
    Второстепенное(имя.ifEmpty { "Без имени" })
    Второстепенное(
        "Оно перестанет получать сообщения и потеряет доступ к аккаунту. Вернуть его " +
            "нельзя — на нём придётся подключаться заново.",
    )

    Кнопка(
        надпись = "Отключить",
        onClick = onПодтвердить,
        вид = ВидКнопки.Опасная,
        modifier = Modifier.fillMaxWidth(),
    )
    Кнопка(
        надпись = "Оставить",
        onClick = onПередумал,
        modifier = Modifier.fillMaxWidth(),
    )
}
