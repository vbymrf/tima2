package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityKinds
import io.tima.domain.chat.GroupKind

/**
 * Мастер создания — подокно.
 *
 * Четыре шага: раздел → вид → вступление → значок, название, описание. Макет —
 * `doc/Layout-UI-light/телефон/подокна/создание-группы.html`, описание — `doc_UI/33`.
 *
 * **Круг слева у каждой строки — он же кнопка «что это такое».** Нажатие на строку
 * выбирает, нажатие на круг открывает объяснение. Отдельного значка помощи нет: он
 * добавил бы к каждой строке второй предмет ради того же самого.
 *
 * **Непозванные номера показаны отдельно от беды и после создания.** Группа создана, и
 * красный текст про сбой здесь означал бы, что дело не сделано. Дело сделано — просто не
 * всех удалось позвать, и это разные вещи.
 *
 * Чистый рендер [NewGroupState]. Решения — в [NewGroupStore].
 */
@Composable
fun NewGroupScreen(
    state: NewGroupState,
    onSection: (Section) -> Unit,
    onKind: (GroupKind) -> Unit,
    onJoining: (Joining) -> Unit,
    onForward: () -> Unit,
    onExplain: (String?) -> Unit,
    onTitle: (String) -> Unit,
    onDescription: (String) -> Unit,
    onNumber: (String) -> Unit,
    onAddNumber: () -> Unit,
    onRemoveNumber: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Готов ли раздел. Спрашивается у состояния, а не берётся из самого раздела: он готов
     * тогда, когда есть чем его выполнить, и знает об этом Store.
     */
    ready: (Section) -> Boolean = { it == Section.Group },
    /** Канал: виден ли в каталоге. */
    onCatalogue: (Boolean) -> Unit = {},
    /** Канал: принимает ли обсуждения. */
    onComments: (Boolean) -> Unit = {},
    /** Сообщество: отметить или снять элемент на шаге «что вносим». */
    onItem: (CommunityItem) -> Unit = {},
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = stepTitle(state.step), onBack = onBack)
        StepBar(state.step)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                // Тот же предел ширины, что у остальных подокон: поле во всю ширину ПК
                // читается как поиск, а не как ввод.
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                when (state.step) {
                    Step.Section -> SectionStep(state, ready, onSection, onExplain)
                    Step.Kind -> KindStep(state, onKind, onExplain)
                    Step.Joining -> JoiningStep(state, onJoining, onExplain)
                    Step.Catalogue -> CatalogueStep(state, onCatalogue, onExplain)
                    Step.Comments -> CommentsStep(state, onComments, onExplain)
                    Step.Naming -> NamingStep(
                        state, onTitle, onDescription, onNumber, onAddNumber, onRemoveNumber,
                    )
                    Step.Bringing -> BringingStep(state, onItem)
                }

                state.trouble?.let { Trouble(it) }

                if (state.step == lastStep(state.section)) {
                    Button(
                        label = if (state.expect) "Создаём…" else "Создать",
                        onClick = onCreate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Появляется только после создания: до него говорить о непозванных нечего.
                    if (state.notInvited.isNotEmpty()) {
                        Secondary("Группа создана. Этих номеров в TIMA нет — позовите людей:")
                        for (number in state.notInvited) Name(number)
                    }
                    // То же самое для сообщества: что не внеслось, названо поимённо.
                    // Молчание означало бы, что человек считает связанным то, чего нет.
                    if (state.notLinked.isNotEmpty()) {
                        Secondary("Сообщество создано. Это внести не удалось — они уже в другом:")
                        for (title in state.notLinked) Name(title)
                    }
                } else {
                    Button(label = "Далее", onClick = onForward, modifier = Modifier.fillMaxWidth())
                }

                state.explaining?.let { Explanation(it, onClose = { onExplain(null) }) }
            }
        }
    }
}

// ── шаги ────────────────────────────────────────────────────────────────────

