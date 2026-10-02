package io.tima.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.tima.core.call.CallPeer
import io.tima.core.call.CallStage
import io.tima.core.call.Calls
import io.tima.core.call.GroupCallInfo
import io.tima.core.call.GroupControl
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.ui.IconButton
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.CreateGroupStep
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.MembersStep
import io.tima.domain.chat.Section
import io.tima.feature.chat.BookView
import io.tima.feature.chat.CallCandidate
import io.tima.feature.chat.CallInvite
import io.tima.feature.chat.CallInviteLink
import io.tima.feature.chat.CallLedgerHelpPage
import io.tima.feature.chat.CallLedgerPage
import io.tima.feature.chat.CallLedgerSetup
import io.tima.feature.chat.CallMember
import io.tima.feature.chat.CallMemberState
import io.tima.feature.chat.GroupCallSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Групповой звонок вне окна 0 (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ5–ГЗ7): настройка звонка, журнал
 * звонка, полоса «Идёт звонок» над группой и приглашения в личных чатах.
 *
 * Здесь — то, чего не знают ни экраны, ни [CallHost]: откуда звонок начат, кого отметили до
 * него, кому и что сказать. Сам звонок — у [CallHost].
 */
class GroupCallDesk(
    private val calls: Calls,
    private val groups: GroupRegistry,
    private val createChat: CreateGroupChat,
    private val host: CallHost,
    private val scope: CoroutineScope,
    private val me: String,
    /** Отправить текст в личную переписку с человеком: `(userId, текст)`. */
    private val sendTo: (String, String) -> Unit,
    /** Временная группа звонка создана — сверить группы, чтобы она встала в «Чаты». */
    private val onCallGroupCreated: () -> Unit = {},
    /** Открыть окно звонка — вход в идущий звонок из группы его не открывал (2026-10-01). */
    private val onShowCall: () -> Unit = {},
    /**
     * Кому приглашение в группу уже уходило — одно сообщение на группу звонка (заказчик
     * 2026-10-01): `(groupId) → кому`, и запомнить новых. Хранит сборка — в настройках
     * устройства: переживает перезапуск.
     */
    private val invitedBefore: suspend (String) -> Set<String> = { emptySet() },
    private val rememberInvited: suspend (String, Set<String>) -> Unit = { _, _ -> },
    /** Как назвать меня в имени временной группы: «@ник», а без ника — имя (2026-10-02). */
    private val myTitle: () -> String? = { null },
    private val words: () -> Words = { CurrentWords.value },
) {
    /** Откуда открыли групповой звонок. */
    data class Ask(
        /** Группа звонка; `null` — группы ещё нет (из личного чата или журнала контактов). */
        val groupId: String?,
        val title: String,
        val canStart: Boolean = true,
    )

    /** Что в журнале звонка: собираем звонок, собираем чат, или звонок идёт. */
    enum class Mode { Pick, Chat, Live }

    var setup by mutableStateOf<Ask?>(null)
        private set
    var ledger by mutableStateOf<Pair<Mode, Ask>?>(null)
        private set
    var help by mutableStateOf(false)

    /** Открыто подокно «Вид» из настройки звонка; «‹» возвращает в настройку. */
    var viewing by mutableStateOf(false)
    var ring by mutableStateOf(false)
    var video by mutableStateOf(true)

    /** Кого отметили до звонка — раздел «Участники» журнала (решение 8). */
    val chosen = mutableStateListOf<String>()

    /** Участники группы — кандидаты, когда звонок в группе. */
    var groupMembers by mutableStateOf<List<String>>(emptyList())
        private set

    /** Беда последнего действия словами: нет связи, чат не создан. */
    var trouble by mutableStateOf<String?>(null)

    /** Звонок группы со слов сервера — полоса «Идёт звонок» и журнал звонка. */
    val live = mutableStateMapOf<String, GroupCallInfo?>()

    /** Приглашения: группа → что с её звонком сейчас. */
    val invites = mutableStateMapOf<String, CallInvite>()
    private val asked = mutableSetOf<String>()

    /** Звонок в группе из «⋯» личного чата: этот человек отмечен сразу (решение 9). */
    fun fromPerson(userId: String) {
        reset()
        chosen += userId
        setup = Ask(groupId = null, title = defaultTitle())
    }

    /** Кнопка в группе: идёт звонок — войти; нет — настройка (решения 1, 3б, 14). */
    fun fromGroup(groupId: String, title: String) {
        reset()
        scope.launch {
            val info = calls.groupCall(groupId)
            live[groupId] = info
            val call = info?.call
            if (call != null) {
                join(call.callId, groupId, title)
                onShowCall()
                return@launch
            }
            setup = Ask(groupId = groupId, title = title, canStart = info?.canStart == true)
        }
    }

    /** Без группы — из журнала контактов. */
    fun fromLedger() {
        reset()
        setup = Ask(groupId = null, title = defaultTitle())
    }

    private fun reset() {
        chosen.clear()
        ring = false
        video = true
        trouble = null
        help = false
    }

    fun closeSetup() {
        setup = null
        viewing = false
    }

    /** Первая кнопка настройки — журнал: контактов или группы. */
    fun pick() {
        val ask = setup ?: return
        setup = null
        ledger = Mode.Pick to ask
        ask.groupId?.let { loadMembers(it) }
    }

    /** «Создать чат группового звонка». */
    fun pickForChat() {
        val ask = setup ?: return
        setup = null
        ledger = Mode.Chat to ask
    }

    /** Журнал идущего звонка — кнопка «👥» окна 0. */
    fun openLive() {
        val g = host.group ?: return
        help = false
        ledger = Mode.Live to Ask(groupId = g.groupId, title = g.title)
        loadMembers(g.groupId)
        refresh(g.groupId)
    }

    fun closeLedger() {
        ledger = null
        help = false
    }

    private fun loadMembers(groupId: String) {
        scope.launch {
            val step = groups.members(groupId)
            if (step is MembersStep.Members) groupMembers = step.members.map { it.userId }.filter { it != me }
        }
    }

    fun refresh(groupId: String) {
        scope.launch { live[groupId] = calls.groupCall(groupId) }
    }

    /** Перечитать вскоре — после команды, когда сервер её уже записал. */
    fun refreshSoon(groupId: String) {
        scope.launch {
            delay(600)
            live[groupId] = calls.groupCall(groupId)
        }
    }

    /** «Добавить»: до звонка — в участники; в звонке — позвать (решение 8). */
    fun add(ids: List<String>) {
        if (ledger?.first == Mode.Live) {
            host.inviteMore(ids)
            ledger?.second?.groupId?.let { g -> scope.launch { delay(600); refresh(g) } }
            return
        }
        for (id in ids) if (id !in chosen) chosen += id
    }

    /** «Удалить»: до звонка — из участников; в звонке — из звонка, не из группы (решение 17). */
    fun remove(ids: List<String>) {
        if (ledger?.first == Mode.Live) {
            for (id in ids) host.control(GroupControl.Remove, id)
            ledger?.second?.groupId?.let { g -> scope.launch { delay(600); refresh(g) } }
            return
        }
        chosen.removeAll(ids)
    }

    /** Главная кнопка журнала: «Позвонить» или «Создать чат». */
    fun primary(onCall: () -> Unit, onChat: (String, String) -> Unit) {
        val (mode, ask) = ledger ?: return
        val people = chosen.toList()
        trouble = null
        when {
            mode == Mode.Pick && ask.groupId != null -> {
                ledger = null
                host.startGroup(ask.groupId, ask.title, ring, video, people)
                onCall()
            }
            else -> scope.launch {
                when (val made = createChat.createForCall(ask.title, people)) {
                    is CreateGroupStep.Created -> {
                        Journal.note(
                            LogCode.CALL, "временная группа звонка создана",
                            "группа" to made.groupId.take(8), "позвано" to people.size, "не добавлены" to made.notInvited.size,
                        )
                        ledger = null
                        onCallGroupCreated()
                        if (mode == Mode.Pick) {
                            host.startGroup(made.groupId, ask.title, ring, video, people)
                            onCall()
                        } else {
                            onChat(made.groupId, ask.title)
                        }
                    }
                    else -> {
                        Journal.trouble(LogCode.CALL, "временная группа звонка не создана", "ответ" to made::class.simpleName.orEmpty())
                        trouble = words().groupCall.createFailed
                    }
                }
            }
        }
    }

    /** Войти в идущий звонок группы — полоса в группе или приглашение. */
    fun join(callId: String, groupId: String, title: String) {
        host.joinGroup(callId, groupId, title, video = false)
    }

    /** Приглашение в личном чате: что с его звонком — спросить один раз за показ. */
    fun inviteOf(groupId: String, title: String): CallInvite {
        invites[groupId]?.let { return it }
        // Спросить один раз; писать в состояние посреди рисования нельзя — пишет корутина.
        if (!asked.add(groupId)) return CallInvite.Checking(title)
        scope.launch {
            val info = calls.groupCall(groupId)
            live[groupId] = info
            invites[groupId] = if (info?.call != null) CallInvite.Live(title) else CallInvite.Ended(title)
        }
        return CallInvite.Checking(title)
    }

    /**
     * Звонок в группе начался, кончился или группа удалена — карточку приглашения спросить
     * заново. Раньше забывался ответ, но не то, что спрашивали, и карточка залипала на
     * «Узнаём, идёт ли звонок…» (заказчик 2026-10-01).
     */
    fun inviteChanged(groupId: String, state: String, title: String) {
        when (state) {
            "ended", "deleted" -> invites[groupId] = CallInvite.Ended(title)
            else -> {
                invites.remove(groupId)
                asked.remove(groupId)
            }
        }
    }

    /** Нажали «Присоединиться» в приглашении. */
    fun joinInvite(groupId: String, title: String) {
        scope.launch {
            val call = calls.groupCall(groupId)?.call
            if (call == null) {
                invites[groupId] = CallInvite.Ended(title)
                return@launch
            }
            join(call.callId, groupId, title)
        }
    }

    /** Звонок начат мной — приглашения позванным в личные чаты (решение 3а). */
    fun sendInvites(groupId: String, invited: List<String>) {
        scope.launch {
            val to = invited.ifEmpty {
                (groups.members(groupId) as? MembersStep.Members)?.members?.map { it.userId }.orEmpty()
            }.filter { it != me }.distinct()
            // Одно приглашение на группу звонка: кому уже уходило, второго не шлём — его
            // карточка и так покажет, идёт ли звонок сейчас (заказчик 2026-10-01).
            val before = invitedBefore(groupId)
            val fresh = to.filter { it !in before }
            for (u in fresh) sendTo(u, CallInviteLink.of(groupId))
            if (fresh.isNotEmpty()) rememberInvited(groupId, before + fresh)
            Journal.note(
                LogCode.CALL, "приглашения в групповой звонок разосланы", "группа" to groupId.take(8),
                "кому" to fresh.size, "уже приглашены" to (to.size - fresh.size),
            )
        }
    }

    /** Полоса «Идёт звонок» группы — опрос, пока группа на экране. */
    suspend fun watchGroup(groupId: String) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            live[groupId] = calls.groupCall(groupId)
            delay(BANNER_EVERY_MS)
        }
    }

    /** Строки раздела «Участники» в журнале идущего звонка. */
    fun liveMembers(peers: List<CallPeer>, nameOf: (String) -> String, faceOf: (String) -> ImageBitmap?): List<CallMember> {
        val g = host.group ?: return emptyList()
        val info = live[g.groupId]?.call
        val creator = info?.creatorId ?: g.creatorId
        val st = host.state
        val self = CallMember(
            userId = me, name = nameOf(me), letters = lettersOf(nameOf(me)), state = CallMemberState.Self,
            face = faceOf(me), microphoneOn = st.microphoneOn, cameraOn = st.cameraOn, creator = creator == me,
            micForbidden = host.micForbidden, videoForbidden = host.videoForbidden,
        )
        val byUser = peers.associateBy { it.userId }
        val fromServer = info?.members.orEmpty().filter { it.userId != me }.map { m ->
            val peer = byUser[m.userId]
            CallMember(
                userId = m.userId, name = nameOf(m.userId), letters = lettersOf(nameOf(m.userId)),
                state = when {
                    m.removed -> CallMemberState.Removed
                    peer != null || m.state == "joined" -> CallMemberState.In
                    m.state == "left" -> CallMemberState.Left
                    else -> CallMemberState.Invited
                },
                face = faceOf(m.userId), microphoneOn = peer?.microphoneOn == true, cameraOn = peer?.cameraOn == true,
                creator = creator == m.userId, micForbidden = m.micForbidden, videoForbidden = m.videoForbidden,
            )
        }
        val known = fromServer.map { it.userId }.toSet()
        val extra = peers.filter { it.userId != me && it.userId !in known }.distinctBy { it.userId }.map { p ->
            CallMember(
                userId = p.userId, name = nameOf(p.userId), letters = lettersOf(nameOf(p.userId)), state = CallMemberState.In,
                face = faceOf(p.userId), microphoneOn = p.microphoneOn, cameraOn = p.cameraOn, creator = creator == p.userId,
            )
        }
        return listOf(self) + fromServer + extra
    }

    /**
     * Имя временной группы — «Групповой звонок @ник» автора (заказчик 2026-10-02, 2а: без ника —
     * имя). Ни того ни другого — время, как было.
     */
    private fun defaultTitle(): String {
        myTitle()?.takeIf { it.isNotBlank() }?.let { return words().groupCall.title + " " + it }
        val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val hh = t.hour.toString().padStart(2, '0')
        val mm = t.minute.toString().padStart(2, '0')
        return words().groupCall.title + " " + hh + ":" + mm
    }

    private companion object {
        const val BANNER_EVERY_MS = 15_000L
    }
}

