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
import io.tima.core.ui.MarkKind
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonCircle
import io.tima.core.ui.Mark
import io.tima.core.ui.Field
import io.tima.core.ui.Caption
import io.tima.core.ui.Bubble
import io.tima.core.ui.Side
import io.tima.core.ui.Arrow
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.Tima
import io.tima.core.ui.Tertiary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Chip
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
fun ChatScreen(
    state: ChatState,
    /** Имя собеседника в шапке. Над сообщениями его нет: в личной переписке подписей нет. */
    peer: String,
    onSet: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись под именем: «в сети», «был вчера». */
    caption: String? = null,
    onCloseMessage: () -> Unit = {},
    /**
     * Человек просит недостающий ключ группы. Кнопка появляется только у групповой
     * переписки с нечитаемыми сообщениями: у личной просить нечего и не у кого.
     */
    onRequestKey: () -> Unit = {},
    /** Человек набирает секретную фразу для подписи запроса. */
    onPhrase: (String) -> Unit = {},
    /**
     * Открыть состав группы. `null` — переписка личная: состава у неё нет, и кнопки быть
     * не должно.
     */
    onMembers: (() -> Unit)? = null,
    /**
     * Круг следующего сообщения (ADR-0019) и его смена. `null` — переписка личная: круга
     * у неё нет, всё зашифровано и адресовано одному человеку.
     *
     * Слова «уровень» на экране нет: человек видит круг — «Всем», «Своим», «Зашифровано».
     */
    circle: MessageCircle? = null,
    onCircle: ((MessageCircle) -> Unit)? = null,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(
            title = peer,
            onBack = onBack,
            caption = caption,
            right = onMembers?.let { open ->
                { Chip("Участники", kind = ChipKind.Selected, onClick = open) }
            },
        )

        Feed(
            lines = state.lines,
            // **В личной переписке подписи нет вовсе.** Так в макете: у чужой реплики
            // остаётся полоса автора, но ни аватара, ни имени внутри пузыря нет
            // (`Layout-UI-light/телефон/подокна/чат.html`, первый кадр). Собеседник один
            // и назван в шапке — подпись у каждого пузыря повторяла бы её.
            // В группе имя берётся по отправителю; неизвестное не выдумываем:
            // «Участник» честнее чужого имени.
            authorName = { line ->
                if (!state.group) null
                else state.names[line.senderId] ?: "Участник"
            },
            modifier = Modifier.weight(1f),
        )

        // Полоса недоступной истории — над вводом и ОДНА на экран, а не у каждой строки.
        // Запрос уходит сразу за все недостающие версии; кнопка у каждого сообщения
        // обещала бы точность, которой в механизме нет.
        if (state.keyAskMay &&
            state.lines.any { it.display == MessageDisplay.UNREADABLE }
        ) {
            StoryUnavailable(
                expect = state.expectKey,
                // Поле фразы появляется только после отказа по подписи: спрашивать её
                // заранее значило бы требовать секрет там, где он может не понадобиться.
                phraseInputNeeded = state.notice is ChatNotice.KeysNeedPhrase,
                phrase = state.phrase,
                onPhrase = onPhrase,
                onRequest = onRequestKey,
            )
        }

        state.notice?.let { Trouble(it, onCloseMessage) }

        InputZone(
            typed = state.draft,
            onSet = onSet,
            onSend = onSend,
            circle = circle,
            onCircle = onCircle,
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
private fun Feed(
    lines: List<ChatLine>,
    authorName: (ChatLine) -> String?,
    modifier: Modifier = Modifier,
) =
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        contentPadding = PaddingValues(
            horizontal = TimaSpacing.about3,
            vertical = TimaSpacing.about4,
        ),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3, Alignment.Bottom),
    ) {
        items(lines, key = { it.dedupKey }) { line ->
            val index = lines.indexOf(line)
            // Предыдущее по времени лежит НИЖЕ в списке: список идёт новым сверху.
            val previous = lines.getOrNull(index + 1)
            Reply(
                line = line,
                author = authorName(line),
                // Смена автора разрывает цепочку, даже когда обе реплики чужие: иначе в
                // группе два человека подряд слились бы в одного, и имя второго не
                // показалось бы вовсе.
                continuation = previous?.outgoing == line.outgoing &&
                    previous?.senderId == line.senderId,
            )
        }
    }

/**
 * Одна реплика: пузырь, время и отметка; в группе — ещё имя и аватар автора.
 *
 * `автор == null` означает «подписывать нечем и незачем» — личная переписка. Аватар без
 * имени не бывает: обе метки отвечают на один вопрос «кто это», и в личной переписке он
 * не задаётся.
 */
