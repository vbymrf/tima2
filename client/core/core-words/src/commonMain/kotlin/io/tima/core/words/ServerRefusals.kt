package io.tima.core.words

/**
 * Коды отказа сервера — словами.
 *
 * Сервер отвечает кодом (`device_unproven`) и русской строкой, а до клиента доходит только
 * код. До 2026-10-06 он так и выходил на экран: человек при входе видел `device_unproven` и не
 * понимал, что делать. Перевод — здесь, в одном месте на все экраны: экранов с отказами
 * десятки, и таблица на каждом разошлась бы.
 *
 * Незнакомый код не прячется: общими словами и с кодом в скобках — его прочтёт тот, кто
 * разбирает отчёт о проблеме.
 */
internal object ServerRefusals {

    private val code = Regex("[a-z][a-z0-9_]*")

    /** Код отказа, а не текст: строчная латиница с подчёркиванием, без пробелов. */
    fun isCode(text: String): Boolean = code.matches(text)

    val ru: Map<String, String> = mapOf(
        "device_unproven" to "Это устройство не заверено. Введите секретную фразу или подключите его по QR с телефона.",
        "phrase_required" to "Нужна секретная фраза аккаунта. Обновите приложение и войдите по фразе.",
        "phone_required" to "Новый аккаунт и новый ключ личности заводятся только на телефоне Android с приложением TIMA.",
        "phone_unproven" to "Этот телефон ещё не подтверждён фразой. Сначала «Подтвердить фразой», потом заверять другие устройства.",
        "start_anew_banned" to "Владелец запретил «Начать заново» на этом аккаунте. Войти можно только по секретной фразе.",
        "identity_closed" to "Эта фраза — от прежнего ключа личности аккаунта, им больше не войти.",
        "identity_mismatch" to "Фраза не подходит к этому аккаунту — проверьте запись.",
        "bad_signature" to "Фраза не та — проверьте запись.",
        "rate_limited" to "Слишком много попыток. Подождите и попробуйте позже.",
        "bad_code" to "Код неверен или устарел — запросите новый.",
        "bad_token" to "Код из SMS устарел — запросите новый.",
        "phone_mismatch" to "Код пришёл не на тот номер или устарел — запросите новый.",
        "bad_challenge" to "Запрос устарел — попробуйте ещё раз.",
        "rereg_open" to "Идёт перерегистрация аккаунта: это станет можно, когда спор кончится.",
        "not_in_window" to "Подтверждение принимается только в окне подтверждения.",
        "no_rereg" to "Перерегистрации нет — подтверждать нечего.",
        "no_claim" to "Заявки нет: её уже подтвердили или отменили.",
        "already_claimed" to "Заявка уже подана.",
        "no_phone_change" to "Заявки на смену номера нет.",
        "phone_change_open" to "Заявка на смену номера уже подана.",
        "phone_taken" to "Этот номер уже привязан к другому аккаунту.",
        "not_your_request" to "Подтвердить смену номера может только ключ личности, подавший заявку.",
        "nothing_to_cancel" to "Отменять нечего.",
        "already_current" to "Вы уже под этой личностью.",
        "device_not_found" to "Устройство не найдено — возможно, его уже отключили.",
        "device_revoked" to "Это устройство отключено от аккаунта.",
        "no_signing_key" to "У этого телефона нет ключа заверения — сначала введите на нём фразу.",
        "not_a_phone" to "Заверять устройства может только телефон.",
        "no_key_copy" to "Копия ключей ещё не заведена.",
        "stale_epoch" to "Ключ копии сменился — откройте экран заново и повторите.",
        "not_moderator" to "Это могут только владелец и модераторы.",
        "not_participant" to "Вы не участник этой переписки.",
        "forbidden" to "Нет прав на это действие.",
        "unauthorized" to "Вход устарел — откройте приложение заново.",
        "user_not_found" to "Пользователь не найден.",
        "not_found" to "Не найдено.",
        "internal" to "Ошибка на сервере — попробуйте позже.",
    )

