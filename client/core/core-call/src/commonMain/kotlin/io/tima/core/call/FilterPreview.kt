package io.tima.core.call

import kotlinx.coroutines.flow.StateFlow

/**
 * Своё изображение с фильтрами **без звонка** (ПЛАН-(ФЗ) ФЗ12, заказчик 2026-11-12: «В Настройках Звонки нужно создать
 * кнопку Фильтры, при нажатии на неё покажет твоё изображение и фильтры — позволит настроить их без видео звонка»).
 *
 * Камера открывается тем же обработчиком кадров, что в звонке, — видно ровно то, что увидит собеседник. Пока идёт
 * звонок, показ не открывается: камера занята разговором.
 */
interface FilterPreview {
    /** Картинка с фильтрами; `null` — камера не открыта. */
    val previewVideo: StateFlow<VideoHandle?>

    /** Передняя ли камера сейчас (ФЗ10: на задней «Лицо» и «Морщины» не рисуются). */
    val previewFront: StateFlow<Boolean>

    /** Что не вышло — словами; `null` — всё в порядке. */
    val previewTrouble: StateFlow<String?>

    /** Есть ли вторая камера — кнопка смены. */
    val previewSwitchable: Boolean

    suspend fun startPreview(filters: CallFilters)

    /** Фильтры сменились — сразу в показ. */
    fun previewFilters(filters: CallFilters)

    suspend fun switchPreviewCamera()

    /** Отпустить камеру — при уходе с экрана. */
    suspend fun stopPreview()
}
