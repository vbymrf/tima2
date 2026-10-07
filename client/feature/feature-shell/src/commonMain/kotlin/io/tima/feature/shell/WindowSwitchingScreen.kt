package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.tima.core.ui.Caption
import io.tima.core.ui.TimaType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import io.tima.core.ui.Avatar
import io.tima.core.ui.words
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Counter
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace

/**
 * Подокно «Переключение окон» — единственный видимый способ сменить окно на телефоне.
 *
 * Три решения макета, которые здесь важнее вида:
 *
 * 1. **Панель выезжает снизу, а не разворачивается от логотипа.** До низа экрана палец
 *    дотягивается, до верхнего левого угла — нет. Открывает её при этом верхний левый
 *    угол, и это не противоречие: нажимают редко, а выбирают из списка часто.
 * 2. **У каждого окна вторая строка о том, что внутри.** «Свободное общение» ничего не
 *    говорит человеку, который туда не ходил, — а решение зайти принимается здесь.
 * 3. **Это единственное место, где счётчики всех окон видны разом.** Панели вкладок в
 *    приложении нет, собрать их больше негде (`интерфейс.md §1`).
 *
 * Под панелью остаётся то окно, где человек был: он не ушёл никуда, а приподнял
 * список поверх. Поэтому фон затемняется, а не подменяется.
 */