@Composable
private fun Reply(line: ChatLine, author: String?, continuation: Boolean) = Bubble(
    my = line.outgoing,
    author = author,
    avatar = author?.take(1)?.uppercase(),
    continuation = continuation,
    bottom = {
        Tertiary(time(line.atMs), lineOne = true)
        mark(line.display)?.let { Mark(it) }
    },
) {
    val text = line.text
    when {
        text != null -> Caption(text, fontSize = TimaType.sz4)

        // **«Не читается» и «ещё не разобрано» — разные вещи, и путать их нельзя.**
        // Первое окончательно: ключа нет, подпись не сошлась. Второе — секунда между
        // записью конверта и его расшифровкой, и человеку в этот миг говорить «не
        // читается» значит соврать: сообщение сейчас появится.
        //
        // Нашлось на живом прогоне: экран показывал «не читается» на сообщении, которое в
        // базе лежало разобранным. Список переписок при этом различал их правильно — то
        // есть два места в одном приложении говорили человеку разное.
        line.display == MessageDisplay.UNREADABLE ->
            Chip("сообщение недоступно", kind = ChipKind.Quiet)

        else -> Chip("расшифровывается…", kind = ChipKind.Quiet)
    }
}

/**
 * Отметка о судьбе сообщения — только у своих.
 *
 * У входящего отметки нет: «получено» для чужого сообщения означает, что оно на экране,
 * а это и так видно. Нечитаемое говорит о себе самим пузырём.
 */
private fun mark(kind: MessageDisplay): MarkKind? = when (kind) {
    MessageDisplay.PENDING -> MarkKind.Waits
    MessageDisplay.SENT -> MarkKind.Left
    MessageDisplay.FAILED -> MarkKind.NotLeft
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
private fun InputZone(
    typed: String,
    onSet: (String) -> Unit,
    onSend: () -> Unit,
    circle: MessageCircle? = null,
    onCircle: ((MessageCircle) -> Unit)? = null,
) {
    val colors = Tima.colors
    // Круг стоит рядом с «Отправить», а не в настройках: он выбирается для каждого
    // сообщения, и последствие у него необратимое — сузить можно, расширить нельзя.
    if (circle != null && onCircle != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.functional)
                .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about1),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (option in MessageCircle.entries) {
                Chip(
                    label = option.title,
                    kind = if (option == circle) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { onCircle(option) },
                )
            }
            Caption(circle.about, fontSize = TimaType.sz6, color = colors.text3)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .heightIn(min = TimaZones.zone4)
            .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(colors.surface, CircleShape)
                .padding(horizontal = TimaSpacing.about4, vertical = 10.dp),
        ) {
            if (typed.isEmpty()) {
                Caption("Сообщение", fontSize = TimaType.sz4, color = colors.text3)
            }
            BasicTextField(
                value = typed,
                onValueChange = onSet,
                textStyle = TextStyle(fontSize = TimaType.sz4, color = colors.text),
                cursorBrush = SolidColor(colors.navigation),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ButtonCircle(onClick = onSend, live = true) {
            Arrow(Side.Up, color = colors.onAccent)
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
private fun Trouble(trouble: ChatNotice, onClose: () -> Unit) {
    val colors = Tima.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.softAccent)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = when (trouble) {
            // Числа обязательны: «слишком большое» без размера человеку бесполезно —
            // он не знает, насколько сокращать.
            is ChatNotice.TooLarge ->
                "Слишком большое: ${trouble.bytes} байт при пределе ${trouble.limit}"

            // Просьба ушла живым устройствам, а не «серверу»: человеку важно понимать,
            // что ответ зависит от того, откроет ли кто-то из участников приложение.
            is ChatNotice.KeysAsked ->
                "Ключ запрошен у ${trouble.devices} устройств — история появится, когда кто-то ответит"

            // Не «попробуйте позже»: ждать здесь бесполезно, и сказать надо именно это.
            ChatNotice.KeysNoHelpers ->
                "Этих ключей нет ни у кого из участников — история до вашего прихода утрачена"

            ChatNotice.KeysNothingMissing ->
                "Все ключи уже у вас: сообщение не читается по другой причине"

            // Не ошибка, а действие. И названы ОБА выхода: устройство, подключённое по
            // QR-коду, фразы не знает, и сообщение «нужна фраза» для него — тупик.
            // Смену ключа при этом может запустить любой участник, то есть сам человек
            // с другого своего устройства, — и после неё группа начнёт читаться вперёд.
            ChatNotice.KeysNeedPhrase ->
                "Нужна секретная фраза: ею аккаунт защищён от угона номера. " +
                    "Не знаете её здесь — напишите в группу с другого своего устройства: " +
                    "ключ сменится, и новые сообщения откроются. Прежние — только по фразе"

            is ChatNotice.KeysRefused -> trouble.text
        }
        Caption(
            text,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        ButtonCircle(onClick = onClose) { Arrow(Side.Right, color = colors.text2) }
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
private fun StoryUnavailable(
    expect: Boolean,
    phraseInputNeeded: Boolean,
    phrase: String,
    onPhrase: (String) -> Unit,
    onRequest: () -> Unit,
) {
    val colors = Tima.colors
    Column(modifier = Modifier.fillMaxWidth().background(colors.functional)) {
        if (phraseInputNeeded) {
            Field(
                value = phrase,
                onChange = onPhrase,
                hint = "Двенадцать слов через пробел",
                modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(
            "Часть истории недоступна: она была до вашего прихода",
            fontSize = TimaType.sz5,
            modifier = Modifier.weight(1f),
        )
        Button(label = if (expect) "Просим…" else "Запросить ключ", onClick = onRequest)
    }
    }
}
