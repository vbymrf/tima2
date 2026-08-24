package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.ВидОтметки
import io.tima.core.ui.ВидЧипа
import io.tima.core.ui.Кнопка
import io.tima.core.ui.КругКнопка
import io.tima.core.ui.Отметка
import io.tima.core.ui.Поле
import io.tima.core.ui.Подпись
import io.tima.core.ui.Пузырь
import io.tima.core.ui.Сторона
import io.tima.core.ui.Стрелка
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.Тима
import io.tima.core.ui.Третьестепенное
import io.tima.core.ui.ШапкаПодокна
import io.tima.core.ui.Чип
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay

/**
 * Окно переписки — К5.2, первый экран.
 *
 * **Экран — чистый рендер состояния.** Он ничего не решает: что делать с набранным
 * текстом при отказе, когда чистить поле, что показать человеку — всё уже решено в
 * [ChatStore]. Здесь только то, как это выглядит. Поэтому экран проверяется снимком: ему
 * дают состояние и смотрят, что нарисовалось.
 *
 * **Первым сделан чат, а не список чатов** — порядок из дорожной карты: экраны идут по
 * частоте использования. Чат открывают чаще всего, и он же тяжелее всех: пузыри, полосы
 * автора, состояния отправки, ввод.
 *
 * Личная переписка. Продолжение серии определяется **направлением**, потому что в личной
 * переписке участников двое; в группе для этого понадобится отправитель, а его в
 * [ChatLine] нет — это придёт с группами (К6), и тогда правило станет другим.
 */
@Composable
fun ЭкранЧата(
    состояние: ChatState,
    /** Имя собеседника в шапке и над его сообщениями. */
    собеседник: String,
    onНабор: (String) -> Unit,
    onОтправить: () -> Unit,
    onНазад: () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись под именем: «в сети», «был вчера». */
    подпись: String? = null,
    onЗакрытьСообщение: () -> Unit = {},
    /**
     * Человек просит недостающий ключ группы. Кнопка появляется только у групповой
     * переписки с нечитаемыми сообщениями: у личной просить нечего и не у кого.
     */
    onЗапроситьКлюч: () -> Unit = {},
    /** Человек набирает секретную фразу для подписи запроса. */
    onФраза: (String) -> Unit = {},
) {
    val цвета = Тима.цвета
    Column(modifier.fillMaxSize().background(цвета.поверхность)) {
        ШапкаПодокна(
            название = собеседник,
            onНазад = onНазад,
            подпись = подпись,
        )

        Лента(
            строки = состояние.lines,
            // В личной переписке автор один и назван в шапке; в группе имя берётся по
            // отправителю. Неизвестное имя не выдумываем: «Участник» честнее чужого.
            имяАвтора = { строка ->
                if (!состояние.группа) собеседник
                else состояние.имена[строка.senderId] ?: "Участник"
            },
            modifier = Modifier.weight(1f),
        )

        // Полоса недоступной истории — над вводом и ОДНА на экран, а не у каждой строки.
        // Запрос уходит сразу за все недостающие версии; кнопка у каждого сообщения
        // обещала бы точность, которой в механизме нет.
        if (состояние.можноПроситьКлюч &&
            состояние.lines.any { it.display == MessageDisplay.UNREADABLE }
        ) {
            НедоступнаяИстория(
                ждём = состояние.ждёмКлюч,
                // Поле фразы появляется только после отказа по подписи: спрашивать её
                // заранее значило бы требовать секрет там, где он может не понадобиться.
                нуженВводФразы = состояние.notice is ChatNotice.KeysNeedPhrase,
                фраза = состояние.фраза,
                onФраза = onФраза,
                onЗапросить = onЗапроситьКлюч,
            )
        }

        состояние.notice?.let { Беда(it, onЗакрытьСообщение) }

        ЗонаВвода(
            набранное = состояние.draft,
            onНабор = onНабор,
            onОтправить = onОтправить,
        )
    }
}

