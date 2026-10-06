package io.tima.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Сервер требует аттестацию у этого телефона (ПЛАН-(ЗБ)-ЗАЩИТЫ-ОТ-БОТОВ ЗБ1).
 *
 * Под требованием сервер отказывает устройству во всём, кроме аттестации: `403` с кодом
 * [CODE] и заголовком [HEADER]. Узнаётся требование здесь, в общем месте, по заголовку — так
 * его не пропустит ни один из полутора десятков Api, а тело ответа разбирать не нужно.
 *
 * Что с ним делать, решает приложение: телефон проходит аттестацию заново, ПК её не умеет.
 * Очереди отправки такой отказ считают временным — сообщение ждёт, а не выбрасывается.
 *
 * Общий на процесс, как [ServerClock]: требование — свойство устройства, а не запроса.
 */
object AttestationDemand {

    /** Код отказа в теле ответа. */
    const val CODE: String = "attestation_required"

    /** Заголовок отказа; значение — `required`. */
    const val HEADER: String = "X-Tima-Attestation"

    private val count = MutableStateFlow(0L)

    /** Сколько раз сервер требовал аттестацию за жизнь процесса. Ноль — не требовал. */
    val raised: StateFlow<Long> = count.asStateFlow()

    /** Учесть ответ сервера: заголовок [HEADER] со значением `required` — требование. */
    fun observe(header: String?) {
        if (header == "required") count.update { it + 1 }
    }
}