/** Буквы аватара из имени — не больше двух. */
internal fun lettersOf(name: String): String = name.trim().split(" ").take(2)
    .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifEmpty { "+" }

/**
 * Окна группового звонка поверх всего: настройка (решение 3) и журнал звонка (решение 8).
 * `false` — показывать нечего, рисует то, что под ними.
 */
@Composable
fun GroupCallOverlays(
    desk: GroupCallDesk,
    host: CallHost,
    me: String,
    nameOf: (String) -> String,
    faceOf: (String) -> ImageBitmap?,
    /** Книга: кого можно позвать без группы. */
    book: List<CallCandidate>,
    sections: List<Section>,
    view: BookView,
    onShowCall: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    /** Вид группового — тот же, что в звонке: выбор здесь становится видом звонка. */
    groupView: io.tima.feature.call.GroupView = io.tima.feature.call.GroupView(),
): Boolean {
    val words = Tima.words.groupCall
    desk.setup?.let { ask ->
        if (desk.viewing) {
            Box(
                Modifier.fillMaxSize().background(Tima.colors.text.copy(alpha = 0.45f)).clickable { desk.viewing = false },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(Modifier.fillMaxWidth().background(Tima.colors.surface).clickable(enabled = false) {}) {
                    // Крестик — как «назад»: в подокно «Групповой звонок» (заказчик 2026-10-02).
                    Header(title = words.viewButton, onBack = { desk.viewing = false }, onClose = { desk.viewing = false })
                    io.tima.feature.call.GroupViewChoice(groupView, onCollapse = { desk.viewing = false })
                }
            }
            return true
        }
        Box(
            Modifier.fillMaxSize().background(Tima.colors.text.copy(alpha = 0.45f)).clickable { desk.closeSetup() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(Modifier.fillMaxWidth().background(Tima.colors.surface).clickable(enabled = false) {}) {
                Header(title = words.title + (ask.groupId?.let { " · " + ask.title } ?: ""), onClose = desk::closeSetup)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GroupCallSetup(
                        ring = desk.ring,
                        video = desk.video,
                        fromGroup = ask.groupId != null,
                        onRing = { desk.ring = it },
                        onVideo = { desk.video = it },
                        onPick = desk::pick,
                        onCreateChat = if (ask.groupId == null) desk::pickForChat else null,
                        noRights = !ask.canStart,
                        // Тип — зелёным: «Говорящий» или «Групповой · по 4».
                        viewNow = (
                            if (groupView.mode == io.tima.feature.call.GroupMode.Speaker) {
                                words.viewSpeaker
                            } else {
                                words.viewGrid + " · " + (when (groupView.perPage) { 1 -> words.viewOne; 2 -> words.viewTwo; else -> words.viewFour }).lowercase()
                            }
                            ) + " · " + (if (groupView.showSelf) words.viewSelfOn else words.viewSelfOff),
                        onView = { desk.viewing = true },
                    )
                }
            }
        }
        return true
    }
    val (mode, ask) = desk.ledger ?: return false
    val live = mode == GroupCallDesk.Mode.Live
    val peers by host.peers.collectAsState()
    if (live) {
        LaunchedEffect(ask.groupId) {
            val g = ask.groupId ?: return@LaunchedEffect
            while (true) {
                desk.refresh(g)
                delay(3_000)
            }
        }
        // Звонок кончился — журнал идущего звонка закрывается сам.
        if (!host.active || host.state.stage == CallStage.Ended) {
            LaunchedEffect(Unit) { desk.closeLedger() }
        }
    }
    val members = if (live) {
        desk.liveMembers(peers, nameOf, faceOf)
    } else {
        listOf(
            CallMember(me, nameOf(me), lettersOf(nameOf(me)), CallMemberState.Self, faceOf(me), creator = true),
        ) + desk.chosen.map { u -> CallMember(u, nameOf(u), lettersOf(nameOf(u)), CallMemberState.Added, faceOf(u)) }
    }
    val fromGroup = ask.groupId != null
    val candidates = if (fromGroup) {
        desk.groupMembers.map { u -> CallCandidate(u, nameOf(u), lettersOf(nameOf(u)), faceOf(u)) }
    } else {
        book
    }
    val mine = !live || host.group?.mine == true
    val max = host.group?.rules?.max ?: desk.live[ask.groupId]?.rules?.max ?: io.tima.core.call.GroupRules().max
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        Header(
            title = if (desk.help) words.helpTitle else words.ledgerTitle,
            onBack = if (desk.help) ({ desk.help = false }) else null,
            onHelp = if (!desk.help) ({ desk.help = true }) else null,
            onClose = desk::closeLedger,
            ledger = true,
        )
        if (desk.help) {
            CallLedgerHelpPage(Modifier.verticalScroll(rememberScrollState()))
        } else {
            CallLedgerPage(
                members = members,
                candidates = candidates,
                sections = sections,
                live = live,
                mine = mine,
                fromGroup = fromGroup,
                max = max,
                modifier = Modifier.weight(1f),
                paused = host.state.roomPaused,
                view = view,
                setup = if (live) null else CallLedgerSetup(
                    ring = desk.ring,
                    video = desk.video,
                    primary = if (mode == GroupCallDesk.Mode.Chat) words.createChatNow else words.callNow,
                    onRing = { desk.ring = it },
                    onVideo = { desk.video = it },
                    onPrimary = { desk.primary(onCall = onShowCall, onChat = onOpenChat) },
                    trouble = desk.trouble,
                ),
                onAdd = desk::add,
                onRemove = desk::remove,
                onForbidMic = { ids, forbid ->
                    ids.forEach { host.control(if (forbid) GroupControl.MuteMic else GroupControl.AllowMic, it) }
                    ask.groupId?.let { g -> desk.refreshSoon(g) }
                },
                onForbidCamera = { ids, forbid ->
                    ids.forEach { host.control(if (forbid) GroupControl.MuteVideo else GroupControl.AllowVideo, it) }
                    ask.groupId?.let { g -> desk.refreshSoon(g) }
                },
                onPause = { on -> host.control(if (on) GroupControl.Pause else GroupControl.Resume) },
                onStop = {
                    host.control(GroupControl.Stop)
                    desk.closeLedger()
                },
                onOwnMic = host::microphone,
                onOwnCamera = host::camera,
            )
        }
    }
    return true
}

@Composable
private fun Header(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    /** Шапка журнала — вторым уровнем серого (заказчик 2026-10-02). */
    ledger: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(if (ledger) Tima.colors.quiet else Tima.colors.functional)
            .heightIn(min = TimaZones.zone1).padding(horizontal = TimaSpacing.about4),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let { IconButton(glyph = "‹", onClick = it, live = true) }
        Box(Modifier.weight(1f)) { ProvidePlace(TextPlace.HEADERS) { Name(title) } }
        onHelp?.let { IconButton(glyph = "?", onClick = it) }
        IconButton(glyph = "✕", onClick = onClose)
    }
}
