package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words

/**
 * «Настройки → Звонок · Вид» (ПЛАН-(ЗН) ЗН1, заказчик 2026-11-10): вид окна звонка — новый (по
 * умолчанию) или прежний — и режим адаптации изображения. Действует со следующего звонка.
 */
@Composable
fun CallLookScreen(
    fresh: Boolean,
    onFresh: (Boolean) -> Unit,
    adapt: Boolean,
    onAdapt: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = Tima.words.call
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Tima.colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(TimaSpacing.about3)
            .testTag(CALL_LOOK_TAG),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Pick(words.lookTitle, listOf(true to words.lookNew, false to words.lookOld), fresh, onFresh)
        Secondary(words.lookAbout)
        Pick(words.adaptTitle, listOf(true to words.codingOn, false to words.codingOff), adapt, onAdapt)
        Secondary(words.adaptAbout)
    }
}

const val CALL_LOOK_TAG: String = "settings:call-look"
