package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import io.tima.core.ui.TimaType
import io.tima.core.ui.Caption
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Name
import io.tima.core.ui.ListLine
import io.tima.core.ui.CheckMark
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.RadioMark

/**
 * Настройки уведомлений — ПЛАН-(У)-УВЕДОМЛЕНИЙ.md, У1 и У14.
 *
 * Пункт «Уведомления» стоял в списке настроек с самого начала и **не открывал ничего**.
 * Теперь здесь два действия, и оба нужны, чтобы уведомления работали на Android:
 *
 * 1. **Право показывать** (`POST_NOTIFICATIONS`). Без него не видно ни одной строки,
 *    включая постоянную строку службы. Приложение при этом работает — и это худший вид
 *    поломки: искать будут где угодно, только не в разрешении.
 * 2. **Не усыплять нас.** Системный белый список ставится одним нажатием, но у realme и
 *    Xiaomi поверх стоят **свои** списки автозапуска, которых он не касается. Обещать
 *    «включили — работает» нельзя, и экран говорит это прямо.
 *
 * ── ЧЕГО ЗДЕСЬ НЕТ ──────────────────────────────────────────────────────────
 *
 * Выбора «показывать текст сообщения». Решение заказчика 2026-09-24: текст не
 * показывается никогда — он зашифрован, и человек увидит его, открыв переписку. А чем
 * звать человека, решает общая настройка «Отображать пользователя как»: заводить здесь
 * вторую значило бы два источника имени, которые однажды разойдутся.
 */
@Composable
fun NotificationsScreen(
    modifier: Modifier = Modifier,
    /** Мелодия звонка (ВЗ4). `null` — раздела нет (проверки). */
    ring: SoundRow? = null,
    /** Звук уведомления о сообщении (ВЗ4). */
    message: SoundRow? = null,
    /** Не показывать уведомления с … до … (заказчик 2026-10-01). `null` — строки нет. */
    quiet: QuietRow? = null,
    /** Экономичный режим канала (заказчик 2026-10-06). `null` — строки нет. */
    economy: EconomyRow? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.settings2

    // ── ВИД — КАК У СПИСКА НАСТРОЕК (заказчик 2026-10-01) ──────────────────────
    //
    // Было: подписи мелкие, а под каждой настройкой — четыре крупные кнопки; выбранное
    // висело строкой без подписи, что это; разделителей не было. Теперь каждая настройка —
    // строка, как в самом списке настроек: значок, название, под ним — что это, справа —
    // что выбрано. Нажатие раскрывает выбор: варианты строками с точкой «одно из» и
    // пояснением. Те же размеры, что у списка настроек (группа «меню», ПЛАН-(Ш)-ШРИФТОВ Ш3).
    ProvidePlace(TextPlace.MENU) {
        Column(
            modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState())
                .padding(bottom = TimaSpacing.about5),
        ) {
            // ── ЗВУКИ (ВЗ4) ─────────────────────────────────────────────────
            //
            // Общие мелодия звонка и звук сообщения. Своя мелодия у контакта — в журнале
            // контактов (ВЗ8), и она важнее общей.
            if (ring != null || message != null) {
                SectionTitle(words.soundsTitle)
                ring?.let { SoundSetting("📞", words.soundRing, words.soundRingAbout, it) }
                message?.let { SoundSetting("💬", words.soundMessage, words.soundMessageAbout, it) }
                Column(Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)) {
                    Tertiary(words.soundsNotSynced)
                }
            }

            // Ниже «Звуков» (заказчик 2026-10-01): что видно и когда не показывать.
            SectionTitle(words.noticesShow)
            // Что видно в уведомлении — сведение, а не выбор: строка без стрелки.
            // Сказано до нажатия, а не после: текст сообщения не показывается никогда, и
            // человек вправе знать это, не выясняя опытом.
            ListLine(
                left = { Name("👁") },
                middle = {
                    Caption(words.noticesSeen, fontSize = TimaType.sz4, weight = FontWeight.Bold, maxLines = 2)
                    Secondary(words.noticesWhat)
                    Tertiary(words.noticesNoText)
                },
            )
            quiet?.let { QuietSetting(it) }
            economy?.let { EconomySetting(it) }
        }
    }
}

/**
 * Разрешения — одно место для всех (заказчик 2026-09-26).
 *
 * Сюда переехали право показывать уведомления, канал «Звонки», «во весь экран» и работа в
 * фоне — из «Уведомлений», — и добавились микрофон, камера и контакты, которые до того
 * спрашивались только по месту. Событие «звонки могут не дойти» ведёт сюда.
 *
 * Каждое разрешение — строкой «разрешено» или кнопкой. `null` у состояния — платформе
 * спрашивать нечего (ПК): раздела нет вовсе, а не «неактивный».
 */
