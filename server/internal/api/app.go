// Проверка обновлений приложения (self-distributed APK, вне Google Play):
// клиент опрашивает GET /api/v1/app/version и сравнивает version_code со своим.
// Конфигурация — из env (main.go): APP_LATEST_VERSION_CODE/NAME, APP_APK_URL,
// APP_UPDATE_NOTES, APP_STREAM.
package api

import (
	"encoding/json"
	"net/http"
)

// AppVersion — последняя доступная версия клиента. VersionCode=0 → фича выключена.
//
// **Stream добавлен 2026-08-26 и добавлен НЕОБЯЗАТЕЛЬНЫМ.** Номера версий сравнимы
// только внутри одного потока сборок: у v1 сейчас version_code 24, у v2 — 2, и клиент
// v2, сравнив числа, предложил бы поставить поверх себя прошлогоднюю v1. Поток
// называет, кому предложение адресовано.
//
// Поле необязательное, потому что API только расширяется: установленный клиент,
// который про stream не знает, читает ответ ровно как читал. Пустой stream означает
// «сервер поток не объявляет» — и новый клиент из осторожности не предлагает ничего.
type AppVersion struct {
	VersionCode int    `json:"version_code"`
	VersionName string `json:"version_name"`
	APKUrl      string `json:"url"`
	Notes       string `json:"notes"`
	Stream      string `json:"stream,omitempty"`
}

// appVersion — публичный (без токена): клиент опрашивает его на старте.
func (s *Server) appVersion(w http.ResponseWriter, _ *http.Request) {
	if s.AppVer == nil || s.AppVer.VersionCode == 0 || s.AppVer.APKUrl == "" {
		w.WriteHeader(http.StatusNoContent) // обновления не настроены — клиент молчит
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s.AppVer)
}
