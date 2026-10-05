package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words

/**
 * Заверить устройство по отсканированному коду (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ Р32).
 *
 * Скан сам по себе не решение — как и в привязке по QR: код могли прислать в переписке.
 * Поэтому перед заверением — вопрос, и в нём сказано, что это значит.
 */
@Composable
fun CertifyScreen(
    busy: Boolean,
    notice: String?,
    onCertify: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier.fillMaxSize().background(Tima.colors.surface)) {
    val words = Tima.words.auth
    SubwindowHeader(title = words.certifyTitle, onBack = onCancel)
    Column(
        Modifier.fillMaxWidth().padding(TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Secondary(words.certifyAsk)
        Button(label = if (busy) "…" else words.certifyYes, onClick = { if (!busy) onCertify() }, modifier = Modifier.fillMaxWidth())
        Button(label = words.keep, onClick = onCancel, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        notice?.let { Secondary(it) }
    }
}