/**
 * Лента реплик.
 *
 * `reverseLayout`: список приходит **новым сверху** — так его отдаёт запрос к базе, — а на
 * экране новое внизу, и открывается переписка на последнем сообщении. Переворачивать
 * список в коде значило бы делать в памяти то, что уже сделано в SQL, и заодно потерять
 * бесплатное «прокрутка начинается снизу».
 */
@Composable
private fun Лента(
    строки: List<ChatLine>,
    имяАвтора: (ChatLine) -> String,
    modifier: Modifier = Modifier,
) =
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        contentPadding = PaddingValues(
            horizontal = TimaSpacing.о3,
            vertical = TimaSpacing.о4,
        ),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.о3, Alignment.Bottom),
    ) {
        items(строки, key = { it.dedupKey }) { строка ->
            val индекс = строки.indexOf(строка)
            // Предыдущее по времени лежит НИЖЕ в списке: список идёт новым сверху.
            val предыдущее = строки.getOrNull(индекс + 1)
            Реплика(
                строка = строка,
                автор = имяАвтора(строка),
                // Смена автора разрывает цепочку, даже когда обе реплики чужие: иначе в
                // группе два человека подряд слились бы в одного, и имя второго не
                // показалось бы вовсе.
                продолжение = предыдущее?.outgoing == строка.outgoing &&
                    предыдущее?.senderId == строка.senderId,
            )
        }
    }

/** Одна реплика: пузырь, имя и аватар автора, время и отметка. */
@Composable
private fun Реплика(строка: ChatLine, автор: String, продолжение: Boolean) = Пузырь(
    моё = строка.outgoing,
    автор = автор,
    аватар = автор.take(1).uppercase(),
    продолжение = продолжение,
    низ = {
        Третьестепенное(время(строка.atMs), однойСтрокой = true)
        отметка(строка.display)?.let { Отметка(it) }
    },
) {
    val текст = строка.text
    when {
        текст != null -> Подпись(текст, кегль = TimaType.щ4)

        // **«Не читается» и «ещё не разобрано» — разные вещи, и путать их нельзя.**
        // Первое окончательно: ключа нет, подпись не сошлась. Второе — секунда между
        // записью конверта и его расшифровкой, и человеку в этот миг говорить «не
        // читается» значит соврать: сообщение сейчас появится.
        //
        // Нашлось на живом прогоне: экран показывал «не читается» на сообщении, которое в
        // базе лежало разобранным. Список переписок при этом различал их правильно — то
        // есть два места в одном приложении говорили человеку разное.
        строка.display == MessageDisplay.UNREADABLE ->
            Чип("сообщение недоступно", вид = ВидЧипа.Тихий)

        else -> Чип("расшифровывается…", вид = ВидЧипа.Тихий)
    }
}

/**
 * Отметка о судьбе сообщения — только у своих.
 *
 * У входящего отметки нет: «получено» для чужого сообщения означает, что оно на экране,
 * а это и так видно. Нечитаемое говорит о себе самим пузырём.
 */
private fun отметка(вид: MessageDisplay): ВидОтметки? = when (вид) {
    MessageDisplay.PENDING -> ВидОтметки.Ждёт
    MessageDisplay.SENT -> ВидОтметки.Ушло
    MessageDisplay.FAILED -> ВидОтметки.НеУшло
    MessageDisplay.RECEIVED, MessageDisplay.UNREADABLE -> null
}

/**
 * Зона 4 — строка ввода.
 *
 * Поле и кнопка на функциональной подложке, как в макете. **Кнопка отправки нарисована**,
 * а не набрана знаком: «→» есть не во всяком шрифте, а кнопка без надписи — это кнопка,
 * про которую надо догадываться.
 */