@Composable
fun PermissionsScreen(
    /** Право показывать уведомления. */
    access: NotifyAccess,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
    onBattery: (() -> Unit)? = null,
    batteryFree: Boolean = false,
    callsChannelOn: Boolean? = null,
    onCallsChannel: (() -> Unit)? = null,
    fullScreenOn: Boolean? = null,
    onFullScreen: (() -> Unit)? = null,
    microphone: Boolean? = null,
    camera: Boolean? = null,
    /** Спросить микрофон (`false`) или камеру с микрофоном (`true`). */
    onAskCall: (Boolean) -> Unit = {},
    /** Страница приложения в настройках телефона — когда система больше не спрашивает. */
    onCallSettings: () -> Unit = {},
    /**
     * Микрофон и камеру не спросить — только открыть настройки. Так на ПК: доступ там дают
     * переключатели Windows, диалога «разрешить» для классических программ у неё нет.
     */
    callInSettings: Boolean = false,
    contacts: Boolean? = null,
    /** Система больше не спросит про контакты — кнопка ведёт в настройки. */
    contactsInSettings: Boolean = false,
    onAskContacts: () -> Unit = {},
    /** Запускается ли вместе с системой. `null` — раздела нет (телефон). */
    autostart: Boolean? = null,
    /** Включить или выключить. `null` при известном [autostart] — включить нечем (запуск из исходников). */
    onAutostart: ((Boolean) -> Unit)? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.settings2

    Column(
        modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState())
            .padding(bottom = TimaSpacing.about5),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        // ── УВЕДОМЛЕНИЯ ─────────────────────────────────────────────────────
        Title(words.itemNotifications, missing = access != NotifyAccess.Given)
        when (access) {
            NotifyAccess.Given -> Column(Modifier.padding(horizontal = TimaSpacing.about4)) {
                Granted(words.noticesAllowed)
            }
            // Кнопка называет себя: в первый раз она поднимет системный диалог, а после
            // отказа уведёт из приложения в настройки. Это разные действия, и молчать о
            // втором нельзя (тот же довод, что у кнопки разрешения на контакты, Л1).
            NotifyAccess.Ask -> Column(Modifier.padding(horizontal = TimaSpacing.about4)) {
                Button(label = words.noticesAllow, onClick = onAsk, kind = ButtonKind.Action)
            }
            NotifyAccess.Settings -> Column(
                Modifier.padding(horizontal = TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Secondary(words.noticesRefused)
                Button(label = words.noticesOpenSettings, onClick = onAsk, kind = ButtonKind.Action)
            }
        }

        Rule()

        // ── ЗВОНКИ: КАНАЛ И ВО ВЕСЬ ЭКРАН ────────────────────────────────────
        //
        // Канал выключают одного, при разрешённых остальных уведомлениях, и тогда молчит
        // именно входящий (ВЗ0г).
        if (callsChannelOn != null && onCallsChannel != null) {
            Title(words.noticesCalls, missing = !callsChannelOn || fullScreenOn == false)
            Column(
                Modifier.padding(horizontal = TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Secondary(words.noticesCallsAbout)
                if (callsChannelOn) {
                    Granted(words.noticesCallsOn)
                } else {
                    Secondary(words.noticesCallsOff)
                    Button(label = words.noticesOpenSettings, onClick = onCallsChannel, kind = ButtonKind.Action)
                }
                if (fullScreenOn == true) Granted(words.noticesFullScreenOn)
                if (fullScreenOn == false && onFullScreen != null) {
                    Secondary(words.noticesFullScreenOff)
                    Button(label = words.noticesOpenSettings, onClick = onFullScreen, kind = ButtonKind.Action)
                }
            }
            Rule()
        }

        // ── МИКРОФОН И КАМЕРА ────────────────────────────────────────────────
        if (microphone != null) {
            PermissionRow(words.permMicrophone, words.permMicrophoneAbout, microphone,
                onAsk = if (callInSettings) onCallSettings else ({ onAskCall(false) }), onSettings = onCallSettings,
                askLabel = if (callInSettings) words.noticesOpenSettings else words.noticesAllow)
        }
        if (camera != null) {
            PermissionRow(words.permCamera, words.permCameraAbout, camera,
                onAsk = if (callInSettings) onCallSettings else ({ onAskCall(true) }), onSettings = onCallSettings,
                askLabel = if (callInSettings) words.noticesOpenSettings else words.noticesAllow)
        }

        // ── КОНТАКТЫ ─────────────────────────────────────────────────────────
        if (contacts != null) {
            PermissionRow(
                words.permContacts, words.permContactsAbout, contacts,
                onAsk = onAskContacts, onSettings = onAskContacts,
                askLabel = if (contactsInSettings) words.noticesOpenSettings else words.noticesAllow,
            )
        }

        // ── АВТОЗАГРУЗКА (ПК) ────────────────────────────────────────────────
        //
        // На ПК это то же, что «работа в фоне» на телефоне: не запущена TIMA — нет канала,
        // и звонок не дойдёт. Раньше выключатель жил только в меню значка в трее, где его
        // никто не искал (заказчик 2026-09-27).
        if (autostart != null) {
            SectionTitle(words.permAutostart)
            Column(Modifier.padding(horizontal = TimaSpacing.about4)) { Secondary(words.permAutostartAbout) }
            if (onAutostart != null) {
                ListLine(onClick = { onAutostart(!autostart) }, left = { CheckMark(autostart) }) {
                    Name(words.permAutostartOn)
                }
            } else {
                Column(Modifier.padding(horizontal = TimaSpacing.about4)) { Tertiary(words.permAutostartNoProgram) }
            }
        }

        // ── РАБОТА В ФОНЕ ────────────────────────────────────────────────────
        if (onBattery != null) {
            Title(words.permBackground, missing = !batteryFree)
            Column(
                Modifier.padding(horizontal = TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Secondary(words.noticesAwakeAbout)
                if (batteryFree) {
                    Granted(words.noticesAwakeDone)
                } else {
                    Button(label = words.noticesAwakeAsk, onClick = onBattery, kind = ButtonKind.Action)
                }
                // ── ЧЕСТНО ПРО ОБОЛОЧКИ ─────────────────────────────────────
                //
                // Системного белого списка на realme и Xiaomi НЕ ХВАТАЕТ: у них свои
                // списки автозапуска, и открыть их программно документированного способа
                // нет. Сказать это прямо дешевле, чем оставить человека гадать.
                Tertiary(words.noticesVendors)
            }
        }
    }
}

/**
 * Одно разрешение: название, зачем оно, и «Разрешено» либо кнопки.
 *
 * Кнопок две, когда не выдано: «Разрешить» поднимает системный вопрос, а если система
 * больше не спрашивает — вопрос молчит, и тогда нужна «Открыть настройки».
 */
@Composable
private fun PermissionRow(
    title: String,
    about: String,
    granted: Boolean,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    askLabel: String = Tima.words.settings2.noticesAllow,
) {
    val words = Tima.words.settings2
    Title(title, missing = !granted)
    Column(
        Modifier.padding(horizontal = TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Secondary(about)
        if (granted) {
            Granted(words.noticesAllowed)
        } else {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Button(label = askLabel, onClick = onAsk, kind = ButtonKind.Action)
                if (askLabel != words.noticesOpenSettings) {
                    Button(label = words.noticesOpenSettings, onClick = onSettings, kind = ButtonKind.Quiet)
                }
            }
        }
    }
    Rule()
}

/**
 * Полоса между пунктами «Разрешений» (заказчик 2026-09-26): пунктов семь, и без полос
 * текст одного сливался с заголовком следующего.
 */
@Composable
private fun Rule() {
    Box(
        Modifier.fillMaxWidth().padding(top = TimaSpacing.about2)
            .height(1.dp).background(Tima.colors.line),
    )
}

/**
 * Заголовок пункта «Разрешений»: **красный, если разрешение не дано** (заказчик
 * 2026-09-27). Всё разрешено — красного на экране нет вовсе, и это видно с первого
 * взгляда. Автозагрузка ПК сюда не входит: это выбор человека, а не отказ системы.
 */
@Composable
private fun Title(text: String, missing: Boolean) =
    SectionTitle(text, color = if (missing) Tima.colors.alarm else Tima.colors.text3)

/**
 * «Разрешено» — серым пузырём (заказчик 2026-09-27).
 *
 * До того он был зелёным, цвета шапки (2026-09-26), и выглядел как кнопка: зелёная
 * капсула в приложении — это действие. Серый говорит «так и есть, делать нечего», а
 * внимание забирает красный заголовок там, где разрешения нет.
 */
@Composable
private fun Granted(text: String) {
    val colors = Tima.colors
    Box(
        Modifier.background(colors.quiet, RoundedCornerShape(TimaSpacing.about4))
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    ) {
        Caption(text, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.text2)
    }
}

/**
 * Что произойдёт, если попросить право показывать уведомления.
 *
 * Свой набор, а не тип из `core-notify`: `feature-shell` про платформы не знает, и
 * зависимость экрана от платформенного модуля была бы зависимостью не по делу.
 */
enum class NotifyAccess { Given, Ask, Settings }

/**
 * Одна настройка звука: что выбрано сейчас и четыре пути — ВЗ4.
 *
 * @param current как назвать выбранное: имя мелодии или файла, «Как в системе», «Без звука».
 * @param picked какой из путей выбран — на нём точка в раскрытом выборе.
 * @param onSystem выбор из стандартных; `null` — у платформы их нет (ПК).
 * @param trouble почему последний файл не взят; `null` — всё хорошо.
 */
data class SoundRow(
    val current: String,
    val onSystem: (() -> Unit)?,
    val onFile: () -> Unit,
    val onSilent: () -> Unit,
    val onDefault: () -> Unit,
    val trouble: String? = null,
    val picked: SoundPicked = SoundPicked.Default,
    /** Что звучит: название мелодии или файла; у «Как в системе» — мелодия телефона. */
    val sound: String? = null,
)

/** Какой путь выбора звука сейчас в силе. */
enum class SoundPicked { Default, System, File, Silent }

/**
 * Настройка звука строкой списка (заказчик 2026-10-01): значок, название, что это; справа —
 * что выбрано и стрелка. Нажатие раскрывает четыре варианта строками; выбранный — с точкой.
 * «Из стандартных» и «Свой файл» открывают выбор мелодии; «Как в системе» и «Без звука»
 * ставятся сразу.
 */
@Composable
fun SoundSetting(glyph: String, title: String, about: String, row: SoundRow) {
    val words = Tima.words.settings2
    var open by remember(title) { mutableStateOf(false) }
    ListLine(
        onClick = { open = !open },
        left = { Name(glyph) },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Secondary(row.current, lineOne = true)
                Secondary(if (open) "⌃" else "›")
            }
        },
        middle = {
            Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, maxLines = 2)
            Tertiary(about)
            // Что звучит — названием (заказчик 2026-10-01).
            row.sound?.let { Secondary("♪ $it", lineOne = true) }
        },
    )
    if (open) {
        Column(Modifier.fillMaxWidth().background(Tima.colors.softAccent)) {
            SoundChoice(words.soundDefault, words.soundDefaultAbout, row.picked == SoundPicked.Default) {
                row.onDefault()
                open = false
            }
            row.onSystem?.let { pick ->
                SoundChoice(words.soundFromSystem, words.soundFromSystemAbout, row.picked == SoundPicked.System) {
                    pick()
                    open = false
                }
            }
            SoundChoice(words.soundFromFile, words.soundFromFileAbout, row.picked == SoundPicked.File) {
                row.onFile()
                open = false
            }
            SoundChoice(words.soundSilent, words.soundSilentAbout, row.picked == SoundPicked.Silent) {
                row.onSilent()
                open = false
            }
        }
    }
    row.trouble?.let {
        Column(Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)) { Secondary(it) }
    }
}

