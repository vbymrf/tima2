package io.tima.domain.chat

/**
 * Вылечить группу, у которой ключа нет **ни у кого**.
 *
 * Два состояния «ключа нет» выглядят на экране одинаково, а лечатся противоположно:
 *
 * | Что на сервере | Что делать |
 * |---|---|
 * | версия ключа > 0, обёрток для нас нет | **просить** у участников — у них ключ есть |
 * | версия ключа = 0 — ключ не выпускался никогда | **выпустить** самим: просить некого |
 *
 * Второе состояние — след потерянного вызова ротации при создании группы (2026-09-16…18,
 * см. `CreateGroupChat`). Группы, созданные до починки, так и остались бы немыми: кнопка
 * «Запросить ключ» ищет тех, у кого он есть, а его нет ни у кого. Поэтому открытие
 * группы без ключа сначала спрашивает сервер, была ли вообще версия, и если нет —
 * выпускает первую. Любой участник вправе: обёртки уйдут всем устройствам состава.
 */
class HealGroupKey(
    private val sync: SyncGroupKeys,
    private val rotator: GroupKeyRotator,
) {

    suspend fun heal(groupId: String): HealStep {
        // Сначала — забрать то, что могло быть выдано нам, но не приехало: ротация
        // соседа, пропущенный кадр. Это дешевле выпуска и правильнее его.
        val synced = sync.refresh(groupId)
        // Сервер отвечает not_e2e на любой вопрос о ключах публичной группы: у неё ключа
        // нет по замыслу — сообщения идут открытыми уровнями 0…3. Это не беда, а род
        // группы, и баннер «ключа нет» здесь ложь.
        if (synced is SyncKeysStep.Refused && synced.reason.contains("not_e2e")) return HealStep.NotEncrypted
        if (synced !is SyncKeysStep.Synced) return HealStep.Unknown
        if (synced.added > 0) return HealStep.Fetched(synced.added)
        if (synced.currentVersion > 0) return HealStep.NeedAsk

        // Первый выпуск — как вход участника: так делал v1, и порог несрочных к нему не
        // применяется, предыдущей ротации нет.
        return when (val turned = rotator.rotate(groupId, RotationReason.MemberJoin)) {
            RotateStep.Rotated, RotateStep.VersionConflict -> HealStep.Issued
            is RotateStep.Refused -> if (turned.reason.contains("not_e2e")) HealStep.NotEncrypted else HealStep.Unknown
            else -> HealStep.Unknown
        }
    }
}

sealed interface HealStep {
    /** Обёртки нашлись на сервере и открылись: ключ теперь есть. */
    data class Fetched(val versions: Int) : HealStep

    /** Ключа не было ни у кого — выпущен первый. */
    data object Issued : HealStep

    /** Ключ у сервера есть, у нас нет: путь прежний — просить участников. */
    data object NeedAsk : HealStep

    /** Группа публичная: ключа нет по замыслу, сообщения идут открытыми уровнями. */
    data object NotEncrypted : HealStep

    /** Сервер не ответил или отказал: ничего не изменилось. */
    data object Unknown : HealStep
}
