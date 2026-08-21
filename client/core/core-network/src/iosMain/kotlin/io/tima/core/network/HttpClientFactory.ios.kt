package io.tima.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

/**
 * Apple. Darwin, а не CIO: он идёт через `NSURLSession`, то есть подчиняется
 * системным настройкам VPN и хранилищу доверенных сертификатов. Свой стек на iOS
 * означал бы, что корпоративный или личный VPN человека нас не касается.
 */
actual fun httpEngine(): HttpClientEngineFactory<*> = Darwin
