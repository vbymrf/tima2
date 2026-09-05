package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.tima.core.database.TimaDatabase
import io.tima.domain.account.Session
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
        val network = Network.create(device.session, entry.host)
        val identity = deviceIdentityFrom(device.secret)

        // Оркестр ключей собирается ЗДЕСЬ, а не внутри приёмника: ему нужны escrow,
        // крипта, сеть и хранилище разом — это работа сборки, а не канала.
        val keyOrchestrator = GroupKeyOrchestrator(
            environment = environment,
            network = network,
            identity = identity,
            msNow = ::msNow,
        )

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
            ),
            keyOrchestrator = keyOrchestrator,
        )
    }