@Composable
fun WindowSwitchingScreen(
    current: Window,
    name: String,
    alias: String,
    onSelect: (Window) -> Unit,
    /**
     * Номер телефона — в шапке, под именем (решение заказчика 2026-09-15, как в макете).
     *
     * Номер — то, чем человека находят и что он диктует, чтобы его добавили; ник —
     * второй способ. Пусто — строки нет: сессия номер не хранит, он приходит из
     * `GET /users/me`, и до ответа выдумывать строку на его месте нельзя.
     */
    phone: String = "",
    /** Идёт ли звонок: от этого зависит, есть ли в переключателе окно 0. */
    inCall: Boolean = false,
    /**
     * Строка «Активный звонок» над окнами — вернуться в окно 0. `null` — звонка нет.
     *
     * **Окно 0 эту панель не замещает**: пришёл вызов, пока она открыта, — и человек
     * видит список окон, а принять нечем (заказчик 2026-09-25). Пункт «0» в списке есть,
     * но он один из восьми и ничем не выделен; звонок — единственное, что ждать не может,
     * поэтому у него своя строка первой и цветом.
     */
    onCall: (() -> Unit)? = null,
    /** Звонят нам и ещё не ответили: строка тогда говорит «Входящий вызов». */
    callRinging: Boolean = false,
    /** С кем звонок — второй строкой. Пусто — строки нет. */
    callPeer: String = "",
    bench: Boolean = false,
    /** Аватар в шапке. `null` — буква, как и было. */
    avatar: ImageBitmap? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Непрочитанное по окнам. Нет записи — нет и числа. */
    counters: Map<Window, Int> = emptyMap(),
    onSettings: (() -> Unit)? = null,
    /**
     * Своя страница — «Я» (заказчик 2026-09-25): значок в шапке, а также нажатие на
     * аватар, имя или телефон.
     *
     * Было «Изменить» — прямо в правку профиля. Но сначала человеку нужно видеть, что
     * о его аккаунте известно (временный ли он, когда может быть удалён), а правка —
     * кнопка уже на той странице.
     */
    onProfile: (() -> Unit)? = null,
    /**
     * Аккаунты этого устройства: пара «идентификатор — как называть» (Д11).
     *
     * Один аккаунт — списка нет вовсе: строка «переключиться» там, где переключаться
     * не на что, обещает несуществующее.
     */
    accounts: List<Pair<String, String>> = emptyList(),
    currentAccount: String = "",
    onAccount: (String) -> Unit = {},
    /**
     * Неотправленное по аккаунтам (Д11, путь Б, смягчение 2).
     *
     * Ушедший не наказан: несказанное остаётся у аккаунта числом, и вход в него запускает
     * досылку. Без этой метки ожидание было бы тихим — человек видел «отправляется» и
     * ушёл бы уверенным, что отправлено.
     */
    unsent: Map<String, Int> = emptyMap(),
    /**
     * «Завести виртуальный аккаунт» (Д11).
     *
     * Стоит здесь, а не в настройках: человек заводит второго себя, и место этому там,
     * где он этих себя выбирает. Показывается и при единственном аккаунте — иначе завести
     * второй было бы неоткуда.
     */
    onNewAccount: (() -> Unit)? = null,
    /**
     * «Выйти» — уйти с экрана, фон работает: звонки и сообщения приходят (заказчик
     * 2026-09-30). `null` — пункта нет.
     */
    onLeave: (() -> Unit)? = null,
    /**
     * «Закрыть приложение» — последним пунктом (заказчик 2026-09-26, имя — 2026-09-30).
     * Фоновый канал останавливается, и звонки не придут, пока приложение снова не
     * открыто, — это сказано под кнопкой. `null` — пункта нет.
     */
    onExit: (() -> Unit)? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.switching
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Затемнение поверх окна, из которого пришли: человек не ушёл никуда, а
            // приподнял список над тем, где был.
            .background(colors.text.copy(alpha = DIM))
            // Касание вне панели закрывает — то же, что «✕». Оба входа обязаны быть:
            // касание вне угадывают не все, а «✕» ищут глазами.
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Панель не выше ~85 % экрана и прокручивается внутри. До 2026-09-15 прокрутки не
        // было вовсе — на телефоне это видно, стоит развернуть список: семь окон, шапка,
        // аккаунты и настройки в 640 точек высоты не помещаются, и низ уезжал за край
        // без единого способа до него добраться. Ограничение нужно вместе с прокруткой:
        // Column, растущий по содержимому, прокручивать нечему.
        val ceiling = maxHeight * PANEL_SHARE
        // Переключение окон — то же «меню», что и настройки: это список переходов.
        ProvidePlace(TextPlace.MENU) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ceiling)
                .clip(RoundedCornerShape(topStart = TimaShapes.radius, topEnd = TimaShapes.radius))
                .background(colors.surface)
                // Нажатие по самой панели не должно закрывать её вместе с фоном.
                .clickable(enabled = false, onClick = {})
                .verticalScroll(rememberScrollState()),
        ) {
            Header(name, alias, phone, avatar, onClose, onProfile)

            onCall?.let { goToCall -> CallBubble(callRinging, callPeer, goToCall) }

            // Окна — в общей рамке с зелёной окантовкой на мягкой подложке (пробы
            // `пробы-окно-переходов.html`, И1, приняты заказчиком 2026-10-07): это то, ради
            // чего панель открывают, и оно стоит сразу под шапкой.
            Column(
                Modifier
                    .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TimaShapes.radius))
                    .border(FRAME, colors.navigation, RoundedCornerShape(TimaShapes.radius))
                    .background(colors.softAccent),
            ) {
                for (window in Window.shown(inCall, bench)) {
                    Item(
                        window = window,
                        current = window == current,
                        howMany = counters[window] ?: 0,
                        onClick = { onSelect(window) },
                    )
                }
            }

            // Аккаунты — сеткой аватаров под окнами (И1): видны все сразу, переход — одним
            // нажатием. Есть и при одном аккаунте: там же «Добавить».
            if (accounts.isNotEmpty()) {
                AccountsGrid(accounts, currentAccount, unsent, onAccount, onNewAccount)
            }

            if (onSettings != null) {
                ListLine(
                    onClick = onSettings,
                    left = { Glyph("⚙") },
                    middle = { Name(words.settingsHelpBugs) },
                )
            }

            onLeave?.let {
                ListLine(
                    onClick = it,
                    left = { Glyph("↩") },
                    middle = {
                        Name(Tima.words.settings2.leaveApp)
                        Secondary(Tima.words.settings2.leaveAbout)
                    },
                )
            }

            onExit?.let {
                ListLine(
                    onClick = it,
                    left = { Glyph("🚪") },
                    middle = {
                        Name(Tima.words.settings2.exitApp)
                        Secondary(Tima.words.settings2.exitAbout)
                    },
                )
            }

            // Блогерские окна включаются в настройках; пока их нет, заголовок раздела
            // тоже не рисуем: пустой раздел обещает то, чего не существует.
        }
        }
    }
}

