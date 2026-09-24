package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
    /** Право показывать: дано ли, и если нет — что случится по нажатию. */
    access: NotifyAccess,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Просить не усыплять. `null` — платформе это не нужно (ПК): строки не будет вовсе,
     * а не «неактивная» — неактивная тоже зовёт нажать.
     */
    onBattery: (() -> Unit)? = null,
    /** Уже не усыпляют. */
    batteryFree: Boolean = false,
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
        when (access) {
            NotifyAccess.Given -> Column(Modifier.padding(horizontal = TimaSpacing.about4)) {
                Name(words.noticesAllowed)
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

        if (onBattery != null) {
            SectionTitle(words.noticesAwake)
            Column(
                Modifier.padding(horizontal = TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Secondary(words.noticesAwakeAbout)
                if (batteryFree) {
                    Name(words.noticesAwakeDone)
                } else {
                    Button(label = words.noticesAwakeAsk, onClick = onBattery, kind = ButtonKind.Action)
                }
                // ── ЧЕСТНО ПРО ОБОЛОЧКИ ─────────────────────────────────────
                //
                // Системного белого списка на realme и Xiaomi НЕ ХВАТАЕТ: у них свои
                // списки автозапуска, и открыть их программно документированного способа
                // нет. Сказать это прямо дешевле, чем оставить человека гадать, почему
                // уведомления приходят через раз.
                Tertiary(words.noticesVendors)
            }
        }
    }
}

/**
 * Что произойдёт, если попросить право показывать уведомления.
 *
 * Свой набор, а не тип из `core-notify`: `feature-shell` про платформы не знает, и
 * зависимость экрана от платформенного модуля была бы зависимостью не по делу.
 */
enum class NotifyAccess { Given, Ask, Settings }
