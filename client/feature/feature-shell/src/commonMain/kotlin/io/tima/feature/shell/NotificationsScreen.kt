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
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words

/**
 * Настройки уведомлений — ПЛАН-УВЕДОМЛЕНИЙ.md, У1 и У14.
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
) {
    val colors = Tima.colors
    val words = Tima.words.settings2

    Column(
        modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState())
            .padding(bottom = TimaSpacing.about5),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        SectionTitle(words.noticesShow)
        Column(Modifier.padding(horizontal = TimaSpacing.about4)) {
            Secondary(words.noticesWhat)
            // Сказано до нажатия, а не после: текст сообщения не показывается никогда, и
            // человек вправе знать это, не выясняя опытом.
            Tertiary(words.noticesNoText)
        }

        // ── ЗВУКИ (ВЗ4) ─────────────────────────────────────────────────────
        //
        // Общие мелодия звонка и звук сообщения. Своя мелодия у контакта — в журнале
        // контактов (ВЗ8), и она важнее общей.
        if (ring != null || message != null) {
            SectionTitle(words.soundsTitle)
            ring?.let { SoundSetting(words.soundRing, it) }
            message?.let { SoundSetting(words.soundMessage, it) }
            Column(Modifier.padding(horizontal = TimaSpacing.about4)) { Tertiary(words.soundsNotSynced) }
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
    contacts: Boolean? = null,
    /** Система больше не спросит про контакты — кнопка ведёт в настройки. */
    contactsInSettings: Boolean = false,
    onAskContacts: () -> Unit = {},
) {
    val colors = Tima.colors
    val words = Tima.words.settings2

    Column(
        modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState())
            .padding(bottom = TimaSpacing.about5),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        // ── УВЕДОМЛЕНИЯ ─────────────────────────────────────────────────────
        SectionTitle(words.itemNotifications)
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
            SectionTitle(words.noticesCalls)
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
                onAsk = { onAskCall(false) }, onSettings = onCallSettings)
        }
        if (camera != null) {
            PermissionRow(words.permCamera, words.permCameraAbout, camera,
                onAsk = { onAskCall(true) }, onSettings = onCallSettings)
        }

        // ── КОНТАКТЫ ─────────────────────────────────────────────────────────
        if (contacts != null) {
            PermissionRow(
                words.permContacts, words.permContactsAbout, contacts,
                onAsk = onAskContacts, onSettings = onAskContacts,
                askLabel = if (contactsInSettings) words.noticesOpenSettings else words.noticesAllow,
            )
        }

        // ── РАБОТА В ФОНЕ ────────────────────────────────────────────────────
        if (onBattery != null) {
            SectionTitle(words.permBackground)
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
    SectionTitle(title)
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
 * «Разрешено» — пузырём цвета шапки, зелёным (заказчик 2026-09-26): выданное видно с
 * первого взгляда, а не читается среди строк пояснений.
 */
@Composable
private fun Granted(text: String) {
    val colors = Tima.colors
    Box(
        Modifier.background(colors.navigation, RoundedCornerShape(TimaSpacing.about4))
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    ) {
        Caption(text, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.onAccent)
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
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SoundSetting(title: String, row: SoundRow) {
    val words = Tima.words.settings2
    Column(
        Modifier.padding(horizontal = TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Secondary(title)
        Name(row.current)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            row.onSystem?.let { Button(label = words.soundFromSystem, onClick = it, kind = ButtonKind.Quiet) }
            Button(label = words.soundFromFile, onClick = row.onFile, kind = ButtonKind.Quiet)
            Button(label = words.soundSilent, onClick = row.onSilent, kind = ButtonKind.Quiet)
            Button(label = words.soundDefault, onClick = row.onDefault, kind = ButtonKind.Quiet)
        }
        row.trouble?.let { Secondary(it) }
    }
}