/**
 * Шапка: кто я и вход в профиль.
 *
 * Заголовка «Окна» здесь больше нет — он повторял то, что видно из списка под ним
 * (решение 2026-09-05). Вместо него имя и ник: это единственное место, где человек
 * видит свою учётную запись.
 */
@Composable
private fun Header(
    name: String,
    alias: String,
    phone: String,
    avatar: ImageBitmap?,
    onClose: () -> Unit,
    onProfile: (() -> Unit)?,
) {
    val colors = Tima.colors
    val words = Tima.words.switching
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Аватар, имя и телефон — один вход на свою страницу вместе со значком справа:
        // по себе нажимают, чтобы открыть себя, и промах мимо значка не должен теряться.
        val toSelf = if (onProfile != null) Modifier.clickable(onClick = onProfile) else Modifier
        // Картинка, если есть; иначе первая буква имени — тот же аватар, что в списках.
        Box(toSelf) { Avatar(letters = name.take(1).ifBlank { "Т" }.uppercase(), image = avatar) }
        Column(modifier = Modifier.weight(1f).then(toSelf)) {
            // Кто я — здесь, а не в шапке окна: имя нужно тому, кто выбирает, от чьего
            // лица он сейчас в приложении, а не тому, кто читает переписку.
            Name(name)
            // Номер выше ника: им человека находят в первую очередь (макет «Телефон, а
            // не псевдоним»). Пустой — строки нет.
            if (phone.isNotBlank()) Tertiary(phone, lineOne = true)
            if (alias.isNotBlank()) Tertiary(alias, lineOne = true)
        }
        // Значок своей страницы. Правка профиля — кнопкой уже на ней; второй вход в
        // правку остаётся в настройках.
        if (onProfile != null) IconButton(glyph = "👤", onClick = onProfile)
        IconButton(glyph = "✕", onClick = onClose)
    }
}

/**
 * Строка окна. Текущее — **и** зелёной полосой слева, **и** зелёной рамкой значка (заказчик
 * 2026-10-07, И1); приписки «вы здесь» нет.
 */
@Composable
private fun Item(window: Window, current: Boolean, howMany: Int, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        ListLine(
            onClick = onClick,
            left = { Glyph(window.glyph, current = current, plate = colors.surface) },
            right = { if (howMany > 0) Counter(howMany) },
            middle = {
                Column {
                    val words = Tima.words.windows
                    Name(words.full(window))
                    Secondary(words.about(window), lineOne = true)
                }
            },
        )
        if (current) {
            Box(Modifier.fillMaxHeight().width(STRIPE).background(colors.navigation))
        }
    }
}

/**
 * «Активный звонок» — залитый зелёный пузырь, как кнопки, на поверхности и сдвинутый вправо к
 * колонке текста (заказчик 2026-10-07: «Формат Активный звонок одобряю»).
 */
@Composable
private fun CallBubble(ringing: Boolean, peer: String, onClick: () -> Unit) {
    val colors = Tima.colors
    val callWords = Tima.words.call
    Row(
        Modifier
            .padding(start = CALL_INDENT, end = TimaSpacing.about4, top = TimaSpacing.about3, bottom = TimaSpacing.about1)
            .fillMaxWidth()
            .clip(RoundedCornerShape(TimaShapes.radius))
            .background(colors.navigation)
            .clickable(onClick = onClick)
            .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("📞", plate = colors.onAccent.copy(alpha = 0.25f))
        Column {
            Caption(if (ringing) callWords.incoming else callWords.activeCall, weight = FontWeight.Bold, color = colors.onAccent)
            if (peer.isNotBlank()) Caption(peer, fontSize = TimaType.sz5, color = colors.onAccent, lineOne = true)
        }
    }
}

