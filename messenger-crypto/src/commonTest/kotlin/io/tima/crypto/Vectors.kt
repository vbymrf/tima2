package io.tima.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Канонические тест-векторы — единая точка чтения для всех проверок.
 *
 * Читается не из ресурса, а из сгенерированной константы [VECTORS_JSON]: ресурсы
 * commonTest в KMP недоступны единообразно — на JVM есть getResourceAsStream, в
 * Kotlin/Native его нет вовсе. Раньше каждый тест вызывал
 * `javaClass.getResourceAsStream("/vectors.json")`, и это работало только на JVM.
 *
 * Источник при этом остался один — `schema/test-vectors/vectors.json`; константу
 * собирает задача `generateVectors` при каждой сборке. Копии вектора в
 * репозитории нет, и правило «вектор не правится, чтобы тест позеленел»
 * действует по-прежнему.
 */
internal object Vectors {

    /** Всё содержимое файла. */
    val root: JsonObject by lazy { Json.parseToJsonElement(VECTORS_JSON).jsonObject }

    /** Раздел `vectors` — сами векторы по именам. */
    val all: JsonObject by lazy { root["vectors"]!!.jsonObject }

    /** Вектор по имени; отсутствие — ошибка теста, а не пустое значение. */
    operator fun get(name: String): JsonObject =
        all[name]?.jsonObject ?: error("Вектор '$name' отсутствует в vectors.json")
}
