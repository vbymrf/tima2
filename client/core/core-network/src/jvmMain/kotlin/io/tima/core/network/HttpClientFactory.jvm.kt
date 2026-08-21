package io.tima.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Desktop и Android. Один движок на оба: расхождение поведения сети между ними в v1
 * стоило дороже, чем экономия на зависимости.
 */
actual fun httpEngine(): HttpClientEngineFactory<*> = OkHttp
