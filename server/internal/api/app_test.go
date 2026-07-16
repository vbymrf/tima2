package api

import (
	"encoding/json"
	"net/http"
	"testing"
)

// TestAppVersion — /app/version: 204 пока не настроено, JSON с version_code когда задано.
func TestAppVersion(t *testing.T) {
	ts, srv := setup(t)

	// Не настроено → 204, клиент молчит про обновления
	resp, err := http.Get(ts.URL + "/api/v1/app/version")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("без конфига: ожидался 204, получен %d", resp.StatusCode)
	}

	// Настроено → 200 + поля версии
	srv.AppVer = &AppVersion{VersionCode: 5, VersionName: "0.2.0", APKUrl: "https://api.example.com/download/TIMA-0.2.0.apk", Notes: "тест"}
	resp2, err := http.Get(ts.URL + "/api/v1/app/version")
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("с конфигом: ожидался 200, получен %d", resp2.StatusCode)
	}
	var got AppVersion
	if err := json.NewDecoder(resp2.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.VersionCode != 5 || got.APKUrl == "" || got.VersionName != "0.2.0" {
		t.Fatalf("некорректный ответ версии: %+v", got)
	}
}