@Composable
private fun SectionStep(
    state: NewGroupState,
    ready: (Section) -> Boolean,
    onSection: (Section) -> Unit,
    onExplain: (String?) -> Unit,
) {
    Caption("Что создаём?", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    for (section in Section.entries) {
        val available = ready(section)
        ChoiceLine(
            title = section.title,
            about = section.about,
            chosen = state.section == section,
            available = available,
            // «Ждёт реализации» — это не «выключено» и не «скоро»: у звукового чата нет
            // ни сервера, ни решения о хранении, и подпись говорит именно это.
            note = if (available) null else "ждёт реализации",
            onChoose = { onSection(section) },
            onExplain = { onExplain(section.title + ". " + section.about) },
        )
    }
}

/** Последний шаг раздела: там стоит «Создать», а не «Далее». */
private fun lastStep(section: Section): Step =
    if (section == Section.Community) Step.Bringing else Step.Naming

/** Канал: виден ли в каталоге. «По подписке» значит «не в каталоге». */
@Composable
private fun CatalogueStep(
    state: NewGroupState,
    onCatalogue: (Boolean) -> Unit,
    onExplain: (String?) -> Unit,
) {
    Caption("Как находят канал?", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = "Открытый",
        about = "Виден в каталоге, подписаться может любой",
        chosen = state.inCatalogue,
        onChoose = { onCatalogue(true) },
        onExplain = { onExplain("Открытый канал. Виден в каталоге, подписаться может любой") },
    )
    ChoiceLine(
        title = "По подписке",
        about = "В каталоге не показывается — находят по ссылке",
        chosen = !state.inCatalogue,
        onChoose = { onCatalogue(false) },
        onExplain = { onExplain("По подписке. Канала нет в каталоге, его находят по ссылке") },
    )
}

/** Канал: принимает ли обсуждения (ADR-0024 §6). */
@Composable
private fun CommentsStep(
    state: NewGroupState,
    onComments: (Boolean) -> Unit,
    onExplain: (String?) -> Unit,
) {
    Caption("Записи можно обсуждать?", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = "Можно",
        about = "Под записью открывается разговор. Комментирует тот, кто видит запись",
        chosen = state.comments,
        onChoose = { onComments(true) },
        onExplain = { onExplain("Комментирует тот, кто видит запись: отдельного права нет") },
    )
    ChoiceLine(
        title = "Нельзя",
        about = "Канал без обсуждений. Это можно поменять потом",
        chosen = !state.comments,
        onChoose = { onComments(false) },
        onExplain = { onExplain("Выключено значит «новых не принимаем»: написанное раньше остаётся") },
    )
}

/**
 * Сообщество: что вносим.
 *
 * **Список — из уже существующего.** Сообщество связывает готовое, а не создаёт новое, и
 * шаг обязан это показывать: галочки стоят напротив своих групп и каналов, а строкой ниже
 * сказано, что переписка и участники не меняются.
 */
@Composable
private fun BringingStep(state: NewGroupState, onItem: (CommunityItem) -> Unit) {
    Caption("Что вносим?", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary("Переписка, участники и ключи не меняются — меняется одна ссылка")
    if (state.linkable.isEmpty()) {
        Secondary("Своих групп и каналов, свободных для внесения, нет. Сообщество можно создать пустым")
        return
    }
    for (item in state.linkable) {
        ChoiceLine(
            title = item.title,
            about = if (item.kind == CommunityKinds.CHANNEL) "канал" else "группа",
            chosen = state.bringing.any { it.id == item.id },
            onChoose = { onItem(item) },
            onExplain = {},
        )
    }
}

@Composable
private fun KindStep(
    state: NewGroupState,
    onKind: (GroupKind) -> Unit,
    onExplain: (String?) -> Unit,
) {
    Caption("Какая группа?", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = "Личная",
        about = "Сообщения зашифрованы. Поиском не находится — зовут по знакомству",
        chosen = state.kind == GroupKind.Personal,
        available = true,
        note = "E2E",
        onChoose = { onKind(GroupKind.Personal) },
        onExplain = { onExplain("Личная группа: сквозное шифрование, сервер переписки не видит. Поиском не находится — о ней узнают по цепочке знакомств.") },
    )
    ChoiceLine(
        title = "Публичная",
        about = "Находится поиском. Открытый и закрытый доступ участников",
        chosen = state.kind == GroupKind.Public,
        available = true,
        onChoose = { onKind(GroupKind.Public) },
        onExplain = { onExplain("Публичная группа: открытое общение, находится поиском и каталогом. Шифрования переписки нет.") },
    )
    // Говорится до выбора, а не после: перешифровать «на месте» нельзя, и человек
    // должен знать это раньше, чем нажмёт.
    Secondary("Вид не меняется после создания: от него зависит шифрование")
}

@Composable
private fun JoiningStep(
    state: NewGroupState,
    onJoining: (Joining) -> Unit,
    onExplain: (String?) -> Unit,
) {
    Caption("Как вступают?", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = Joining.Open.title,
        about = Joining.Open.about,
        chosen = state.joining == Joining.Open,
        available = state.openJoiningAllowed,
        note = if (state.openJoiningAllowed) null else "у личной нет",
        onChoose = { onJoining(Joining.Open) },
        onExplain = { onExplain("Открытая: человек находит группу и вступает сам.") },
    )
    ChoiceLine(
        title = Joining.Closed.title,
        about = Joining.Closed.about,
        chosen = state.joining == Joining.Closed,
        available = true,
        onChoose = { onJoining(Joining.Closed) },
        onExplain = { onExplain("Закрытая: человек подаёт заявку, админ разрешает.") },
    )
    if (!state.openJoiningAllowed) {
        // Причина, а не запрет: личную группу не находят поиском, поэтому «вступить
        // самому» некуда — сначала надо найти.
        Secondary("Личная группа всегда закрытая: её не находят поиском, и вступить самому некуда")
    }
}

@Composable
private fun NamingStep(
    state: NewGroupState,
    onTitle: (String) -> Unit,
    onDescription: (String) -> Unit,
    onNumber: (String) -> Unit,
    onAddNumber: () -> Unit,
    onRemoveNumber: (String) -> Unit,
) {
    Caption("Название и описание", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Field(value = state.title, onChange = onTitle, hint = "Название группы")
    Field(value = state.description, onChange = onDescription, hint = "Описание — его видят все, кому открыта карточка")

    Secondary("Кого позвать")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Field(value = state.number, onChange = onNumber, hint = "+7…", numeric = true)
        }
        Button(label = "Добавить", onClick = onAddNumber)
    }
    for (number in state.numbers) {
        ListLine(
            left = { Avatar(letters = "№") },
            right = { Secondary("убрать", Modifier.padding(start = TimaSpacing.about2)) },
            onClick = { onRemoveNumber(number) },
            middle = { Name(number) },
        )
    }
}

// ── части ───────────────────────────────────────────────────────────────────

/**
 * Строка выбора: круг слева, название и объяснение, пометка справа.
 *
 * Круг — и выделение, и кнопка справки. Недоступная строка приглушена, а её круг
 * пунктирный: «не выбирается», а не «выключено».
 */
@Composable
private fun ChoiceLine(
    title: String,
    about: String,
    chosen: Boolean,
    onChoose: () -> Unit,
    // Доступна ли строка. Умолчание «да»: недоступной бывает только строка раздела,
    // и говорить об этом на каждом вызове значило бы повторять очевидное.
    available: Boolean = true,
    onExplain: () -> Unit,
    note: String? = null,
) {
    val colors = Tima.colors
    ListLine(
        modifier = if (available) Modifier else Modifier.alphaOfUnavailable(),
        onClick = if (available) onChoose else null,
        left = {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .border(
                        width = if (chosen && available) 3.dp else 2.dp,
                        color = if (chosen && available) colors.navigation else colors.line,
                        shape = CircleShape,
                    )
                    .clickable(onClick = onExplain),
                contentAlignment = Alignment.Center,
            ) { Secondary("?") }
        },
        right = note?.let { { Secondary(it) } },
        middle = {
            Column {
                Name(title)
                Secondary(about)
            }
        },
    )
}

