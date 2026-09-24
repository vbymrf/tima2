package io.tima.shared

import io.tima.core.database.TimaDatabase
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * То, что живёт, пока жив **процесс**, а не пока живо окно — ПЛАН-УВЕДОМЛЕНИЙ.md, У2.
 *
 * ── ЧТО БЫЛО ────────────────────────────────────────────────────────────────
 *
 * В `BackgroundLoops` стояло прямо: «живой канал держится, пока живо окно», и держал его
 * `LaunchedEffect`. Для приложения без уведомлений это было верно — приложение без окна
 * никого не слушало и никому ничего не показывало.
 *
 * Уведомление переворачивает это условие: оно нужно ровно тогда, когда окна нет.
 *
 * ── ПОЧЕМУ СБОРКА ПЕРЕЕХАЛА СЮДА ЦЕЛИКОМ ────────────────────────────────────
 *
 * Можно было оставить сборку в композиции и держать здесь только цикл канала. Тогда
 * служба, поднятая после перезагрузки телефона, не нашла бы **ничего**: ни базы, ни
 * сети, ни приёмника — они появлялись бы только с первым открытием окна. То есть
 * «уведомления после перезагрузки» работали бы лишь у того, кто после перезагрузки
 * приложение открыл, а это ровно тот человек, которому они и не нужны.
 *
 * Поэтому здесь и кэш сборок, и её цикл. Окно теперь **берёт готовое**, а не строит.
 *
 * ── ОДИН ВЛАДЕЛЕЦ, И ЭТО НЕ ПЕРЕСТРАХОВКА ───────────────────────────────────
 *
 * Сборка кэшируется по устройству, и второй такой же не появляется. Иначе вышло бы два
 * владельца одной базы, а это ломает сразу три вещи:
 *
 * - SQLDelight оповещает слушателей таблицы **внутри своего экземпляра**: вторая сборка
 *   записала бы сообщение, а списки первой об этом не узнали;
 * - `Inbox.openNext` читает «следующее `RECEIVED`» и обновляет его не одной транзакцией —
 *   две сборки разобрали бы одну запись дважды;
 * - курсор у устройства один, и `ack` от двоих двигал бы его за обоих.
 */
object ChannelHost {

    /**
     * Своя область, не связанная ни с одним экраном.
     *
     * `SupervisorJob`: падение одного цикла не должно уносить остальные — канал и очередь
     * живут своей жизнью, и беда у одного не повод замолчать другому.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Сборки по устройству. Ключ — `deviceId`: смена аккаунта это другое устройство. */
    private val assemblies = mutableMapOf<String, Assembled>()

    /** Кто сейчас держит канал и каким заданием. */
    private var heldDevice: String? = null
    private var job: Job? = null

    /**
     * Готовая сборка для устройства — та же самая при каждом обращении.
     *
     * @param build как собрать, если её ещё нет. Зовётся **не больше одного раза** на
     *   устройство за жизнь процесса.
     */
    @Synchronized
    fun assembled(deviceId: String, build: () -> Assembled): Assembled =
        assemblies.getOrPut(deviceId, build)

    /**
     * Держать канал этой сборки, пока жив процесс.
     *
     * Повторный вызов с той же сборкой ничего не делает: зовут это и окно при каждой
     * пересборке состава, и служба при каждом запуске, и поднимать второй канал на
     * каждый такой вызов означало бы два `ack` по одному курсору.
     */
    @Synchronized
    fun hold(assembled: Assembled) {
        val deviceId = assembled.session.deviceId
        if (heldDevice == deviceId && job?.isActive == true) return
        job?.cancel()
        heldDevice = deviceId
        Journal.note(LogCode.NET_CHANNEL, "канал взят процессом, а не окном")
        job = scope.launch { assembled.receiver.hold() }
    }

    /**
     * Отпустить канал — выход из аккаунта.
     *
     * Не зовётся при закрытии окна: в том и смысл, что окно закрыли, а канал остался.
     */
    @Synchronized
    fun release() {
        job?.cancel()
        job = null
        heldDevice = null
    }

    /** Держится ли канал прямо сейчас. Нужно службе: без сборки ей держать нечего. */
    @Synchronized
    fun holding(): Boolean = job?.isActive == true

    /**
     * Уведомления той сборки, чей канал держим; `null` — не держим ничей.
     *
     * Нужно точке входа: показалось окно или ушло, знает платформа, а не композиция —
     * на ПК это трей, на Android `onStart`/`onStop`.
     */
    @Synchronized
    fun notices(): Notices? = heldDevice?.let { assemblies[it]?.notices }
}
