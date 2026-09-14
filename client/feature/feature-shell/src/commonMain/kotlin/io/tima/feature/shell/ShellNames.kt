package io.tima.feature.shell

import io.tima.core.words.SettingsListWords
import io.tima.core.words.TabWords
import io.tima.core.words.WindowName
import io.tima.core.words.WindowWords

/**
 * Раскладка «ключ → надпись» для перечней оболочки (ПЛАН-ЯЗЫКА, Я-D).
 *
 * ── ПОЧЕМУ ЗДЕСЬ, А НЕ В СЛОВАРЕ ────────────────────────────────────────────
 *
 * До Я-D словарь брал [Window], [WindowTab] и [SettingsItem] параметром — и ради этого
 * перечни переехали в `core-ui`, потому что модуль словаря не вправе импортировать
 * оболочку. Так дизайн-система узнала про окна, вкладки и настройки.
 *
 * Слой не может знать того, что над ним: `core-words`, называющий [Window], обязан
 * зависеть от `feature-shell`, который уже зависит от словаря, — Gradle цикла не соберёт.
 * Значит раскладка делается у того, чей это ключ, и место ей здесь.
 *
 * **Заслон тот же, что был.** Новое значение перечня не соберётся: сначала упадёт `when`
 * в этом файле, потом три словаря без нового поля.
 *
 * Имена и подписи вызовов не менялись — `words.windows.full(window)` читается ровно так
 * же, как читалось, и вызывающему безразлично, что это стало расширением.
 */

// ── МЕТКИ ДЛЯ СЦЕНАРИЕВ ──────────────────────────────────────────────────────
//
// Отбор в живых прогонах возможен либо по видимому тексту, либо по метке. Текста у
// нас три — русский, английский, испанский, — и сценарий с `tapOn: "Чаты"` на
// английском не находит ничего. Метка от языка не зависит.
//
// **Метка — это ключ, а не надпись**, и берётся она прямо из перечисления. Правило то
// же, что и у надписей («перечисление не носит надпись»), только с другой стороны:
// надпись живёт в словаре, ключ — здесь, и метка есть имя ключа.
//
// Придумывать метки руками нельзя: придуманная разойдётся с ключом при первом
// переименовании, и разойдётся молча. Здесь она выводится, а не назначается.
//
// На экране метки не видно. В дереве доступности она приходит как `resource-id` —
// это включено в точке входа Android (`MainActivity`), потому что `Modifier.testTag`
// сам по себе виден только тестам Compose, а прогонщику нужен именно `resource-id`.

/** Метка окна: `window:Phone`. */
fun Window.tag(): String = "window:" + name

/** Метка вкладки, фильтра или режима: `tab:Chats`. */
fun WindowTab.tag(): String = "tab:" + name

/** Метка пункта настроек: `settings:LANGUAGE`. */
fun SettingsItem.tag(): String = "settings:" + name

/** Три имени окна разом: длинное, короткое и что внутри. */
fun WindowWords.name(window: Window): WindowName = when (window) {
    Window.Phone -> phone
    Window.Social -> social
    Window.Media -> media
    Window.Activity -> activity
    Window.Page -> page
}

/** Длинное имя: переключатель окон и рейка. */
fun WindowWords.full(window: Window): String = name(window).full

/** Короткое имя: собственная шапка окна. */
fun WindowWords.short(window: Window): String = name(window).short

/** Что внутри окна — строкой под названием. */
fun WindowWords.about(window: Window): String = name(window).about

/** Надпись на вкладке, фильтре или переключателе режима. */
fun TabWords.label(tab: WindowTab): String = when (tab) {
    WindowTab.Chats -> chats
    WindowTab.Contacts -> contacts
    WindowTab.Calls -> calls
    WindowTab.View -> view

    WindowTab.All -> all
    WindowTab.FromBook -> fromBook
    WindowTab.Unknown -> unknown
    WindowTab.Missed -> missed

    WindowTab.Common -> common
    WindowTab.Friends -> friends
    WindowTab.Catalogue -> catalogue

    WindowTab.Feed -> feed
    WindowTab.Slides -> slides

    WindowTab.Answers -> answers
    WindowTab.Reactions -> reactions
    WindowTab.Collections -> collections

    WindowTab.Comments -> comments
    WindowTab.Marks -> marks

    WindowTab.Subscribed -> subscribed
    WindowTab.Groups -> groups

    WindowTab.Media -> media
    WindowTab.Messages -> messages
    WindowTab.Open -> open
    WindowTab.Personal -> personal
}

/** Название группы настроек. */
fun SettingsListWords.group(group: SettingsGroup): String = when (group) {
    SettingsGroup.ACCOUNT -> groupAccount
    SettingsGroup.APPLICATION -> groupApplication
    SettingsGroup.BLOGGER -> groupBlogger
    SettingsGroup.HELP -> groupHelp
}

/** Название пункта настроек. */
fun SettingsListWords.item(item: SettingsItem): String = when (item) {
    SettingsItem.PROFILE -> itemProfile
    SettingsItem.DEVICES -> itemDevices
    SettingsItem.NOTIFICATIONS -> itemNotifications
    SettingsItem.VIRTUALS -> itemVirtuals
    SettingsItem.APPEARANCE -> itemAppearance
    SettingsItem.LANGUAGE -> itemLanguage
    SettingsItem.PRIVACY -> itemPrivacy
    SettingsItem.STORAGE -> itemStorage
    SettingsItem.BLOGGER -> itemBlogger
    SettingsItem.QUESTIONS -> itemQuestions
    SettingsItem.PROBLEM -> itemProblem
    SettingsItem.UPDATE -> itemUpdate
    SettingsItem.ABOUT -> itemAbout
}
