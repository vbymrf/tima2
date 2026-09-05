package io.tima.app

import android.app.Application
import io.tima.core.contacts.AndroidContacts
import io.tima.core.secrets.AndroidSecrets

/**
 * Точка, где Android отдаёт приложению контекст.
 *
 * Нужна ровно для одного: тем частям, что ходят в системные хранилища, контекст
 * необходим, а общие подписи `platformVault(scope)` и `platformPhoneBook()` о нём не
 * знают и знать не должны — на ПК и на Apple контекста нет. Протаскивать его через все
 * слои ради одной платформы значило бы впустить Android в общий код.
 *
 * Больше здесь ничего не заводится. Граф зависимостей (Koin) приезжает в `shared`
 * вместе с экранами — К5; собирать его здесь заранее значило бы делать из точки входа
 * второй композиционный корень.
 */
class TimaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // До первого обращения к хранилищу — то есть до всего остального.
        AndroidSecrets.install(this)
        // До первого открытия вкладки «Контакты». Разрешение при этом не спрашивается:
        // контекст нужен, чтобы было чем спросить, когда человек туда дойдёт.
        AndroidContacts.install(this)
    }
}