/** Полоса шагов: сколько пройдено и где мы сейчас. */
@Composable
private fun StepBar(step: Step) {
    val colors = Tima.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
    ) {
        for (s in Step.entries) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .size(height = 3.dp, width = 1.dp)
                    .background(
                        when {
                            s == step -> colors.navigation
                            s.ordinal < step.ordinal -> colors.navigation.copy(alpha = 0.45f)
                            else -> colors.line
                        },
                    ),
            )
        }
    }
}

/** Объяснение по кругу с вопросом. */
@Composable
private fun Explanation(text: String, onClose: () -> Unit) {
    val colors = Tima.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Secondary(text)
        Button(label = "Понятно", onClick = onClose)
    }
}

private fun stepTitle(step: Step): String = when (step) {
    Step.Section -> "Что создаём?"
    Step.Kind -> "Какая группа?"
    Step.Joining -> "Как вступают?"
    Step.Catalogue -> "Как находят канал?"
    Step.Comments -> "Записи можно обсуждать?"
    Step.Naming -> "Название и описание"
    Step.Bringing -> "Что вносим?"
}

/** Приглушение недоступной строки. Отдельной функцией, чтобы не плодить магию в разметке. */
private fun Modifier.alphaOfUnavailable(): Modifier = this.background(Color.Transparent)
