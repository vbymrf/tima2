package io.tima.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.encryption.TextBodyCodec
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.HelpFiles
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Где человек в «Частых вопросах» (заказчик 2026-10-24): раздел → вкладка → страница. «Назад» настроек
 * сначала шагает здесь: со страницы — к списку вкладки, из раздела — к разделам.
 */
internal class HelpNav {
    var section by mutableStateOf<String?>(null)
    var tab by mutableStateOf<String?>(null)
    var page by mutableStateOf<HelpFiles.Page?>(null)

    /** Шаг назад внутри справки; шагнули — `true`, иначе «назад» уводит из настроек. */
    fun back(): Boolean = when {
        page != null -> {
            page = null
            true
        }
        section != null -> {
            section = null
            tab = null
            true
        }
        else -> false
    }
}

/** Метки справки для сценариев. */
internal object HelpTags {
    const val SECTIONS = "help:sections"
    const val TABS = "help:tabs"
    const val PAGES = "help:pages"
    const val PAGE = "help:page"
    const val BACK = "help:back"
    fun section(key: String) = "help:section.$key"
    fun tab(key: String) = "help:tab.$key"
    fun page(i: Int) = "help:page.$i"
}

/**
 * «Частые вопросы»: основной пункт — разделы; раздел — вкладки вторым рядом и страницы строками; страница —
 * «‹ Назад», название, текст тем же видом, что сообщение (наш быстрый markdown), и снимки экрана.
 */
@Composable
internal fun Questions(nav: HelpNav) {
    ProvidePlace(TextPlace.MENU) {
        val page = nav.page
        val section = nav.section
        when {
            page != null -> HelpPageView(page) { nav.page = null }
            section != null -> HelpSection(nav, section)
            else -> HelpSections(nav)
        }
    }
}

@Composable
private fun HelpSections(nav: HelpNav) {
    val sections by produceState(emptyList<Pair<String, String>>()) { value = HelpFiles.text("sections.txt")?.let(HelpFiles::list).orEmpty() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(HelpTags.SECTIONS)) {
        for ((key, title) in sections) {
            ListLine(
                modifier = Modifier.testTag(HelpTags.section(key)),
                onClick = { nav.section = key },
                middle = { Name(title) },
                right = { Tertiary("›", lineOne = true) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpSection(nav: HelpNav, section: String) {
    val tabs by produceState(emptyList<Pair<String, String>>(), section) { value = HelpFiles.text("$section/tabs.txt")?.let(HelpFiles::list).orEmpty() }
    val tab = nav.tab ?: tabs.firstOrNull()?.first
    val pages by produceState(emptyList<HelpFiles.Page>(), section, tab) {
        value = tab?.let { HelpFiles.text("$section/$it.md") }?.let(HelpFiles::pages).orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        // Вкладки — вторым рядом, как в окнах.
        ProvidePlace(TextPlace.TABS) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2).testTag(HelpTags.TABS),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((key, title) in tabs) {
                    Chip(title, kind = if (key == tab) ChipKind.Selected else ChipKind.Neutral, onClick = { nav.tab = key },
                        modifier = Modifier.testTag(HelpTags.tab(key)))
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(HelpTags.PAGES)) {
            pages.forEachIndexed { i, page ->
                ListLine(
                    modifier = Modifier.testTag(HelpTags.page(i)),
                    onClick = { nav.page = page },
                    middle = { Name(page.title) },
                    right = { Tertiary("›", lineOne = true) },
                )
            }
        }
    }
}

@Composable
private fun HelpPageView(page: HelpFiles.Page, onBack: () -> Unit) {
    val dark = Tima.colors.surface.luminance() < 0.5f
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag(HelpTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Chip("‹ " + Tima.words.common.back, kind = ChipKind.Quiet, onClick = onBack, modifier = Modifier.testTag(HelpTags.BACK))
        ProvidePlace(TextPlace.HEADERS) { Caption(page.title, fontSize = TimaType.sz3, weight = androidx.compose.ui.text.font.FontWeight.ExtraBold) }
        // Текст — тем же разбором и видом, что сообщение: справка заодно показывает, как выглядит оформление.
        ProvidePlace(TextPlace.MESSAGES) {
            for (part in page.parts) {
                when (part) {
                    is HelpFiles.Part.Text -> {
                        val rich = remember(part.markdown) { TextBodyCodec.decodeLine(TextBodyCodec.encodeTyped(part.markdown))?.rich }
                        if (rich != null) io.tima.feature.chat.RichBody(rich, Modifier.fillMaxWidth()) else Caption(part.markdown, fontSize = TimaType.sz4)
                    }
                    is HelpFiles.Part.Shot -> HelpShot(part.name, dark)
                }
            }
        }
    }
}

/** Снимок экрана справки — своей темы; нет — ничего. */
@Composable
private fun HelpShot(name: String, dark: Boolean) {
    var image by remember(name, dark) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(name, dark) {
        image = HelpFiles.bytes("img/$name-${if (dark) "dark" else "light"}.png")?.let { io.tima.core.media.decodeImage(it) }
    }
    image?.let {
        Image(
            it, contentDescription = null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().border(1.dp, Tima.colors.line, RoundedCornerShape(8.dp)),
        )
    }
}
