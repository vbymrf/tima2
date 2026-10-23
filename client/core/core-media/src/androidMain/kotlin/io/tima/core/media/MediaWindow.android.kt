package io.tima.core.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Телефон: окно во весь экран; приложение поворачивается само — видео ложится по горизонтали. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Окно — своё, вне корня `MainActivity`: метки `player:*` для сценариев включаются здесь заново,
        // иначе в дереве экрана их нет (2026-10-23).
        Box(Modifier.semantics { testTagsAsResourceId = true }) { content() }
    }
}

/**
 * Полоски прячутся у окна диалога (у него свои), поворот — у самого приложения: диалог поворачивается
 * вместе с ним. Прежний поворот приложения запоминается и возвращается при уходе.
 */
@Composable
actual fun FullScreenMode(on: Boolean, turn: ScreenTurn, onUpright: () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val before = remember(activity) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val upright by rememberUpdatedState(onUpright)
    DisposableEffect(view, on) {
        val window = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (on) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(activity, turn) {
        activity?.requestedOrientation = when (turn) {
            ScreenTurn.FREE -> before
            ScreenTurn.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenTurn.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // После выхода из полного экрана держим вертикально, пока телефон не поставят вертикально, —
        // иначе лежащий боком телефон тут же развернул бы окно обратно (как у YouTube).
        val sensor = if (turn != ScreenTurn.PORTRAIT || activity == null) null else object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != ORIENTATION_UNKNOWN && (degrees < 25 || degrees > 335)) upright()
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
        onDispose {
            sensor?.disable()
            activity?.requestedOrientation = before
        }
    }
}

private fun Context.activity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

/** Флажок вида держит экран у окна, к которому вид приложен, — внутри `MediaWindow` это окно диалога. */
@Composable
actual fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

actual val turnsWithPhone: Boolean = true
