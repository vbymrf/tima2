package io.tima.feature.group

import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.ListLine
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ContentItem
import io.tima.domain.chat.ContentSection

/** Метки «Содержимого» — для сценариев. */
object ContentTags {
    const val SECTION = "content:section"
    const val ROW = "content:row"
    const val SHOW = "content:show"
}

/**
 * Вкладка «Содержимое» (ПЛАН-(СЦ) СЦ20, заказчик 2026-11-23): подразделы — фото, видео, музыка, голосовые, файлы,
 * ссылки; только то, что есть на этом устройстве. Первое открытие собирает под «Собирается…», дальше — только новое.
 * Нажатие — открыть (фото и файл — тем же показом, что в переписке; ссылка — по адресу); «Показать в переписке» —
 * сообщение на своём месте.
 */
@Composable
fun EntityContentScreen(
    title: String,
    tabs: @Composable () -> Unit,
    items: List<ContentItem>?,
    collecting: Boolean,
    section: ContentSection,
    onSection: (ContentSection) -> Unit,
    onOpen: (ContentItem) -> Unit,
    onShow: (ContentItem) -> Unit,
    time: (Long) -> String,
    onBack: () -> Unit,
    /** Как показать фото, видео, файл — тем же пузырём, что в переписке. */
    preview: @Composable (ContentItem) -> Unit,
) {
    // «Списки» «Шрифтов и размеров» (заказчик 2026-11-23); шапка и вкладки ставят своё.
    ProvidePlace(TextPlace.LISTS) {
    val w = Tima.words.socium
    fun label(s: ContentSection) = when (s) {
        ContentSection.Photo -> w.contentPhotos
        ContentSection.Video -> w.contentVideo
        ContentSection.Music -> w.contentMusic
        ContentSection.Voice -> w.contentVoice
        ContentSection.File -> w.contentFiles
        ContentSection.Link -> w.contentLinks
    }
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = title, onBack = onBack, caption = w.tabContent, avatar = title.take(2).uppercase())
        tabs()
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in ContentSection.entries) {
                val n = items?.count { it.section == s } ?: 0
                Chip(
                    if (n > 0) label(s) + " " + n else label(s),
                    kind = if (s == section) ChipKind.Selected else ChipKind.Neutral,
                    onClick = { onSection(s) },
                    modifier = Modifier.testTag(ContentTags.SECTION),
                )
            }
        }
        if (collecting) Secondary(w.contentCollecting, Modifier.padding(horizontal = TimaSpacing.about4))
        Tertiary(w.contentFromPhone, Modifier.padding(horizontal = TimaSpacing.about4))
        val list = items.orEmpty().filter { it.section == section }
        if (items != null && list.isEmpty() && !collecting) {
            Tertiary(w.contentEmpty, Modifier.padding(TimaSpacing.about5))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // Фото и видео — сеткой миниатюр по три (живьём на realme, 274: во всю ширину одно фото занимало экран).
            if (section == ContentSection.Photo || section == ContentSection.Video) {
                val rows = list.chunked(3)
                items(rows.size, key = { "grid:" + rows[it].first().messageId + ":" + rows[it].first().index }) { r ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about3, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (it in rows[r]) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.weight(1f).aspectRatio(1f)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .clickable { onOpen(it) }
                                    .testTag(ContentTags.ROW),
                            ) { preview(it) }
                        }
                        repeat(3 - rows[r].size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                    }
                }
                return@LazyColumn
            }
            items(list.size, key = { list[it].messageId.toString() + ":" + list[it].index }) { i ->
                val it = list[i]
                Column(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)) {
                    if (it.section == ContentSection.Link) {
                        ListLine(
                            modifier = Modifier.testTag(ContentTags.ROW),
                            onClick = { onOpen(it) },
                            middle = {
                                if (it.title.isNotBlank()) Caption(it.title, fontSize = TimaType.sz5, maxLines = 2)
                                Tertiary(it.url, lineOne = true)
                            },
                            right = { Tertiary(time(it.atMs)) },
                        )
                    } else {
                        Column(Modifier.testTag(ContentTags.ROW)) { preview(it) }
                    }
                    Button(w.showInChat, onClick = { onShow(it) }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(ContentTags.SHOW))
                }
            }
        }
    }
    }
}
