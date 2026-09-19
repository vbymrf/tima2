package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Решения заказчика 2026-09-19, закреплённые проверкой.
 *
 * Здесь не проверяется работа — работать пока нечему. Здесь закреплено то, что **решено** и
 * что легко потерять при следующей правке: набор кодеков, запасной кодек и RED. Каждое из
 * них стоило разбора, и вернуть его случайно дороже, чем удержать.
 */
class PublishPresetTest {

    @Test
    fun av1_в_наборе_нет_совсем() {
        // «AV1 убираем совсем, так как нет поддержки — значит не для мессенджера».
        // Подтверждено замером телефонов стенда: аппаратного кодера AV1 нет ни на одном.
        // В SDK он есть — убираем мы, и эта проверка о нашем решении, а не о SDK.
        assertEquals(listOf("h264", "vp9", "h265"), VideoCodec.entries.map { it.wire })
    }

    @Test
    fun svc_умеет_только_vp9() {
        // Следствие отказа от AV1, которое легко не заметить: SVC в WebRTC есть только у
        // VP9 и AV1. Значит весь вопрос «simulcast или SVC» держится на VP9 одном, и если
        // его однажды уберут — сравнивать режимы слоёв станет не на чем.
        assertTrue(VideoCodec.VP9.svcCapable)
        assertFalse(VideoCodec.H264.svcCapable)
        assertFalse(VideoCodec.H265.svcCapable)
    }

    @Test
    fun запасной_кодек_по_умолчанию_H264_а_не_VP8() {
        // Ловушка SDK: при SVC он сам ставит запасным VP8 с simulcast
        // (`BackupVideoCodec(codec = "vp8", simulcast = true)`), и прогон «VP9 SVC» тогда
        // мерит не VP9 — второй телефон может попросить VP8, и публикующий закодирует ещё
        // и три его слоя. Поэтому запасной задан явно, и это H.264 — аппаратный везде.
        assertEquals(VideoCodec.H264, VideoPreset().backup)
    }

    @Test
    fun red_включён_по_умолчанию() {
        // Решение заказчика: голос ~24 кбит/с становится ~48, на фоне видео это незаметно,
        // а разборчивость на потерях до ~20 % держится. Сервер сам снимет RED для тех, кто
        // его не умеет.
        assertTrue(AudioPreset().red)
    }

    @Test
    fun умолчание_публикации_безопасное() {
        // Один слой аппаратным кодеком: точка отсчёта забега и разумное поведение, если
        // испытательный режим выключен и пресет никто не выбирал.
        val preset = PublishPreset(name = "умолчание")
        assertEquals(VideoCodec.H264, preset.video.codec)
        assertEquals(LayerMode.Single, preset.video.layers)
        assertTrue(preset.video.dynacast, "dynacast задан явно: SDK включает его сам у SVC")
    }
}
