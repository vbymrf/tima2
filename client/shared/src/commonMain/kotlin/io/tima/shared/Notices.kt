package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.notify.Notice
import io.tima.core.notify.NoticeKind
import io.tima.core.notify.Notifier
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookList
import io.tima.domain.chat.ChatPerson
import io.tima.feature.chat.PERSON_FIRST_LINE
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.line

/**
 * Что заслуживает уведомления — ПЛАН-УВЕДОМЛЕНИЙ.md, У5…У10.
 *
 * Правила живут здесь, а не в `core-notify`, потому что они знают то, чего показу знать
 * незачем: кто заблокирован, какое устройство своё и сошлась ли подпись.
 *
 * ── ДВЕ СТАДИИ ОДНОЙ СТРОКИ (У6) ────────────────────────────────────────────
 *
 * ```
 * конверт пришёл       →  «Новое сообщение»   [arrived]
 * подпись сошлась      →  «Борис»             [opened] — ТА ЖЕ строка, по тому же ключу
 * подпись не сошлась   →  остаётся безымянной
 * ```
 *
 * Обе нужны, и ни одну нельзя выкинуть:
 *
 * - показать имя сразу нельзя — оно лежит в **открытой** части конверта, подписью не
 *   покрыто, и «Мама написала» подделал бы всякий, кто знает `user_id` мамы. А узнать
 *   его можно: он виден тому, кто состоит с человеком в одной группе или нашёл его
 *   поиском по нику;
 * - ждать разбора тоже нельзя — при недоехавшем ключе человек не узнал бы **ничего**, а
 *   это как раз тот случай, когда узнать надо.
 *
 * ── ЗВОНОК — НАОБОРОТ, И ЭТО ЗАКОННО ────────────────────────────────────────
 *
 * Кто звонит, говорит **сервер**: строку в `calls` заводит он, а не звонящий. Для
 * звонков сервер и есть источник правды (ADR-0026), и мы ему в этом уже верим каждый
 * раз, когда рисуем журнал. Значит имя показывается сразу.
 */
