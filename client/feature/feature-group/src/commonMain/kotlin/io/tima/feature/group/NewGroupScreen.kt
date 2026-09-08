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
import io.tima.core.ui.words
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
    val words = Tima.words.wizard
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
                        label = if (state.expect) words.creating else words.create,
                        onClick = onCreate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Появляется только после создания: до него говорить о непозванных нечего.
                    if (state.notInvited.isNotEmpty()) {
                        Secondary(words.groupCreatedNotInvited)
                        for (number in state.notInvited) Name(number)
                    }
                    // То же самое для сообщества: что не внеслось, названо поимённо.
                    // Молчание означало бы, что человек считает связанным то, чего нет.
                    if (state.notLinked.isNotEmpty()) {
                        Secondary(words.communityCreatedNotLinked)
                        for (title in state.notLinked) Name(title)
                    }
                } else {
                    Button(label = words.next, onClick = onForward, modifier = Modifier.fillMaxWidth())
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
    val words = Tima.words.wizard
    Caption(words.whatCreate, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    for (section in Section.entries) {
        val available = ready(section)
        val title = sectionTitle(section)
        val about = sectionAbout(section)
        ChoiceLine(
            title = title,
            about = about,
            chosen = state.section == section,
            available = available,
            // «Ждёт реализации» — это не «выключено» и не «скоро»: у звукового чата нет
            // ни сервера, ни решения о хранении, и подпись говорит именно это.
            note = if (available) null else words.waitsImplementation,
            onChoose = { onSection(section) },
            onExplain = { onExplain(title + ". " + about) },
        )
    }
}

@Composable
private fun sectionTitle(section: Section): String = with(Tima.words.wizard) {
    when (section) {
        Section.Group -> sectionGroup
        Section.Channel -> sectionChannel
        Section.Community -> sectionCommunity
        Section.VoiceRoom -> sectionVoice
    }
}

@Composable
private fun sectionAbout(section: Section): String = with(Tima.words.wizard) {
    when (section) {
        Section.Group -> sectionGroupAbout
        Section.Channel -> sectionChannelAbout
        Section.Community -> sectionCommunityAbout
        Section.VoiceRoom -> sectionVoiceAbout
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
    val words = Tima.words.wizard
    Caption(words.howFound, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = words.inCatalogue,
        about = words.inCatalogueAbout,
        chosen = state.inCatalogue,
        onChoose = { onCatalogue(true) },
        onExplain = { onExplain(words.inCatalogueExplain) },
    )
    ChoiceLine(
        title = words.byLink,
        about = words.byLinkAbout,
        chosen = !state.inCatalogue,
        onChoose = { onCatalogue(false) },
        onExplain = { onExplain(words.byLinkExplain) },
    )
}

/** Канал: принимает ли обсуждения (ADR-0024 §6). */
@Composable
private fun CommentsStep(
    state: NewGroupState,
    onComments: (Boolean) -> Unit,
    onExplain: (String?) -> Unit,
) {
    val words = Tima.words.wizard
    Caption(words.discussable, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = words.commentsAllowed,
        about = words.commentsAllowedAbout,
        chosen = state.comments,
        onChoose = { onComments(true) },
        onExplain = { onExplain(words.commentsAllowedExplain) },
    )
    ChoiceLine(
        title = words.commentsForbidden,
        about = words.commentsForbiddenAbout,
        chosen = !state.comments,
        onChoose = { onComments(false) },
        onExplain = { onExplain(words.commentsForbiddenExplain) },
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
    val words = Tima.words
    Caption(words.wizard.bringing, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(words.communities.bringingKeepsEverything)
    if (state.linkable.isEmpty()) {
        Secondary(words.wizard.nothingFreeToBring)
        return
    }
    for (item in state.linkable) {
        ChoiceLine(
            title = item.title,
            about = if (item.kind == CommunityKinds.CHANNEL) {
                words.communities.channel
            } else {
                words.communities.group
            },
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
    val words = Tima.words.wizard
    Caption(words.whichGroup, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = words.personal,
        about = words.personalAbout,
        chosen = state.kind == GroupKind.Personal,
        available = true,
        note = "E2E",
        onChoose = { onKind(GroupKind.Personal) },
        onExplain = { onExplain(words.personalExplain) },
    )
    ChoiceLine(
        title = words.public,
        about = words.publicAbout,
        chosen = state.kind == GroupKind.Public,
        available = true,
        onChoose = { onKind(GroupKind.Public) },
        onExplain = { onExplain(words.publicExplain) },
    )
    // Говорится до выбора, а не после: перешифровать «на месте» нельзя, и человек
    // должен знать это раньше, чем нажмёт.
    Secondary(words.kindIsFinal)
}

@Composable
private fun JoiningStep(
    state: NewGroupState,
    onJoining: (Joining) -> Unit,
    onExplain: (String?) -> Unit,
) {
    val words = Tima.words.wizard
    Caption(words.howJoin, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    ChoiceLine(
        title = words.openJoining,
        about = words.openJoiningAbout,
        chosen = state.joining == Joining.Open,
        available = state.openJoiningAllowed,
        note = if (state.openJoiningAllowed) null else words.noneForPersonal,
        onChoose = { onJoining(Joining.Open) },
        onExplain = { onExplain(words.openExplain) },
    )
    ChoiceLine(
        title = words.closedJoining,
        about = words.closedJoiningAbout,
        chosen = state.joining == Joining.Closed,
        available = true,
        onChoose = { onJoining(Joining.Closed) },
        onExplain = { onExplain(words.closedExplain) },
    )
    if (!state.openJoiningAllowed) {
        // Причина, а не запрет: личную группу не находят поиском, поэтому «вступить
        // самому» некуда — сначала надо найти.
        Secondary(words.personalAlwaysClosed)
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
    val words = Tima.words.wizard
    Caption(words.naming, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Field(value = state.title, onChange = onTitle, hint = words.groupName)
    Field(value = state.description, onChange = onDescription, hint = words.descriptionAbout)

    Secondary(words.whomInvite)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Field(value = state.number, onChange = onNumber, hint = "+7…", numeric = true)
        }
        Button(label = words.add, onClick = onAddNumber)
    }
    for (number in state.numbers) {
        ListLine(
            left = { Avatar(letters = "№") },
            right = { Secondary(words.remove, Modifier.padding(start = TimaSpacing.about2)) },
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
        Button(label = Tima.words.wizard.gotIt, onClick = onClose)
    }
}

@Composable
private fun stepTitle(step: Step): String = with(Tima.words.wizard) {
    when (step) {
        Step.Section -> whatCreate
        Step.Kind -> whichGroup
        Step.Joining -> howJoin
        Step.Catalogue -> howFound
        Step.Comments -> discussable
        Step.Naming -> naming
        Step.Bringing -> bringing
    }
}

/** Приглушение недоступной строки. Отдельной функцией, чтобы не плодить магию в разметке. */
private fun Modifier.alphaOfUnavailable(): Modifier = this.background(Color.Transparent)