/** Вариант выбора звука: точка «одно из», название и пояснение — строкой с отступом. */
@Composable
private fun SoundChoice(label: String, about: String, on: Boolean, onClick: () -> Unit) {
    ListLine(
        modifier = Modifier.padding(start = TimaSpacing.about5),
        onClick = onClick,
        left = { RadioMark(on) },
        middle = {
            Caption(label, fontSize = TimaType.sz4, weight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
            Tertiary(about)
        },
    )
}

/**
 * «Не беспокоить, в часы:» (заказчик 2026-10-01).
 *
 * @param from начало, минуты от полуночи; [to] — конец.
 * @param calls глушить звонки: входящий без мелодии, пропущенные без строки.
 * @param messages глушить сообщения — личные и групповые.
 * @param onChange новое значение целиком: часы и галочки сохраняются вместе.
 */
data class QuietRow(
    val on: Boolean,
    val from: Int,
    val to: Int,
    val calls: Boolean = true,
    val messages: Boolean = true,
    val onChange: (QuietRow) -> Unit,
)

/**
 * Экономичный режим канала: включён ли и раз во сколько секунд перекличка (заказчик
 * 2026-10-06). Границы и шаг — у того, кто хранит настройку.
 */
data class EconomyRow(
    val on: Boolean,
    val seconds: Int,
    val min: Int,
    val max: Int,
    val step: Int,
    val onChange: (EconomyRow) -> Unit,
)

/** «12:30» из минут от полуночи. */
fun clockOf(minute: Int): String {
    val m = ((minute % 1440) + 1440) % 1440
    return (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
}

/**
 * Тихие часы строкой списка: справа — «выкл» или «23:00–08:00»; нажатие раскрывает
 * «Выключено / Включено» и, при включённом, «С» и «До» кнопками − и + по 30 минут.
 */
@Composable
private fun QuietSetting(row: QuietRow) {
    val words = Tima.words.settings2
    var open by remember { mutableStateOf(false) }
    ListLine(
        onClick = { open = !open },
        left = { Name("🌙") },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Secondary(if (row.on) clockOf(row.from) + "–" + clockOf(row.to) else words.quietOff, lineOne = true)
                Secondary(if (open) "⌃" else "›")
            }
        },
        middle = {
            Caption(words.quietTitle, fontSize = TimaType.sz4, weight = FontWeight.Bold, maxLines = 2)
            Tertiary(words.quietAbout)
        },
    )
    if (!open) return
    Column(Modifier.fillMaxWidth().background(Tima.colors.softAccent)) {
        SoundChoice(words.quietOff, words.quietOffAbout, !row.on) { row.onChange(row.copy(on = false)) }
        SoundChoice(words.quietOn, words.quietOnAbout, row.on) { row.onChange(row.copy(on = true)) }
        if (row.on) {
            QuietTime(words.quietFrom, row.from) { row.onChange(row.copy(from = it)) }
            QuietTime(words.quietTo, row.to) { row.onChange(row.copy(to = it)) }
            // Что глушить — галочками (заказчик 2026-10-01): квадрат — «сколько угодно».
            QuietCheck(words.quietCalls, words.quietCallsAbout, row.calls) { row.onChange(row.copy(calls = it)) }
            QuietCheck(words.quietMessages, words.quietMessagesAbout, row.messages) { row.onChange(row.copy(messages = it)) }
        }
    }
}

