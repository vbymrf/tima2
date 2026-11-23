package io.tima.feature.chat

import io.tima.domain.chat.ComposedWidget

/**
 * Куда уходит то, что отправлено панелью ввода (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.13, СЦ24; заказчик 2026-11-23: «единый
 * код»). Панель одна — строка ввода, скрепка, голосовое; отправка у каждого места своя: переписка — [ChatStore] (очередь
 * отправки), «Написать», «Обращения», «Управление → Сообщения», лента канала — свои.
 */
interface MessageSink {
    fun sendPhotos(images: List<Pair<ByteArray, String>>)
    fun sendFile(bytes: ByteArray, mime: String, name: String)
    fun sendVoice(ogg: ByteArray, durationMs: Long, wave: ByteArray)
    fun sendContact(name: String, phone: String)
    fun sendPlace(lat: Double, lon: Double, label: String = "")
    fun sendLink(url: String, title: String = "", cloudKind: String = "")

    /** Виджет отдельным сообщением (ссылка из скрепки, «Из облака»); ответ — что вышло, у каждого своё. */
    fun sendWidget(widget: ComposedWidget): Any?

    /** Сказать одно предложение под панелью — отказ, предел. */
    fun said(text: String)
}
