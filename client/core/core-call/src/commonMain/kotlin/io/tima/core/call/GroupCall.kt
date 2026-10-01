package io.tima.core.call

/**
 * Групповой звонок в личной группе (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ.md, ГЗ3; решения заказчика
 * 2026-10-01).
 *
 * Звонок идёт в группе: в существующей личной, а без неё — во временной, которая живёт
 * 12 часов от последнего звонка. Начинает модератор и выше, без модераторов — владелец;
 * войти может любой участник группы, пока мест меньше предела. Командует создатель.
 */

/**
 * Группа идущего звонка — то, что сервер сказал в двери сверх обычного.
 *
 * @param creatorId кто начал звонок: только у него команды (решение 6).
 * @param paused звонок на паузе в момент входа (решение 5); дальше пауза приходит событием
 *   комнаты ([CallState.roomPaused]).
 */
data class GroupRoom(
    val groupId: String,
    val creatorId: String,
    val paused: Boolean = false,
    val rules: GroupRules = GroupRules(),
    /** Мне запрещены микрофон и видео — запрет создателя держится при перезаходе. */
    val micForbidden: Boolean = false,
    val videoForbidden: Boolean = false,
)

/**
 * Правила группового звонка с сервера (решение 4): до 4 участников — 720p, до 8 — 480p,
 * больше — только голос; не больше 25.
 */
data class GroupRules(
    val max: Int = 25,
    val tiers: List<VideoTier> = listOf(VideoTier(4, 720), VideoTier(8, 480)),
) {
    /**
     * Потолок высоты видео при [count] участниках, считая себя. `null` — только голос.
     */
    fun heightFor(count: Int): Int? = tiers.sortedBy { it.upTo }.firstOrNull { count <= it.upTo }?.height

    /** Сколько участников ещё с видео: больше — только голос. */
    val videoUpTo: Int get() = tiers.maxOfOrNull { it.upTo } ?: 0
}

/** «До [upTo] участников включительно — видео не выше [height]». */
data class VideoTier(val upTo: Int, val height: Int)

/**
 * Участник группового звонка, как его видит движок.
 *
 * @param identity `user:device` — у человека может быть два устройства в одной комнате.
 * @param paused свернул приложение, и его видео на паузе (атрибут [VideoPause.ATTRIBUTE]).
 * @param video его картинка; `null` — камера выключена, мы отписались или видео нет по
 *   правилам (больше 8 участников).
 */
data class CallPeer(
    val identity: String,
    val userId: String,
    val microphoneOn: Boolean = false,
    val cameraOn: Boolean = false,
    val speaking: Boolean = false,
    val paused: Boolean = false,
    val video: VideoHandle? = null,
    /**
     * Его видео показывается, а у нас кадров нет — и почему ([RemoteVideoWatch]). Своё у
     * каждого участника: в групповом общая строка окна говорила бы про случайного.
     */
    val videoLoss: RemoteVideoLoss? = null,
    /**
     * Чем он публикует — его слово атрибутом [BenchAttribute.ATTRIBUTE]: набор, кодек, кадр,
     * кодер, обрезка. `null` — не сказал (старая версия). Для журнала стенда (заказчик
     * 2026-10-01, 5а).
     */
    val bench: String? = null,
    /** Что я принимаю от него — числа моей стороны. `null` — видео от него не приходит. */
    val incoming: PeerIncoming? = null,
)

/**
 * Входящее видео одного участника глазами получателя: кадр, кодек, полоса, раскодировщик,
 * время на кадр, выброшенные кадры и замирания за звонок.
 */
data class PeerIncoming(
    val frame: String,
    val codec: String?,
    val kbit: Long?,
    val decoder: String,
    val decodeMs: Double?,
    val dropped: Long?,
    val freezes: Long,
)

/**
 * Чем участник публикует — атрибутом, как «что раскодирую» ([PeerCodecs]). Обновляется
 * при перемене, а не по часам: полоса и кадры в секунду в него не входят.
 */
object BenchAttribute {
    const val ATTRIBUTE = "tima.bench"
}

