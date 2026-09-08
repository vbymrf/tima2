package io.tima.shared

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.remember
import io.tima.core.database.TimaDatabase
import io.tima.domain.account.Session
import io.tima.core.encryption.DeviceTokenSignerOverKodium
import io.tima.core.encryption.deviceIdentityFrom

/**
 * Сборка приложения: где конкретные подсистемы соединяются в одно.
 *
 * ── ЗАЧЕМ ОТДЕЛЬНЫЙ ФАЙЛ ────────────────────────────────────────────────────
 *
 * `Root.kt` совмещал четыре обязанности: сборку подсистем, запуск фоновых циклов,
 * навигацию и сборку Store для каждого окна. Пока окон было три, это читалось; на
 * семи стало 598 строк и 46 импортов, и каждое новое окно правило тот же файл, что
 * и все остальные, — то есть очередь на merge там, где по смыслу пересечения нет.
 *
 * Здесь — только «кто из чего состоит». Ни навигации, ни `@Composable`-разметки, ни
 * циклов: они в [Корень] и [ФоновыеЦиклы] соответственно.
 */
class Assembled(
    /** Кто мы для сервера: нужен экранам, которым важен свой идентификатор. */
    val session: Session,
    val environment: Environment,
    val network: Network,
    val sender: Sender,
    /**
     * Второй проход очереди — групповой.
     *
     * Отдельно от [sender], потому что до отправки у них разное всё: личному нужны ключ
     * эпохи escrow и устройства собеседника, групповому — версия ключа группы либо
     * ничего, если сообщение открытое.
     */
    val groupSender: GroupSender,
    val receiver: Receiver,
    val keyOrchestrator: GroupKeyOrchestrator,
    /**
     * Звонок «под вашей записью ответили» (ADR-0024, следствие 5).
     *
     * Поток, а не обратный вызов: экран страницы появляется и исчезает, а канал живёт всё
     * время работы приложения. Отдавать ему ссылку на живой экран значило бы держать
     * закрытый экран в памяти ради события, которое ему уже некуда показать.
     *
     * Значение — номер записи, под которой ответили; каждое событие меняет его, и этого
     * достаточно, чтобы открытая страница перечиталась.
     */
    val commentPings: StateFlow<Long>,
)

/**
 * Собрать всё для заведённого устройства.
 *
 * `remember` по устройству, а не по составу окна: другое устройство — другая база и
 * другой ключ покоя, и переиспользовать собранное между ними нельзя.
 */
@Composable
fun assemble(
    entry: Entry,
    device: Entry.Device,
    /**
     * Открыть базу по **имени файла**. Имя считает общий код (`databaseFor`), а не
     * приложение: правило именования одно на все платформы, и два одинаковых правила в
     * двух приложениях однажды разошлись бы.
     */
    deviceDatabase: (String) -> TimaDatabase,
    /**
     * Первый заведённый аккаунт: за ним остаётся прежнее имя базы.
     *
     * Иначе человек, уже пользующийся приложением, после обновления открыл бы пустую
     * базу — переписка осталась бы в файле, которого никто больше не ищет.
     */
    firstAccount: String? = null,
): Assembled =
    remember(device) {
        val environment = Environment.open(
            deviceDatabase(databaseFor(device.session.userId, firstAccount)),
            device.secret,
            device.session.userId,
        )
        val identity = deviceIdentityFrom(device.secret)

        // Подпись ключом ЭТОГО устройства — то, чем обновляется просроченный токен
        // (находка 2026-09-06). Ключ выводится из секрета, который здесь уже открыт:
        // второй путь к хранилищу означал бы второе место, где его можно потерять.
        val signer = DeviceTokenSignerOverKodium(identity)
        val network = Network.create(
            session = device.session,
            host = entry.host,
            sign = signer::sign,
            // Новый токен переживает перезапуск: иначе каждый запуск начинался бы с
            // обновления, а первый запрос до него — с 401.
            remember = { session -> entry.rememberSession(session) },
        )

        // Оркестр ключей собирается ЗДЕСЬ, а не внутри приёмника: ему нужны escrow,
        // крипта, сеть и хранилище разом — это работа сборки, а не канала.
        val keyOrchestrator = GroupKeyOrchestrator(
            environment = environment,
            network = network,
            identity = identity,
            msNow = ::msNow,
        )

        // Звонки о комментариях: канал кладёт сюда, страница читает. Заводится здесь,
        // потому что живёт столько же, сколько сборка, — а не столько, сколько экран.
        val commentPings = MutableStateFlow(0L)

        Assembled(
            session = device.session,
            environment = environment,
            network = network,
            sender = Sender(
                environment = environment,
                network = network,
                session = device.session,
                identity = identity,
            ),
            groupSender = GroupSender(
                environment = environment,
                network = network,
                session = device.session,
                identity = identity,
                rotate = keyOrchestrator::rotate,
                stale = keyOrchestrator::keyStale,
            ),
            receiver = Receiver(
                environment = environment,
                network = network,
                session = device.session,
                identity = identity,
                keyOrchestrator = keyOrchestrator,
                onComment = { _, postId -> commentPings.value = postId },
            ),
            keyOrchestrator = keyOrchestrator,
            commentPings = commentPings,
        )
    }
