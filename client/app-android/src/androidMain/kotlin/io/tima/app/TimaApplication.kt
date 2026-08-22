package io.tima.app

import android.app.Application
import io.tima.core.secrets.AndroidSecrets

/**
 * Точка, где Android отдаёт приложению контекст.
 *
 * Нужна ровно для одного: хранилищу секретов ([AndroidSecrets]) контекст необходим, а
 * общая подпись `platformVault(scope)` о нём не знает и знать не должна — на ПК и на
 * Apple контекста нет. Протаскивать его через все слои ради одной платформы значило бы
 * впустить Android в общий код.
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
    }
}
