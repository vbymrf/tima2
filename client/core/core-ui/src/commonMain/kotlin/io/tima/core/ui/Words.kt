package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Словарь надписей (ПЛАН-ЯЗЫКА Я1).
 *
 * ── ПОЧЕМУ СЛОВАРЬ НА KOTLIN, А НЕ ФАЙЛ РЕСУРСОВ ────────────────────────────
 *
 * У нас **тексты сидят в перечислениях**: имя пункта настроек одновременно ключ навигации
 * и надпись. Файл ресурсов такой случай обслуживает плохо, а компилятор, который ловит
 * пропущенный перевод, стоит дороже удобства переводчика — переводчика у нас нет вовсе,
 * переводит агент (решение заказчика 2026-09-08).
 *
 * Пропущенная надпись здесь **не собирается**, а не обнаруживается на экране: интерфейс
 * обязывает реализацию назвать всё.
 *
 * ── КАК РАЗДАЁТСЯ ───────────────────────────────────────────────────────────
 *
 * Через `CompositionLocal`, как цвета ([LocalTimaColors]). Механизм проверен на оформлении:
 * смена языка на лету — это подмена словаря, а не перезапуск экрана.
 *
 * ── ЧТО СЮДА НЕ КЛАДЁТСЯ ────────────────────────────────────────────────────
 *
 * **Журнал и коды журнала, отчёт о проблеме** — они инструмент чинящего, и переведённый
 * журнал перестаёт находиться поиском по коду. Это решение, а не забывчивость.
 */
interface Words {

    /** Двухбуквенный тег языка: `ru`, `en`, `es`. Он же лежит в настройках. */
    val tag: String

    /** Название языка на нём самом: так его узнают в списке, не зная текущего. */
    val ownName: String

    // ── Общее ────────────────────────────────────────────────────────────────

    val back: String
    val cancel: String
    val ready: String
    val send: String
    val hide: String
    val noConnection: String

    // ── Настройки ────────────────────────────────────────────────────────────

    val settings: String
    val language: String
    val languageAbout: String

    // ── Разговор под записью (ADR-0024) ─────────────────────────────────────

    val comments: String
    val thread: String
    val commentHint: String
    val reply: String
    val commentsClosed: String
    val commentsClosedOld: String
    val nobodyWroteYet: String
    val postGone: String

    // ── Сообщества ───────────────────────────────────────────────────────────

    val community: String
    val communities: String
    val subscribe: String
    val unsubscribe: String
    val bringHere: String
    val takeOut: String
    val bringingKeepsEverything: String
}

/**
 * Русский словарь.
 *
 * Он же образец для остальных: перевод — это тот же список имён с другими значениями, и
 * ни одно имя не пропадает. Пропадёт — не соберётся.
 */
object RussianWords : Words {
    override val tag = "ru"
    override val ownName = "Русский"

    override val back = "Назад"
    override val cancel = "Отмена"
    override val ready = "Готово"
    override val send = "Отправить"
    override val hide = "Скрыть"
    override val noConnection = "Нет связи с сервером"

    override val settings = "Настройки"
    override val language = "Язык"
    override val languageAbout = "Язык приложения. Сообщения не переводятся"

    override val comments = "Комментарии"
    override val thread = "Ветка"
    override val commentHint = "Написать комментарий…"
    override val reply = "Ответить"
    override val commentsClosed = "Обсуждение закрыто"
    override val commentsClosedOld = "Обсуждение закрыто. Написанное раньше осталось"
    override val nobodyWroteYet = "Здесь ещё никто не написал"
    override val postGone = "Записи больше нет"

    override val community = "Сообщество"
    override val communities = "Сообщества"
    override val subscribe = "Подписаться"
    override val unsubscribe = "Отписаться"
    override val bringHere = "Внести"
    override val takeOut = "Вынуть"
    override val bringingKeepsEverything =
        "Переписка, участники и ключи не меняются — меняется одна ссылка"
}

/**
 * Языки приложения.
 *
 * Три: русский, английский, испанский — решение заказчика 2026-09-08. Английский и
 * испанский заводятся вместе со своими словарями (Я9, Я10); до тех пор список знает о них,
 * но выбрать можно лишь то, у чего словарь есть — [available].
 */
enum class Language(val tag: String, val ownName: String, val words: Words?) {
    Russian("ru", "Русский", RussianWords),
    English("en", "English", null),
    Spanish("es", "Español", null);

    /** Есть ли словарь. Выбирать язык без словаря — обещать надписи, которых нет. */
    val available: Boolean get() = words != null

    companion object {
        /** По тегу из настроек. Незнакомый тег — русский: приложение обязано открыться. */
        fun of(tag: String): Language = entries.firstOrNull { it.tag == tag } ?: Russian
    }
}

/**
 * Раздача словаря. Умолчание — русский: приложение обязано говорить даже там, где язык
 * ещё не выбран.
 */
val LocalWords: ProvidableCompositionLocal<Words> = staticCompositionLocalOf { RussianWords }

/** Короткий доступ: `Tima.words.comments`. */
val Tima.words: Words
    @Composable get() = LocalWords.current