/** Пауза и закреплённый — из данных комнаты: `{"paused":true,"pinned":"<user>"}`. */
object RoomMeta {
    fun paused(metadata: String?): Boolean = metadata?.replace(" ", "")?.contains("\"paused\":true") == true

    fun pinned(metadata: String?): String =
        metadata?.let { Regex("\"pinned\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }.orEmpty()
}

/** Кто такой участник по `identity` LiveKit: `user:device`. */
fun userOfIdentity(identity: String): String = identity.substringBefore(':')

/**
 * Звонок группы со слов сервера — `GET /groups/{id}/call`: для полосы «Идёт звонок» над
 * перепиской и для журнала звонка.
 *
 * @param call идущий звонок; `null` — звонка в группе нет.
 * @param canStart может ли спросивший начать звонок (роль модератор и выше, решение 14).
 * @param ttlUntilMs когда удалится временная группа; `null` — группа обычная.
 */
data class GroupCallInfo(
    val call: GroupCallLive?,
    val canStart: Boolean,
    val myRole: String,
    val rules: GroupRules,
    val ttlUntilMs: Long?,
)

/** Идущий звонок группы. */
data class GroupCallLive(
    val callId: String,
    val creatorId: String,
    val video: Boolean,
    val startedAtMs: Long,
    val paused: Boolean,
    val members: List<GroupCallMember>,
)

/**
 * Участник звонка со слов сервера.
 *
 * @param state `invited` — позван и не входил · `joined` — в комнате · `left` — вышел или
 *   отклонил.
 * @param invited отмечен создателем; `false` — вошёл сам по полосе в группе.
 * @param removed создатель удалил его из звонка (решение 17) — не из группы.
 */
data class GroupCallMember(
    val userId: String,
    val state: String,
    val invited: Boolean,
    val removed: Boolean,
    /** Запрет создателя: сервер не принимает от него звук или видео (уточнение 2026-10-01). */
    val micForbidden: Boolean = false,
    val videoForbidden: Boolean = false,
) {
    val inRoom: Boolean get() = state == "joined"
}

/** Команды создателя (решения 5, 6, 17) — слова ручки `POST /calls/{id}/control`. */
enum class GroupControl(val wire: String) {
    /** Позвать ещё участника группы в идущий звонок («Добавить» в журнале звонка). */
    Invite("invite"),
    /**
     * Запретить микрофон или видео: сервер перестаёт принимать этот источник, пока создатель
     * не разрешит (уточнение заказчика 2026-10-01: это запрет, а не выключение).
     */
    MuteMic("mute_mic"),
    MuteVideo("mute_video"),
    /** Снять запрет — включает участник сам. */
    AllowMic("allow_mic"),
    AllowVideo("allow_video"),
    /** Закрепить участника наверху вида «Говорящий» у всех; открепить (заказчик 2026-10-01). */
    Pin("pin"),
    Unpin("unpin"),
    Remove("remove"),
    Pause("pause"),
    Resume("resume"),
    Stop("stop"),
    ;

    companion object {
        fun of(wire: String): GroupControl? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Набор под потолок высоты [height] — групповой звонок по числу участников (решение 4).
 * Ширина и битрейт — в той же пропорции: 480 из 720 — это меньше точек, и та же полоса на
 * них была бы растратой, которой у восьмерых нет. Ниже потолка набор не меняется.
 */
fun PublishPreset.cappedTo(height: Int): PublishPreset {
    if (video.height <= height) return this
    val k = height.toDouble() / video.height
    val width = ((video.width * k).toInt() / 16) * 16
    val area = k * k
    return copy(
        video = video.copy(
            width = width.coerceAtLeast(16),
            height = height,
            bitrate = (video.bitrate * area).toInt().coerceAtLeast(MIN_GROUP_BITRATE),
            maxBitrate = (video.maxBitrate * area).toInt().coerceAtLeast(MIN_GROUP_BITRATE),
        ),
    )
}

/** Ниже этого видео распадается на квадраты — урезать дальше незачем. */
private const val MIN_GROUP_BITRATE = 150_000
