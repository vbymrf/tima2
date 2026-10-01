package io.tima.shared

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.tima.core.call.CallEngine
import io.tima.feature.shell.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Звонок, стенд и открытое окно — **процессу, а не окну Android** (заказчик 2026-09-30, 1а).
 *
 * ── ЗАЧЕМ ───────────────────────────────────────────────────────────────────
 *
 * Android пересоздаёт главное окно, когда хочет: Redmi 2026-09-30 — четыре раза за вечер,
 * всем приложениям разом (смена путей к ресурсам, `Config changes=80000000`), и отказаться
 * от этого в манифесте нельзя. Пока ведущий звонка ([CallHost]) и стенд ([BenchStore]) жили в
 * композиции окна, пересоздание уносило их с собой: окно звонка исчезало, а комната LiveKit с
 * камерой оставалась открытой без хозяина — собеседник видел нас ещё 34 секунды. Разбор —
 * `doc_mig/БЕДЫ/2026-09-30-окно-пересоздаёт-система.md`.
 *
 * Теперь они живут здесь, по одному на устройство, как канал у [ChannelHost]. Новое окно берёт
 * тех же и только перерисовывает: звонок идёт, окно 0 или 6 на месте. На ПК то же спасает
 * звонок при закрытии окна в трей: композиция окна разбирается, а звонок — нет.
 *
 * ── ЧТО НЕ ЗДЕСЬ ────────────────────────────────────────────────────────────
 *
 * Подокна (переписка, настройки, книга) — по-прежнему окну: пересоздание вернёт на само окно,
 * но не внутрь переписки. Они ничего не делают сами, и потерять их — неудобство, а не беда.
 */
object CallKeep {

    /**
     * Где живут звонок и стенд. Ставит платформа до первого окна: Android — главный поток (там,
     * где раньше жила область окна), ПК — поток окна. По умолчанию — фоновый, для проверок и
     * платформ, которые звонить не умеют.
     */
    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Сборка звонка устройства: движок, для которого собрана, стенд и ведущий. */
    class Kept(val engine: CallEngine?, val bench: BenchStore, val host: CallHost)

    private val kept = mutableMapOf<String, Kept>()
    private val windows = mutableMapOf<String, MutableState<Window>>()

    /**
     * Звонок устройства [deviceId] — тот же при каждом окне.
     *
     * Собирается заново, только если сменился движок: на Android и ПК он один на процесс, и
     * это случается лишь в проверках. Прежний ведущий тогда кладёт трубку — звонка без хозяина
     * не остаётся.
     */
    fun kept(deviceId: String, engine: CallEngine?, build: (CoroutineScope) -> Kept): Kept {
        val had = kept[deviceId]
        if (had != null && had.engine === engine) return had
        had?.host?.hangUp()
        return build(scope).also { kept[deviceId] = it }
    }

    /**
     * Какое окно открыто у устройства [deviceId]. Процесс начинается с окна 1 — личная связь;
     * пересозданное окно Android показывает то, что было открыто.
     */
    /**
     * Окно приложения на экране или нет — от платформы (Android: `onStart` / `onStop`).
     * Ведущему звонка это решает паузу своего видео и сторож камеры (заказчик 2026-10-01).
     */
    fun visible(visible: Boolean) {
        for (k in kept.values) k.host.appVisible(visible)
    }

    fun window(deviceId: String): MutableState<Window> = windows.getOrPut(deviceId) { mutableStateOf(Window.Phone) }
}