/**
 * Аккаунты — сеткой аватаров (заказчик 2026-10-07, И1): пять постоянных мест в строке, не
 * влезло — следующая строка; «Добавить» — всегда в правой колонке последней строки. Текущий —
 * зелёной рамкой аватара, неотправленное — числом в углу.
 *
 * @param accounts пары «идентификатор — подпись»; подпись одна на всё приложение (имя, @ник,
 * служебное имя — `AccountTitle`)
 */
@Composable
private fun AccountsGrid(
    accounts: List<Pair<String, String>>,
    current: String,
    unsent: Map<String, Int>,
    onAccount: (String) -> Unit,
    onNew: (() -> Unit)?,
) {
    val words = Tima.words.switching
    Column(
        Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        accountSlots(accounts.size, add = onNew != null).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { slot ->
                    val m = Modifier.weight(1f)
                    when {
                        slot == null -> Spacer(m)
                        slot == ADD_SLOT && onNew != null -> AddCell(words.addAccount, m, onNew)
                        else -> {
                            val (userId, label) = accounts[slot]
                            AccountCell(label, userId == current, unsent[userId] ?: 0, m) { if (userId != current) onAccount(userId) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Раскладка сетки аккаунтов: строки по [GRID] мест, в месте — номер аккаунта, [ADD_SLOT] или
 * `null` (пусто). Места постоянные; «Добавить» — всегда в правой колонке последней строки, и если
 * последняя строка заполнена аккаунтами до конца, «Добавить» уходит на новую строку.
 */
internal fun accountSlots(accounts: Int, add: Boolean): List<List<Int?>> {
    val slots = mutableListOf<Int?>()
    repeat(accounts) { slots += it }
    if (add) {
        while (slots.size % GRID != GRID - 1) slots += null
        slots += ADD_SLOT
    }
    return slots.chunked(GRID).map { row -> row + List(GRID - row.size) { null } }
}

/** Место «Добавить» в [accountSlots]. */
internal const val ADD_SLOT = -1

@Composable
private fun AccountCell(label: String, current: Boolean, waiting: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = Tima.colors
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                if (current) Modifier.border(FRAME, colors.navigation, RoundedCornerShape(TimaShapes.smallSquare + FRAME)).padding(FRAME)
                else Modifier.padding(FRAME),
            ) {
                Avatar(letters = label.trimStart('@').take(1).uppercase().ifBlank { "?" })
            }
            if (waiting > 0) Counter(waiting, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
        }
        Caption(label, fontSize = TimaType.sz6, weight = FontWeight.Bold, lineOne = true, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddCell(label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = Tima.colors
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .padding(FRAME)
                .size(ADD_SIDE)
                .border(1.5.dp, colors.navigation, RoundedCornerShape(TimaShapes.smallSquare)),
            contentAlignment = Alignment.Center,
        ) { Caption("+", fontSize = TimaType.sz2, weight = FontWeight.Bold, color = colors.navigation) }
        Caption(label, fontSize = TimaType.sz6, weight = FontWeight.Bold, lineOne = true, textAlign = TextAlign.Center)
    }
}

/** Мест в строке сетки аккаунтов: пять аватаров на ширину телефона. */
internal const val GRID = 5

/** Толщина зелёной рамки — окна, текущий значок, текущий аккаунт. */
private val FRAME = 2.dp

/** Полоса текущего окна. */
private val STRIPE = 5.dp

/** Сдвиг пузыря звонка — до колонки текста под шапкой. */
private val CALL_INDENT = 58.dp

/** Сторона «Добавить» — как у аватара. */
private val ADD_SIDE = 42.dp

@Composable
private fun Glyph(glyph: String, current: Boolean = false, plate: Color? = null) {
    val colors = Tima.colors
    val shape = RoundedCornerShape(TimaShapes.smallSquare)
    Box(
        modifier = Modifier
            .then(if (current) Modifier.border(FRAME, colors.navigation, shape) else Modifier)
            .background(plate ?: colors.softAccent, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Name(glyph) }
}

/** Насколько затемняется окно под панелью. Меньше — панель «висит», больше — окно исчезает. */
private const val DIM = 0.32f

/**
 * Доля высоты экрана, выше которой панель не растёт — дальше прокрутка.
 *
 * Не 100 %: над панелью обязана остаться полоса того окна, откуда пришли, — она и
 * говорит, что это панель поверх, а не новый экран. 85 % оставляет её на любом телефоне.
 */
private const val PANEL_SHARE = 0.85f

/**
 * Уход из аккаунта с непустой очередью — ПЛАН-(Д)-КОНТАКТОВ.md, Д11 (путь Б, смягчение 1).
 *
 * **Вопрос, а не сообщение.** Оба ответа законны, и умалчивать нельзя ни о том, ни о
 * другом: молча уйти значит соврать про «отправляется», молча подождать — задержать того,
 * кто спешит.
 *
 * Почему вопрос вообще возникает: конверт собирается перед посылкой, а не при написании
 * (ADR-0020 §3), — значит для отправки нужны ключи аккаунта, живые и в памяти. Вышел из
 * аккаунта — ключей нет, и написанное waiting до возвращения. Досылка «на выходе», пока
 * ключи ещё в памяти, покрывает почти всё, ради чего иначе пришлось бы заводить очередь
 * как службу — а служба заставила бы устройство работать от имени аккаунта, в который
 * никто не вошёл.
 */
@Composable
fun AccountLeavingSheet(
    /** Сколько waiting неотправленного. Ноль сюда не приходит: вопроса тогда нет. */
    howMany: Int,
    onWait: () -> Unit,
    onLeaveNow: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.switching
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.text.copy(alpha = DIM))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = TimaShapes.radius, topEnd = TimaShapes.radius))
                .background(colors.surface)
                .clickable(enabled = false, onClick = {})
                .padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            SectionTitle(words.notSentSection)
            Name(words.waiting(howMany))
            Secondary(
                words.waitingAbout,
            )
            ListLine(
                onClick = onWait,
                left = { Glyph("↑") },
                middle = { Name(words.waitForSending) },
            )
            ListLine(
                onClick = onLeaveNow,
                left = { Glyph("→") },
                middle = { Name(words.leaveNow) },
            )
        }
    }
}

/**
 * Вопрос перед «Закрыть приложение» (заказчик 2026-09-30, 3а): закрыть совсем или выйти.
 *
 * Закрытие останавливает фон — звонки и сообщения не придут до следующего открытия, и
 * случайное касание молча оставило бы человека без входящих. Поэтому вопрос предлагает и
 * то, что человек, скорее всего, хотел: выйти, оставив фон работать.
 */
@Composable
fun CloseQuestionSheet(
    /** Закрыть совсем: фон останавливается. */
    onClose: () -> Unit,
    /** Выйти, фон работает. `null` — платформа этого не умеет (ПК без трея), строки нет. */
    onLeave: (() -> Unit)?,
    /** Передумал — касание вне панели. */
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.settings2
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.text.copy(alpha = DIM))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = TimaShapes.radius, topEnd = TimaShapes.radius))
                .background(colors.surface)
                .clickable(enabled = false, onClick = {})
                .padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            SectionTitle(words.closeQuestion)
            ListLine(
                onClick = onClose,
                left = { Glyph("🚪") },
                middle = {
                    Name(words.closeYes)
                    Secondary(words.exitAbout)
                },
            )
            onLeave?.let {
                ListLine(
                    onClick = it,
                    left = { Glyph("↩") },
                    middle = {
                        Name(words.leaveApp)
                        Secondary(words.leaveAbout)
                    },
                )
            }
        }
    }
}
