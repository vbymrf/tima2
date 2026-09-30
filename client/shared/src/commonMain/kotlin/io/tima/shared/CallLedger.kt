package io.tima.shared

import io.tima.core.call.CallUpdate
import io.tima.domain.chat.CallRecord

/**
 * Что делать телефону по изменениям ленты звонков — ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md, ВЗ0а.
 *
 * Чистое правило без сети и экрана: на входе изменения **по возрастанию номера**, на
 * выходе — действия в том же порядке. Отдельно от приёмника, чтобы проверяться целиком.
 *
 * ── ПОЧЕМУ ПО НОМЕРУ И ЦЕЛОЙ ПАЧКОЙ ──────────────────────────────────────────
 *
 * Звонящий набрал и через секунду передумал: «ringing» и «cancelled» приходят одной
 * догрузкой. Применённые по одному они заставили бы телефон зазвонить и тут же замолчать;
 * применённые наоборот — звонить по отменённому звонку до срока. Поэтому пачка читается
 * целиком: звонок, закончившийся в ней же, не звонит вовсе, а становится пропущенным.
 */
object CallLedger {

    /** Действие для телефона. `callId` — всегда первым: по нему всё и сверяется. */
    sealed interface Action {
        val callId: String

        /** Нам звонят — звонить. */
        data class Ring(override val callId: String, val fromId: String, val video: Boolean) : Action

        /** Трубку взяли на другом устройстве этого человека — замолчать, звонок не трогать. */
        data class Taken(override val callId: String) : Action

        /**
         * Трубку взяли **здесь** — строка звонка снимается, мелодия молчит. Без этого она
         * звенела всё время разговора (Redmi, 2026-09-26).
         */
        data class AnsweredHere(override val callId: String) : Action

        /** Звонок кончился; [why] — слово ленты: declined, cancelled, busy, ended, missed. */
        data class End(override val callId: String, val why: String) : Action

        /**
         * Нам звонили и не дождались — уведомление «пропущенный». [atMs] — когда сервер
         * записал конец: по нему старое, поднятое догонкой, молчит (ЖУ3).
         */
        data class Missed(override val callId: String, val fromId: String, val video: Boolean, val atMs: Long = 0) : Action

        /** Пропущенный просмотрен на каком-то устройстве — снять строку в шторке. */
        data class MissedSeen(override val callId: String) : Action

        /** Вызов дошёл до телефона собеседника — у звонящего «Звонит». */
        data class Delivered(override val callId: String) : Action

        /** Вызов за 5 с не подтвердил никто — «не в сети»; звонок идёт дальше. */
        data class Unreachable(override val callId: String) : Action
    }

    private val ENDS = setOf("declined", "cancelled", "busy", "ended", "missed")

    /** Конец, который для вызываемого — пропущенный: он трубку не брал и не отклонял. */
    private val MISSED_FOR_CALLEE = setOf("cancelled", "missed")

    fun actions(me: String, updates: List<CallUpdate>): List<Action> {
        // Звонки, которые в этой же пачке кончились или ушли на другое устройство, —
        // звонить по ним нечего.
        val closed = updates
            .filter { it.change in ENDS || (it.change == "answered" && it.call.peerId == me) }
            .map { it.callId }
            .toSet()
        val missedTold = mutableSetOf<String>()
        val out = mutableListOf<Action>()
        for (u in updates) {
            val callee = u.call.peerId == me
            val caller = u.call.initiatorId == me
            when {
                callee && u.change == "ringing" ->
                    // Снимок — на момент чтения: звонок мог кончиться, пока телефон спал.
                    if (u.callId !in closed && u.call.ringing) {
                        out += Action.Ring(u.callId, u.call.initiatorId, u.call.video)
                    }
                callee && u.change == "answered" ->
                    out += if (u.here) Action.AnsweredHere(u.callId) else Action.Taken(u.callId)
                callee && u.change in ENDS -> {
                    out += Action.End(u.callId, u.change)
                    if (u.change in MISSED_FOR_CALLEE && missedTold.add(u.callId)) {
                        out += Action.Missed(u.callId, u.call.initiatorId, u.call.video, u.atMs)
                    }
                }
                callee && u.change == "seen" -> out += Action.MissedSeen(u.callId)
                caller && u.change == "delivered" -> out += Action.Delivered(u.callId)
                caller && u.change == "unreachable" -> out += Action.Unreachable(u.callId)
                caller && u.change in ENDS -> out += Action.End(u.callId, u.change)
                // «answered» звонящему: разговор и так начнётся в комнате. «seen» звонящему
                // не приходит — просмотренным бывает только пропущенный, а он у вызываемого.
                else -> Unit
            }
        }
        return out
    }

    /**
     * Звонки из журнала, которые звонят мне прямо сейчас, — при запуске процесса
     * (заказчик 2026-09-27, см. `Receiver.ringingNow`).
     *
     * Только `ringing` и только где я вызываемый: свой исходящий после перезапуска
     * поднимать нечем — комнату и токен знал умерший процесс. И только свежие: строка
     * старше [freshMs] — это `ringing`, переживший уборщика сервера, а не звонок.
     */
    fun stillRinging(me: String, records: List<CallRecord>, nowMs: Long, freshMs: Long): List<Action.Ring> =
        records
            .filter { it.state == "ringing" && it.peerId == me && it.createdAt >= nowMs - freshMs }
            .map { Action.Ring(it.callId, it.initiatorId, it.video) }
}
