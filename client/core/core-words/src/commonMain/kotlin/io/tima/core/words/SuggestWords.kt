package io.tima.core.words

/**
 * «Предложить изменения» — пункт «Помощи» (заказчик 2026-10-07): сообщение и фото, как в
 * «Сообщить о проблеме», без журнала и без «О чём». Уходят версия, модель, система и ник.
 */
interface SuggestWords {
    val whatToChange: String
    val hint: String
    val photosAbout: String
    val whatGoes: String
    val whatGoesAbout: String
    val writeSomething: String
    val sent: String
    val number: String
    val writeAgainHow: String
    val queuedAbout: String
    fun refused(status: Int): String
}

object RussianSuggestWords : SuggestWords {
    override val whatToChange = "Что изменить"
    override val hint = "Что сделать иначе или что добавить"
    override val photosAbout = "Снимок экрана или набросок того, как должно быть. До трёх."
    override val whatGoes = "Что уйдёт"
    override val whatGoesAbout =
        "Текст, фото, версия приложения, модель, система и ник. Журнал и переписка не отправляются."
    override val writeSomething = "Напишите, что изменить — без этого не отправить."
    override val sent = "Предложение отправлено"
    override val number = "Предложение получено, номер:"
    override val writeAgainHow = "Чтобы написать ещё раз, выйдите и снова откройте «Предложить изменения»."
    override val queuedAbout =
        "Сети сейчас нет, предложение сохранено на устройстве и уйдёт само. Приложение можно закрыть."
    override fun refused(status: Int) = "Сервер не принял предложение ($status)"
}

object EnglishSuggestWords : SuggestWords {
    override val whatToChange = "What to change"
    override val hint = "What to do differently or what to add"
    override val photosAbout = "A screenshot or a sketch of how it should be. Up to three."
    override val whatGoes = "What will be sent"
    override val whatGoesAbout =
        "The text, photos, app version, device model, system and nickname. The log and chats are not sent."
    override val writeSomething = "Write what to change — it cannot be sent without that."
    override val sent = "Suggestion sent"
    override val number = "Suggestion received, number:"
    override val writeAgainHow = "To write again, leave and reopen “Suggest changes”."
    override val queuedAbout =
        "There is no network now; the suggestion is saved on the device and will go by itself. You can close the app."
    override fun refused(status: Int) = "The server did not accept the suggestion ($status)"
}

object SpanishSuggestWords : SuggestWords {
    override val whatToChange = "Qué cambiar"
    override val hint = "Qué hacer de otra manera o qué añadir"
    override val photosAbout = "Una captura de pantalla o un boceto de cómo debería ser. Hasta tres."
    override val whatGoes = "Qué se enviará"
    override val whatGoesAbout =
        "El texto, las fotos, la versión de la aplicación, el modelo, el sistema y el apodo. El registro y los chats no se envían."
    override val writeSomething = "Escriba qué cambiar: sin eso no se puede enviar."
    override val sent = "Propuesta enviada"
    override val number = "Propuesta recibida, número:"
    override val writeAgainHow = "Para escribir otra vez, salga y vuelva a abrir «Proponer cambios»."
    override val queuedAbout =
        "Ahora no hay red; la propuesta está guardada en el dispositivo y se enviará sola. Puede cerrar la aplicación."
    override fun refused(status: Int) = "El servidor no aceptó la propuesta ($status)"
}
