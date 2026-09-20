package io.tima.core.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

/**
 * Android: системный диалог про микрофон и камеру, а если система его больше не покажет —
 * страница приложения в настройках.
 *
 * **Написано по образцу `AndroidContactsAccess`** и по тем же двум причинам:
 *
 * 1. Нужна активность, а не контекст приложения: разрешение спрашивает окно.
 * 2. `requestPermissions` показывает диалог **не всегда** — если человек однажды отказал
 *    так, что система решила больше не спрашивать, вызов молча не делает ничего. Кнопка
 *    «позвонить» при этом выглядит сломанной, и в неё жмут повторно.
 *
 * Общего кода с контактами нет намеренно: общий потребовал бы модуля, который знает и про
 * книгу телефона, и про звонки, а это два разных разрешения с разной судьбой.
 *
 * ── ПОЧЕМУ МИКРОФОН И КАМЕРА СПРАШИВАЮТСЯ ОДНИМ ВЫЗОВОМ ─────────────────────
 *
 * Их два в одном `requestPermissions`, но это один диалог системы, а не два подряд. Два
 * подряд человек читает как один и жмёт наугад. Для голосового звонка в списке только
 * микрофон: камеру, которая не понадобится, просить нечем объяснить.
 *
 * ── ЧТО СЧИТАЕТСЯ УСПЕХОМ ───────────────────────────────────────────────────
 *
 * **Микрофон.** Отказ от камеры при выданном микрофоне — не провал: звонок пойдёт
 * голосом, и это лучше, чем не пойти вовсе. Отказ от микрофона — провал: комната
 * соединится, собеседник будет виден, а звука не будет ни в одну сторону.
 */
object AndroidCallAccess {

    private const val REQUEST = 4202
    private const val PREFS = "call"
    private const val ASKED = "asked"

    @Volatile
    private var activity: Activity? = null

    @Volatile
    private var waiting: ((Boolean) -> Unit)? = null

    /** Вызывается из `onCreate`. */
    fun attach(activity: Activity) {
        this.activity = activity
    }

    /**
     * Вызывается из `onDestroy` — **с той самой активностью**, которая умирает.
     *
     * Проверка `===` не перестраховка: Android умеет создать новое окно раньше, чем
     * доломает старое. Без неё уходящая активность обнуляла бы ссылку на живую, и дальше
     * кнопка «позвонить» не делала бы ничего — молча. Ровно это ловили на realme
     * 2026-09-05 с контактами.
     */
    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        waiting = null
    }

    /**
     * Вызывается из `onRequestPermissionsResult`.
     *
     * ── СПРАШИВАЕМ СИСТЕМУ, А НЕ ОТВЕТ СИСТЕМЫ ──────────────────────────────
     *
     * Здесь был разбор массивов `permissions` и `results`, и он **ломал видеозвонок
     * целиком**. [ask] не просит того, что уже выдано, — значит при видеозвонке на
     * телефоне, где микрофон уже разрешён, система спрашивает **только камеру**, и
     * `RECORD_AUDIO` в ответе не приходит вовсе. Разбор находил его отсутствие и отвечал
     * «микрофона нет», после чего звонок заканчивался словами «нет доступа к микрофону» —
     * при выданном микрофоне и независимо от того, что человек ответил про камеру.
     *
     * Голосовой звонок делают первым, и микрофон после него выдан всегда. То есть
     * ломался **каждый** видеозвонок, кроме самого первого звонка на телефоне.
     *
     * Поэтому итог берётся у системы: `checkSelfPermission` знает правду про все
     * разрешения сразу, и разойтись с ней нельзя.
     */
    fun answered(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (requestCode != REQUEST) return
        val current = activity
        val microphone = current != null &&
            current.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = current != null &&
            current.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        // Половина «не работает» на Android — это невыданное разрешение, и по отчёту это
        // должно быть видно сразу (правило журнала, группа «разрешение»). Пишем **оба**:
        // по одному микрофону не отличить «отказал в камере» от «отказал во всём».
        if (microphone) Journal.note(LogCode.PERM_GRANTED, "звонок", "что" to "RECORD_AUDIO")
        else Journal.trouble(LogCode.PERM_DENIED, "звонок", "что" to "RECORD_AUDIO")
        if (permissions.contains(Manifest.permission.CAMERA)) {
            if (camera) Journal.note(LogCode.PERM_GRANTED, "звонок", "что" to "CAMERA")
            else Journal.trouble(LogCode.PERM_DENIED, "звонок", "что" to "CAMERA")
        }

        // Звонок держится на микрофоне: отказ от камеры при выданном микрофоне — не
        // провал, разговор пойдёт голосом.
        waiting?.invoke(microphone)
        waiting = null
    }

    internal fun ask(video: Boolean, onResult: (Boolean) -> Unit) {
        val current = activity
        if (current == null) {
            // Спрашивать не через кого. Раньше это молчало, и «кнопка ничего не делает»
            // было неотличимо от «человек отказал».
            Journal.trouble(LogCode.CALL, "разрешение спросить не через кого — окна нет")
            return onResult(false)
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { current.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) return onResult(true)

        val prefs = current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val asked = prefs.getBoolean(ASKED, false)
        if (asked && needed.none { current.shouldShowRequestPermissionRationale(it) }) {
            // ── СИСТЕМА БОЛЬШЕ НЕ СПРОСИТ ───────────────────────────────────
            //
            // Android перестаёт показывать диалог после второго отказа, и `requestPermissions`
            // с этого момента молча не делает ничего. Кнопка при этом выглядит сломанной —
            // ровно то, что заказчик описал словами «повторно не просит».
            //
            // **Раньше мы отсюда уводили человека в системные настройки.** Выглядело это
            // как вылет: приложение исчезало с экрана без объяснения, посреди звонка. Так
            // и было прочитано 2026-09-20 — «вылетело приложение», а в журнале на этом
            // месте стоит наш собственный `APP-BACKGROUND`.
            //
            // Теперь молчим и отвечаем отказом. Куда идти, человеку говорит надпись на
            // экране звонка: выкидывать его из приложения ради этого незачем.
            Journal.trouble(
                LogCode.PERM_DENIED,
                "система больше не спрашивает — чинится в настройках телефона",
                "что" to needed.joinToString(",") { it.substringAfterLast('.') },
            )
            return onResult(false)
        }

        Journal.note(
            LogCode.CALL,
            "спрашиваю разрешение",
            "что" to needed.joinToString(",") { it.substringAfterLast('.') },
        )
        prefs.edit().putBoolean(ASKED, true).apply()
        waiting = onResult
        current.requestPermissions(needed.toTypedArray(), REQUEST)
    }

}

actual fun askCallAccess(video: Boolean, onResult: (Boolean) -> Unit) =
    AndroidCallAccess.ask(video, onResult)
