# Каталог раздачи APK (авто-обновление)

Сюда кладётся собранный APK клиента. Caddy раздаёт его по `https://api.DOMAIN/download/<имя>.apk`
(см. `deploy/caddy/Caddyfile`), а приложение скачивает по ссылке из `APP_APK_URL` (`.env`).

**Выпуск новой версии:**
1. Подними `versionCode` (и `versionName`) в `client/composeApp/build.gradle.kts`, собери APK.
2. Скопируй APK сюда, напр. `TIMA-0.2.0.apk`.
3. В `deploy/.env`: `APP_LATEST_VERSION_CODE` = новый versionCode, `APP_LATEST_VERSION_NAME`,
   `APP_APK_URL=https://api.DOMAIN/download/TIMA-0.2.0.apk`, `APP_UPDATE_NOTES`.
4. `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d backend`.

Старые версии можно оставлять в каталоге — они не мешают. Сами `.apk` в git не хранятся.