    val en: Map<String, String> = mapOf(
        "device_unproven" to "This device is not certified. Enter the secret phrase or connect it by QR from your phone.",
        "phrase_required" to "The account's secret phrase is required. Update the app and sign in with the phrase.",
        "phone_required" to "A new account and a new identity key can be created only on an Android phone with the TIMA app.",
        "phone_unproven" to "This phone is not confirmed with the phrase yet. First “Confirm with phrase”, then certify other devices.",
        "start_anew_banned" to "The owner has forbidden “Start over” on this account. You can sign in only with the secret phrase.",
        "identity_closed" to "This phrase belongs to the account's previous identity key; it can no longer sign in.",
        "identity_mismatch" to "The phrase does not match this account — check what you wrote down.",
        "bad_signature" to "Wrong phrase — check what you wrote down.",
        "rate_limited" to "Too many attempts. Wait and try again later.",
        "bad_code" to "The code is wrong or expired — request a new one.",
        "bad_token" to "The SMS code has expired — request a new one.",
        "phone_mismatch" to "The code went to a different number or expired — request a new one.",
        "bad_challenge" to "The request has expired — try again.",
        "rereg_open" to "The account is being re-registered: this will be possible when the dispute ends.",
        "not_in_window" to "Confirmation is accepted only within the confirmation window.",
        "no_rereg" to "There is no re-registration — nothing to confirm.",
        "no_claim" to "There is no claim: it was already confirmed or cancelled.",
        "already_claimed" to "The claim has already been filed.",
        "no_phone_change" to "There is no number change request.",
        "phone_change_open" to "A number change request has already been filed.",
        "phone_taken" to "This number is already linked to another account.",
        "not_your_request" to "Only the identity key that filed the request can confirm the number change.",
        "nothing_to_cancel" to "Nothing to cancel.",
        "already_current" to "You are already under this identity.",
        "device_not_found" to "Device not found — it may have been disconnected already.",
        "device_revoked" to "This device is disconnected from the account.",
        "no_signing_key" to "This phone has no certifying key — enter the phrase on it first.",
        "not_a_phone" to "Only a phone can certify devices.",
        "no_key_copy" to "The key copy has not been created yet.",
        "stale_epoch" to "The copy key has changed — reopen the screen and try again.",
        "not_moderator" to "Only the owner and moderators can do this.",
        "not_participant" to "You are not a participant of this chat.",
        "forbidden" to "You are not allowed to do this.",
        "unauthorized" to "Your sign-in has expired — reopen the app.",
        "user_not_found" to "User not found.",
        "not_found" to "Not found.",
        "internal" to "Server error — try again later.",
    )

    val es: Map<String, String> = mapOf(
        "device_unproven" to "Este dispositivo no está certificado. Introduzca la frase secreta o conéctelo por QR desde su teléfono.",
        "phrase_required" to "Se necesita la frase secreta de la cuenta. Actualice la aplicación y entre con la frase.",
        "phone_required" to "Una cuenta nueva y una clave de identidad nueva solo se crean en un teléfono Android con la aplicación TIMA.",
        "phone_unproven" to "Este teléfono aún no está confirmado con la frase. Primero «Confirmar con la frase», luego certifique otros dispositivos.",
        "start_anew_banned" to "El propietario prohibió «Empezar de cero» en esta cuenta. Solo se puede entrar con la frase secreta.",
        "identity_closed" to "Esta frase es de la clave de identidad anterior de la cuenta; ya no sirve para entrar.",
        "identity_mismatch" to "La frase no corresponde a esta cuenta — revise lo que anotó.",
        "bad_signature" to "La frase no es correcta — revise lo que anotó.",
        "rate_limited" to "Demasiados intentos. Espere e inténtelo más tarde.",
        "bad_code" to "El código es incorrecto o caducó — solicite uno nuevo.",
        "bad_token" to "El código SMS caducó — solicite uno nuevo.",
        "phone_mismatch" to "El código llegó a otro número o caducó — solicite uno nuevo.",
        "bad_challenge" to "La solicitud caducó — inténtelo de nuevo.",
        "rereg_open" to "La cuenta se está re-registrando: será posible cuando termine la disputa.",
        "not_in_window" to "La confirmación solo se acepta dentro de la ventana de confirmación.",
        "no_rereg" to "No hay re-registro — no hay nada que confirmar.",
        "no_claim" to "No hay reclamación: ya se confirmó o se canceló.",
        "already_claimed" to "La reclamación ya se presentó.",
        "no_phone_change" to "No hay solicitud de cambio de número.",
        "phone_change_open" to "Ya se presentó una solicitud de cambio de número.",
        "phone_taken" to "Este número ya está vinculado a otra cuenta.",
        "not_your_request" to "Solo la clave de identidad que presentó la solicitud puede confirmar el cambio de número.",
        "nothing_to_cancel" to "No hay nada que cancelar.",
        "already_current" to "Ya está bajo esta identidad.",
        "device_not_found" to "Dispositivo no encontrado — quizá ya se desconectó.",
        "device_revoked" to "Este dispositivo está desconectado de la cuenta.",
        "no_signing_key" to "Este teléfono no tiene clave de certificación — introduzca primero la frase en él.",
        "not_a_phone" to "Solo un teléfono puede certificar dispositivos.",
        "no_key_copy" to "La copia de claves aún no se ha creado.",
        "stale_epoch" to "La clave de la copia cambió — vuelva a abrir la pantalla y repita.",
        "not_moderator" to "Solo el propietario y los moderadores pueden hacerlo.",
        "not_participant" to "No participa en esta conversación.",
        "forbidden" to "No tiene permiso para esta acción.",
        "unauthorized" to "Su sesión caducó — vuelva a abrir la aplicación.",
        "user_not_found" to "Usuario no encontrado.",
        "not_found" to "No encontrado.",
        "internal" to "Error del servidor — inténtelo más tarde.",
    )
}