@Composable
private fun ЗонаВвода(
    набранное: String,
    onНабор: (String) -> Unit,
    onОтправить: () -> Unit,
) {
    val цвета = Тима.цвета
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(цвета.функц)
            .heightIn(min = TimaZones.зона4)
            .padding(horizontal = TimaSpacing.о3, vertical = TimaSpacing.о2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(цвета.поверхность, CircleShape)
                .padding(horizontal = TimaSpacing.о4, vertical = 10.dp),
        ) {
            if (набранное.isEmpty()) {
                Подпись("Сообщение", кегль = TimaType.щ4, цвет = цвета.текст3)
            }
            BasicTextField(
                value = набранное,
                onValueChange = onНабор,
                textStyle = TextStyle(fontSize = TimaType.щ4, color = цвета.текст),
                cursorBrush = SolidColor(цвета.навигация),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        КругКнопка(onClick = onОтправить, живая = true) {
            Стрелка(Сторона.Вверх, цвет = цвета.наАкценте)
        }
    }
}

/**
 * Сообщение о беде: единственное на весь экран, и это не случайно.
 *
 * То, что очередь решает сама — повторы, ожидание сети, — человеку сообщать нечем и
 * незачем: это видно по отметке у реплики. Остаётся то, что требует его решения.
 */
@Composable
private fun Беда(беда: ChatNotice, onЗакрыть: () -> Unit) {
    val цвета = Тима.цвета
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(цвета.акцентМягкий)
            .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val текст = when (беда) {
            // Числа обязательны: «слишком большое» без размера человеку бесполезно —
            // он не знает, насколько сокращать.
            is ChatNotice.TooLarge ->
                "Слишком большое: ${беда.bytes} байт при пределе ${беда.limit}"

            // Просьба ушла живым устройствам, а не «серверу»: человеку важно понимать,
            // что ответ зависит от того, откроет ли кто-то из участников приложение.
            is ChatNotice.KeysAsked ->
                "Ключ запрошен у ${беда.устройствам} устройств — история появится, когда кто-то ответит"

            // Не «попробуйте позже»: ждать здесь бесполезно, и сказать надо именно это.
            ChatNotice.KeysNoHelpers ->
                "Этих ключей нет ни у кого из участников — история до вашего прихода утрачена"

            ChatNotice.KeysNothingMissing ->
                "Все ключи уже у вас: сообщение не читается по другой причине"

            // Не ошибка, а единственное доступное действие: фраза защищает историю от
            // того, кто увёл номер.
            ChatNotice.KeysNeedPhrase ->
                "Нужна секретная фраза: ею аккаунт защищён от угона номера"

            is ChatNotice.KeysRefused -> беда.текст
        }
        Подпись(
            текст,
            кегль = TimaType.щ5,
            вес = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        КругКнопка(onClick = onЗакрыть) { Стрелка(Сторона.Вправо, цвет = цвета.текст2) }
    }
}

/**
 * Полоса «часть истории недоступна» с кнопкой запроса.
 *
 * **Почему это не беда.** Сообщения до входа в группу не читаются законно: обёртки
 * прошлых версий ключа выдают только тем, кто был в группе на момент ротации. Показать
 * это ошибкой значило бы сказать человеку, что что-то сломалось, тогда как всё работает
 * как задумано — и одновременно спрятать единственное доступное ему действие.
 */
@Composable
private fun НедоступнаяИстория(
    ждём: Boolean,
    нуженВводФразы: Boolean,
    фраза: String,
    onФраза: (String) -> Unit,
    onЗапросить: () -> Unit,
) {
    val цвета = Тима.цвета
    Column(modifier = Modifier.fillMaxWidth().background(цвета.функц)) {
        if (нуженВводФразы) {
            Поле(
                значение = фраза,
                onИзменение = onФраза,
                подсказка = "Двенадцать слов через пробел",
                modifier = Modifier.padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(цвета.функц)
            .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Подпись(
            "Часть истории недоступна: она была до вашего прихода",
            кегль = TimaType.щ5,
            modifier = Modifier.weight(1f),
        )
        Кнопка(надпись = if (ждём) "Просим…" else "Запросить ключ", onClick = onЗапросить)
    }
    }
}
