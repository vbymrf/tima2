package io.tima.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Alarm
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble
import io.tima.core.ui.words
import io.tima.core.words.CurrentWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// «Предложить изменения» (заказчик 2026-10-07): сообщение и фото, как «Сообщить о проблеме», но без
// журнала, без снимка состояния и без «О чём» — предложение не поломка. Версию, модель, систему и
// ник прикладывает отправляющий из [ProblemFacts]; сервер отличает предложение видом `suggestion`.

/** Кто отправляет. Реализация — в приложении: сеть и очередь те же, что у отчёта о проблеме. */
fun interface SuggestSender {
    suspend fun send(text: String, photos: List<ProblemPhoto>): SendOutcome
}

/** Что видно на экране «Предложить изменения». */
data class SuggestState(
    val text: String = "",
    val photos: List<ProblemPhoto> = emptyList(),
    /** Выбранное не приложилось: не картинка. Снимается следующим выбором. */
    val photoRejected: Boolean = false,
    val sending: Boolean = false,
    val outcome: SendOutcome? = null,
) {
    /** Принято сервером или очередью — повторять нечего, до повторного входа на экран. */
    val delivered: Boolean get() = outcome is SendOutcome.Sent || outcome == SendOutcome.Queued

    val canSend: Boolean get() = text.isNotBlank() && !sending && !delivered

    val morePhotos: Boolean get() = photos.size < MAX_PHOTOS && !delivered

    /** Почему не отправить; `null` — всё на месте. */
    val missing: String? get() = if (!delivered && text.isBlank()) CurrentWords.value.suggest.writeSomething else null
}

/** Состояние и решения экрана — то же устройство, что [ProblemStore], без журнала. */
class SuggestStore(
    private val sender: SuggestSender,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SuggestState())
    val state: StateFlow<SuggestState> = _state.asStateFlow()

    fun changedText(line: String) {
        _state.value = _state.value.copy(text = line)
    }

    /** `null` — выбранное не картинка: говорим об этом, а не молчим. */
    fun addPhoto(photo: ProblemPhoto?) {
        val state = _state.value
        if (!state.morePhotos) return
        _state.value = if (photo == null) state.copy(photoRejected = true) else state.copy(photos = state.photos + photo, photoRejected = false)
    }

    fun removePhoto(index: Int) {
        val state = _state.value
        if (state.delivered || index !in state.photos.indices) return
        _state.value = state.copy(photos = state.photos.filterIndexed { i, _ -> i != index })
    }

    fun send() {
        val state = _state.value
        if (!state.canSend) return
        scope.launch {
            _state.value = state.copy(sending = true, outcome = null)
            val outcome = try {
                sender.send(state.text.trim(), state.photos)
            } catch (e: Throwable) {
                SendOutcome.Refused(CurrentWords.value.problem.couldNotSend)
            }
            _state.value = _state.value.copy(sending = false, outcome = outcome)
        }
    }
}

/**
 * Экран «Предложить изменения». Порядок — как у «Сообщить о проблеме»: исход, помеха и кнопка
 * сверху, ниже текст и фото, внизу — что уйдёт.
 */
@Composable
fun SuggestScreen(
    state: SuggestState,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    /** «Приложить фото»; `null` — платформа выбирать не умеет. */
    onAddPhoto: (() -> Unit)? = null,
    onRemovePhoto: (Int) -> Unit = {},
    photoPreview: @Composable (ProblemPhoto) -> Unit = {},
) = Column(
    modifier.fillMaxSize().padding(TimaSpacing.about4).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    val words = Tima.words.suggest
    val problem = Tima.words.problem
    when (val outcome = state.outcome) {
        is SendOutcome.Sent -> {
            Secondary(words.number)
            Caption(outcome.number, fontSize = TimaType.sz1, weight = FontWeight.ExtraBold)
        }
        SendOutcome.Queued -> {
            Caption(problem.willSendWhenOnline, fontSize = TimaType.sz4, weight = FontWeight.Bold)
            Secondary(words.queuedAbout)
        }
        is SendOutcome.Refused -> Trouble(outcome.why)
        null -> Unit
    }
    state.missing?.let { Alarm(it) }
    Button(
        label = when {
            state.delivered -> words.sent
            state.sending -> problem.sending
            else -> problem.send
        },
        onClick = onSend,
        kind = if (state.delivered) ButtonKind.Done else ButtonKind.Action,
        enabled = state.canSend,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.delivered) Secondary(words.writeAgainHow)

    Caption(words.whatToChange, fontSize = TimaType.sz4, weight = FontWeight.Bold)
    Field(value = state.text, onChange = onText, hint = words.hint)

    if (state.photos.isNotEmpty() || onAddPhoto != null) {
        Caption(problem.photos, fontSize = TimaType.sz5, weight = FontWeight.Bold)
        Secondary(words.photosAbout)
        state.photos.forEachIndexed { index, photo ->
            photoPreview(photo)
            if (!state.delivered) {
                Button(label = problem.removePhoto, onClick = { onRemovePhoto(index) }, kind = ButtonKind.Quiet)
            }
        }
        if (state.photoRejected) Alarm(problem.photoNotImage)
        if (onAddPhoto != null && state.morePhotos) {
            Button(label = problem.addPhoto, onClick = onAddPhoto, kind = ButtonKind.Quiet)
        }
    }

    Caption(words.whatGoes, fontSize = TimaType.sz5, weight = FontWeight.Bold)
    Secondary(words.whatGoesAbout)
    if (state.photos.isNotEmpty()) Tertiary(problem.photosGo(state.photos.size))
}