class Notices(
    private val notifier: Notifier,
    /** Кто я: своё сообщение с другого своего устройства уведомления не заслуживает. */
    private val me: String,
    /** Строка книги про человека; `null` — его в книге нет вовсе. */
    private val entryOf: suspend (String) -> BookEntry?,
    /**
     * Карточка человека со справочника — ради **ника незнакомца** (У9).
     *
     * `null` — не спросили или он не ответил. Тогда строка остаётся безымянной, и это
     * правильнее выдуманного имени.
     */
    private val cardOf: suspend (String) -> ChatPerson?,
    /** Чем звать человека: тот же порядок полей, что в списках (`Вид`). */
    private val look: suspend () -> PersonLook,
    private val words: () -> Words = { CurrentWords.value },
) {

    /**
     * Переписка, открытая прямо сейчас; `null` — ни одна.
     *
     * Уведомлять о том, что человек читает глазами, незачем: он уже узнал. Держится
     * здесь, а не проверяется на экране, потому что решает это **приёмник** — он
     * приходит в чужую минуту, и спрашивать у экрана ему не у кого.
     */
    @Volatile
    private var openChat: String? = null

    /**
     * Видно ли окно приложения.
     *
     * Без этого открытая переписка молчала бы и **после того, как окно убрали**: человек
     * нажал «Домой» или закрыл окно в трей, а переписка по-прежнему числится открытой —
     * и уведомления из неё пропадают до следующего захода. Ровно та поломка, которую
     * замечают не сразу и объясняют «у меня не приходят сообщения».
     */
    @Volatile
    private var windowShown: Boolean = true


    /**
     * Конверт записан, подпись ещё не проверена — У6, первая стадия.
     *
     * @return `true`, если строка показана. Ложь — уведомлять было не о чем.
     */
    suspend fun arrived(chatId: String, senderId: String?): Boolean {
        if (isWatched(chatId) || !shouldNotify(senderId)) return false
        notifier.show(
            Notice(
                key = chatId,
                kind = NoticeKind.Message,
                // Имени нет намеренно: до проверки подписи называть человека нельзя.
                who = null,
                what = words().notices.newMessage,
            ),
        )
        return true
    }

    /**
     * Подпись сошлась — У6, вторая стадия: та же строка получает имя.
     *
     * Не вторая строка: два уведомления об одном сообщении человек читает как два
     * сообщения.
     */
    suspend fun opened(chatId: String, senderId: String) {
        if (isWatched(chatId) || !shouldNotify(senderId)) return
        val name = nameOf(senderId)
        notifier.show(
            Notice(
                key = chatId,
                kind = NoticeKind.Message,
                who = name,
                // Назвать нечем — остаётся то же, что было: «Новое сообщение». Строка
                // «Написал вам» без имени не значила бы ничего.
                what = if (name == null) words().notices.newMessage else words().notices.wroteToYou,
            ),
        )
    }

    /** Нам звонят — У7. Имя сразу: его утверждает сервер, а не звонящий. */
    suspend fun calling(callId: String, fromUserId: String) {
        if (!shouldNotify(fromUserId)) return
        // В журнал — что строку поставили: «звонок пришёл, а телефон молчал» иначе не
        // отличить от «строку не ставили вовсе» (так и было до 2026-09-26).
        Journal.note(LogCode.CALL, "уведомление о входящем поставлено", "звонок" to callId.take(8))
        notifier.show(
            Notice(
                key = CALL_KEY_PREFIX + callId,
                kind = NoticeKind.Call,
                who = nameOf(fromUserId),
                what = words().notices.incomingCall,
            ),
        )
    }

    /** Звонок кончился — чем бы ни кончился. Строка звонка не переживает звонок. */
    fun callOver(callId: String) = notifier.hide(CALL_KEY_PREFIX + callId)

    /**
     * Человек открыл переписку или ушёл из неё — У10.
     *
     * Снимает то, что уже показано, и **гасит будущие**: сообщение, пришедшее при
     * открытой переписке, человек видит и так — строка в шторке про него была бы
     * уведомлением о том, что он читает.
     */
    fun watching(chatId: String?) {
        openChat = chatId
        chatId?.let(notifier::hide)
    }

    /** Окно показалось или ушло — У4. Убранное окно ничего не показывает глазами. */
    fun windowVisible(visible: Boolean) {
        windowShown = visible
    }

    /** Выход из аккаунта: чужих строк в шторке остаться не должно. */
    fun forget() = notifier.hideAll()

    /**
     * Уведомлять ли о человеке — У8.
     *
     * Заблокированный молчит, и проверка здесь **явная**. Пока уведомление ставилось
     * после разбора, молчание выходило само собой — его конверт мы не открываем (Л9).
     * Теперь строка ставится ДО разбора, и «само собой» больше не работает.
     *
     * Своё сообщение с другого своего устройства — тоже молча: человек сам его и
     * написал. Отправитель здесь не проверен подписью, но для **умолчания** этого
     * довольно: худшее, чего добьётся подделка, — не покажет своего же уведомления.
     */
    /** Человек смотрит на эту переписку прямо сейчас. */
    private fun isWatched(chatId: String): Boolean = windowShown && chatId == openChat

    private suspend fun shouldNotify(userId: String?): Boolean {
        if (userId.isNullOrBlank() || userId == me) return false
        return entryOf(userId)?.list != BookList.Blocked
    }

    /**
     * Как назвать человека — У9.
     *
     * Он в книге — зовём тем же порядком полей, что и списки: заказчик уже распространил
     * «Отображать пользователя как» на журнал звонков 2026-09-19, и второй источник имени
     * однажды разошёлся бы с первым.
     *
     * Его в книге нет — остаётся `@ник`. Ника нет — **строка без имени вовсе** (решение
     * заказчика 2026-09-24): выдуманное имя хуже отсутствующего.
     */
    private suspend fun nameOf(userId: String): String? {
        val card = cardOf(userId)
        val entry = entryOf(userId)
        if (entry != null) {
            val person = ChatPerson(
                name = entry.name,
                userName = card?.userName,
                nick = card?.nick,
                phone = entry.phone.ifBlank { card?.phone },
            )
            person.line(look(), PERSON_FIRST_LINE)?.let { return it }
        }
        return card?.nick?.let { "@$it" }
    }

    private companion object {
        /**
         * Ключи звонков и переписок не должны столкнуться.
         *
         * Идентификаторы у них разной природы, и совпадение маловероятно — но «маловероятно»
         * здесь означало бы, что звонок однажды снимет уведомление о сообщении.
         */
        const val CALL_KEY_PREFIX = "call:"
    }
}
