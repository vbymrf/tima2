package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words
import io.tima.domain.chat.ChannelFeedPost

/** Метки ленты канала (ПЛАН-(СЦ) СЦ3). */
object ChannelFeedTags {
    const val MENU = "channel:menu"
    const val POST = "channel:post"
    const val COMMENTS = "channel:comments"
    const val ABOUT = "channel:about"
    const val INPUT = "channel:input"
    const val SEND = "channel:send"
    const val MORE = "channel:more"
    const val PHOTO = "channel:photo"
    const val FILE = "channel:file"
    const val EDITOR = "channel:editor"
}

/**
 * Лента канала (ПЛАН-(СЦ) СЦ3: «переход — на минимальный экран канала: лента постов, подписка, комментарии — К5»).
 * Записи новые сверху; под каждой — «Комментарии» в окно обсуждения записи. Подписка и прочее — на странице канала
 * («О канале»). Владелец и модератор пишут запись полем внизу ([onPublish] не `null`). `posts == null` — сервер не
 * ответил или канал закрыт для не подписанного.
 */
@Composable
fun ChannelFeedScreen(
    title: String,
    posts: List<ChannelFeedPost>?,
    time: (Long) -> String,
    onComments: (ChannelFeedPost) -> Unit,
    onAbout: () -> Unit,
    onPublish: ((String) -> Unit)?,
    onBack: () -> Unit,
    avatarPicture: String? = null,
    /** «⋮» канала — разделы и уведомления, как у группы (ПЛАН-(ВС) 16); `null` — кнопки нет. */
    onMenu: (() -> Unit)? = null,
    /** Запись оформленной — фото, файлы, блоки (СЦ22); `null` — текстом. */
    content: (@Composable (ChannelFeedPost) -> Unit)? = null,
    /** «Ещё» — записи старше показанных (СЦ22); `null` — кнопки нет. */
    onMore: (() -> Unit)? = null,
    /** Вложения записи, как в переписке (СЦ22, СЦ17); `null` — кнопки нет. */
    onPhotos: (() -> Unit)? = null,
    onFile: (() -> Unit)? = null,
    onEditor: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(
            title = title,
            onBack = onBack,
            caption = w.channelFeed,
            avatar = title.take(2).uppercase(),
            avatarPicture = avatarPicture,
            right = {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(w.channelAbout, onClick = onAbout, kind = ButtonKind.Quiet, modifier = Modifier.testTag(ChannelFeedTags.ABOUT))
                    onMenu?.let { io.tima.core.ui.IconButton(glyph = "⋮", onClick = it, modifier = Modifier.testTag(ChannelFeedTags.MENU)) }
                }
            },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when {
                posts == null -> item { Secondary(Tima.words.trouble.offline, Modifier.padding(TimaSpacing.about5)) }
                posts.isEmpty() -> item { Tertiary(w.channelEmpty, Modifier.padding(TimaSpacing.about5)) }
                else -> items(posts, key = { it.postId }) { p ->
                    ListLine(
                        modifier = Modifier.testTag(ChannelFeedTags.POST),
                        middle = {
                            Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                                if (content != null) content(p) else Name(p.text)
                                Tertiary(time(p.atMs))
                                if (!p.commentsClosed || p.comments > 0) {
                                    Button(
                                        w.channelComments(p.comments),
                                        onClick = { onComments(p) },
                                        kind = ButtonKind.Quiet,
                                        modifier = Modifier.testTag(ChannelFeedTags.COMMENTS),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (onMore != null && !posts.isNullOrEmpty()) {
                item(key = "more") {
                    Button(w.channelMore, onClick = onMore, kind = ButtonKind.Quiet, modifier = Modifier.padding(TimaSpacing.about4).testTag(ChannelFeedTags.MORE))
                }
            }
        }
        if (busy) Tertiary(w.threadSending, Modifier.padding(horizontal = TimaSpacing.about4))
        if (onPublish != null && (onPhotos != null || onFile != null || onEditor != null)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about3), horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                onPhotos?.let { io.tima.core.ui.Chip(w.threadPhoto, onClick = it, modifier = Modifier.testTag(ChannelFeedTags.PHOTO)) }
                onFile?.let { io.tima.core.ui.Chip(w.threadFile, onClick = it, modifier = Modifier.testTag(ChannelFeedTags.FILE)) }
                onEditor?.let { io.tima.core.ui.Chip(w.threadEditor, onClick = it, modifier = Modifier.testTag(ChannelFeedTags.EDITOR)) }
            }
        }
        if (onPublish != null) {
            var text by remember { mutableStateOf("") }
            Row(
                Modifier.fillMaxWidth().padding(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Field(value = text, onChange = { text = it }, hint = w.channelPostHint, modifier = Modifier.weight(1f).testTag(ChannelFeedTags.INPUT))
                Button(w.channelPublish, onClick = { if (text.isNotBlank()) { onPublish(text.trim()); text = "" } }, modifier = Modifier.testTag(ChannelFeedTags.SEND))
            }
        }
    }
}
