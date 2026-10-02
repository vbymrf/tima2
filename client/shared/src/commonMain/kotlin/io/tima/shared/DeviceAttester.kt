package io.tima.shared

/**
 * Аттестация ключа телефона (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ8, закладка Р20–Р23). Реализует
 * `app-android`: ключ P-256 в защищённой части телефона с вызовом сервера в записи аттестации.
 *
 * Сервер пока только записывает итог (режим `record`): так видно, какие телефоны и прошивки
 * проходят, прежде чем кому-то отказывать.
 */
fun interface DeviceAttester {
    /**
     * @param challengeToken токен вызова сервера; в запись аттестации кладётся его sha256.
     * @param signed что подписать ключом аттестации — открытые ключи этого устройства.
     * @return `null` — телефон этого не умеет.
     */
    fun attest(challengeToken: String, signed: ByteArray): AttestedKey?
}

/** Цепочка сертификатов (листовой первым, DER) и подпись ключом аттестации. */
class AttestedKey(val chain: List<ByteArray>, val signature: ByteArray)
