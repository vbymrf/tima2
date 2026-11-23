package io.tima.feature.group

import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ThreadState

/** Метки подокна «Уведомления» — для сценариев. */
object NotifyTags {
    const val ENTITY_ON = "notify:entity:on"
    const val ENTITY_OFF = "notify:entity:off"
    const val THREAD_ON = "notify:thread:on"
    const val THREAD_OFF = "notify:thread:off"
    const val THREAD_BLOCKED = "notify:thread:blocked"
}

/**
 * Подокно «Уведомления» страницы сущности (ПЛАН-(СЦ) СЦ16, §1.10, 1): два подписанных раздела, чтобы «выключено»
 * одного не путалось с другим. «Новое в сущности» — уведомления о новом в группе, канале, сообществе (только тому, кто
 * внутри); «Сообщения от сущности мне» — состояние переписки «сущность — человек» (§1.9, 10): включены, без
 * уведомления, заблокированы. Ленты ещё нет — состояние ставится заранее: сервер заводит ленту сам.
 */
@Composable
fun NotifySettingsScreen(
    title: String,
    /** Уведомления о новом; `null` — не участник и не подписчик: раздел с подсказкой. */
    entityOn: Boolean?,
    onEntity: (Boolean) -> Unit,
    /** Состояние переписки; `null` — ещё не пришло или нет связи. */
    threadState: ThreadState?,
    onThread: (ThreadState) -> Unit,
    /** Переписка — с сообществом, куда входит сущность (§1.9, 4). */
    threadOfCommunity: Boolean,
    onBack: () -> Unit,
    /** Названия разделов видом сущности (§1.14, 3): «Уведомления в личной группе», «Сообщения от личной группы мне». */
    entitySection: String = Tima.words.socium.notifyEntitySection,
    threadSection: String = Tima.words.socium.notifyThreadSection,
    /** «Показывать уровень сообщений» (§1.14, 2) — у участника группы; `null` — строки нет. */
    showLevels: Boolean? = null,
    onShowLevels: (Boolean) -> Unit = {},
) {
    // «Меню и настройки» «Шрифтов и размеров» (заказчик 2026-11-23); шапка и вкладки ставят своё.
    ProvidePlace(TextPlace.MENU) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.settingsTitle, onBack = onBack, caption = title, avatar = title.take(2).uppercase())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TimaSpacing.about5),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            Caption(entitySection, fontSize = TimaType.sz4, weight = FontWeight.Bold)
            if (entityOn == null) {
                Tertiary(w.notifyEntityNeedsJoin)
            } else {
                Choice(w.wordOn, entityOn, NotifyTags.ENTITY_ON) { onEntity(true) }
                Choice(w.wordOff, !entityOn, NotifyTags.ENTITY_OFF) { onEntity(false) }
            }
            Caption(threadSection, Modifier.padding(top = TimaSpacing.about4), fontSize = TimaType.sz4, weight = FontWeight.Bold)
            if (threadOfCommunity) Tertiary(w.notifyThreadOfCommunity)
            if (threadState == null) {
                Secondary("…")
            } else {
                Choice(w.wordOn, threadState == ThreadState.On, NotifyTags.THREAD_ON) { onThread(ThreadState.On) }
                Choice(w.wordSilent, threadState == ThreadState.Off, NotifyTags.THREAD_OFF) { onThread(ThreadState.Off) }
                Choice(w.wordBlocked, threadState == ThreadState.Blocked, NotifyTags.THREAD_BLOCKED) { onThread(ThreadState.Blocked) }
                Tertiary(w.notifyThreadHint)
            }
            if (showLevels != null) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = TimaSpacing.about3).height(1.dp).background(Tima.colors.line))
                Choice(w.showLevels, showLevels, PageTags.LEVELS) { onShowLevels(!showLevels) }
            }
        }
    }
    }
}

/** Строка выбора: отметка и слово во всю ширину — нажимается целиком. */
@Composable
private fun Choice(text: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = TimaSpacing.about2).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        CheckMark(chosen)
        Caption(text, fontSize = TimaType.sz4, weight = if (chosen) FontWeight.Bold else FontWeight.Normal)
    }
}