/** Экономичный режим — строкой с раскрытием, как тихие часы. */
@Composable
private fun EconomySetting(row: EconomyRow) {
    val words = Tima.words.settings2
    var open by remember { mutableStateOf(false) }
    ListLine(
        onClick = { open = !open },
        left = { Name("🔋") },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Secondary(if (row.on) words.economyEvery(row.seconds) else words.economyOff, lineOne = true)
                Secondary(if (open) "⌃" else "›")
            }
        },
        middle = {
            Caption(words.economyTitle, fontSize = TimaType.sz4, weight = FontWeight.Bold, maxLines = 2)
            Tertiary(words.economyAbout)
        },
    )
    if (!open) return
    Column(Modifier.fillMaxWidth().background(Tima.colors.softAccent)) {
        SoundChoice(words.economyOff, words.economyOffAbout, !row.on) { row.onChange(row.copy(on = false)) }
        SoundChoice(words.economyOn, words.economyOnAbout, row.on) { row.onChange(row.copy(on = true)) }
        if (row.on) {
            ListLine(
                modifier = Modifier.padding(start = TimaSpacing.about5),
                middle = { Caption(words.economyEvery(row.seconds), fontSize = TimaType.sz4, weight = FontWeight.Bold) },
                right = {
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                        io.tima.core.ui.IconButton(glyph = "−", onClick = { row.onChange(row.copy(seconds = (row.seconds - row.step).coerceAtLeast(row.min))) })
                        io.tima.core.ui.IconButton(glyph = "+", onClick = { row.onChange(row.copy(seconds = (row.seconds + row.step).coerceAtMost(row.max))) })
                    }
                },
            )
        }
    }
}

/** Галочка «Звонки» / «Сообщения» тихих часов. */
@Composable
private fun QuietCheck(label: String, about: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListLine(
        modifier = Modifier.padding(start = TimaSpacing.about5),
        onClick = { onChange(!on) },
        left = { io.tima.core.ui.CheckMark(on) },
        middle = {
            Caption(label, fontSize = TimaType.sz4, weight = if (on) FontWeight.Bold else FontWeight.Normal)
            Tertiary(about)
        },
    )
}

/** «С 23:00» и кнопки − / + по 30 минут, через полночь по кругу. */
@Composable
private fun QuietTime(label: String, minute: Int, onChange: (Int) -> Unit) {
    ListLine(
        modifier = Modifier.padding(start = TimaSpacing.about5),
        middle = { Caption(label + " " + clockOf(minute), fontSize = TimaType.sz4, weight = FontWeight.Bold) },
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                io.tima.core.ui.IconButton(glyph = "−", onClick = { onChange((minute - STEP + 1440) % 1440) })
                io.tima.core.ui.IconButton(glyph = "+", onClick = { onChange((minute + STEP) % 1440) })
            }
        },
    )
}

/** Шаг часов — полчаса: точнее для «не беспокоить» не нужно. */
private const val STEP = 30
