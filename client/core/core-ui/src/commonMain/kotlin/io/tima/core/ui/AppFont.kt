package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.resources.Res
import io.tima.core.ui.resources.OpenSans
import io.tima.core.ui.resources.Roboto
import io.tima.core.words.AppearanceWords
import org.jetbrains.compose.resources.Font

/**
 * Шрифт приложения — ПЛАН-ШРИФТОВ Ш1, решение заказчика 2026-09-15.
 *
 * ── ПОЧЕМУ ШРИФТ СВОЙ, А НЕ СИСТЕМНЫЙ ───────────────────────────────────────
 *
 * Системный у каждой платформы свой: на Android Roboto, на Windows что найдётся у JVM,
 * на раннере сборки третье. Один и тот же экран выглядел по-разному везде, а меры
 * интерфейса при этом одни. Свой шрифт в сборке делает вид предсказуемым — и заодно
 * снимает целый класс расхождений между снимком и телефоном.
 *
 * **«Системный» остаётся пунктом списка**, а не выкидывается: человек, привыкший к
 * шрифту своей системы (или поставивший его ради чтения), вправе его вернуть.
 *
 * ── ЧТО ЛЕЖИТ В СБОРКЕ ──────────────────────────────────────────────────────
 *
 * Roboto и Open Sans, переменные начертания — один файл на семью вместо четырёх, около
 * 500 КБ каждый. Веса Skia выводит из переменной оси, поэтому отдельный полужирный не
 * нужен. Лицензии (обе OFL-1.1) лежат рядом с файлами, в той же папке ресурсов.
 *
 * Noto не кладётся: он нужен для письменностей, которых у нас пока нет вовсе. Появится
 * четвёртый язык с другой письменностью — появится и Noto.
 */
enum class AppFont {
    /** Умолчание. Им же рисует Android, поэтому на телефоне вид не меняется. */
    Roboto,
    OpenSans,

    /** Шрифт платформы. `null` в [LocalFontFamily] — то, что было до Ш1. */
    System,
    ;

    companion object {
        val byDefault: AppFont = Roboto

        /** Прочитать из хранилища. Непонятное значение — умолчание, а не падение. */
        fun of(saved: String?): AppFont = entries.firstOrNull { it.name == saved } ?: byDefault
    }
}

/** Надпись пункта — в словаре, а не в перечислении (ПЛАН-ЯЗЫКА Я2). */
fun AppearanceWords.font(font: AppFont): String = when (font) {
    AppFont.Roboto -> fontRoboto
    AppFont.OpenSans -> fontOpenSans
    AppFont.System -> fontSystem
}

/**
 * Семейство для выбранного шрифта. `null` — системный.
 *
 * `@Composable`, потому что ресурс грузится через `Res`: на Android это asset, на ПК
 * файл в jar, на iOS — bundle. Один и тот же вызов на всех платформах, и это главная
 * причина брать ресурсы Compose, а не classpath: classpath на iOS нет вовсе.
 */
@Composable
fun familyOf(font: AppFont): FontFamily? = when (font) {
    AppFont.System -> null
    AppFont.Roboto -> FontFamily(
        Font(Res.font.Roboto, FontWeight.Normal),
        Font(Res.font.Roboto, FontWeight.Bold),
        Font(Res.font.Roboto, FontWeight.ExtraBold),
    )
    AppFont.OpenSans -> FontFamily(
        Font(Res.font.OpenSans, FontWeight.Normal),
        Font(Res.font.OpenSans, FontWeight.Bold),
        Font(Res.font.OpenSans, FontWeight.ExtraBold),
    )
}
