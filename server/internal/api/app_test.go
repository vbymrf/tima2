package api

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
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
	srv.AppVer = &AppVersion{VersionCode: 5, VersionName: "0.2.0", PackageURL: "https://api.example.com/download/TIMA-0.2.0.apk", Notes: "тест"}
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
	if got.VersionCode != 5 || got.PackageURL == "" || got.VersionName != "0.2.0" {
		t.Fatalf("некорректный ответ версии: %+v", got)
	}
}

// TestAppVersionПоПлатформам — параметр platform (ПЛАН-ОБНОВЛЕНИЯ.md, О1).
//
// Проверяется главное свойство расширения: **запрос без параметра отвечает как прежде**.
// Установленные клиенты про параметр не знают, и смена умолчания означала бы, что
// обновление ломает само себя — то есть чинить его будет нечем.
func TestAppVersionПоПлатформам(t *testing.T) {
	ts, srv := setup(t)

	srv.AppVer = &AppVersion{
		VersionCode: 5,
		VersionName: "2.0.5-dev",
		PackageURL:  "https://api.example.com/download/TIMA-5.apk",
		Stream:      "v2",
		SHA256:      "aa",
	}
	srv.AppVerWin = &AppVersion{
		VersionCode: 4,
		VersionName: "2.0.4-dev",
		PackageURL:  "https://api.example.com/download/TIMA-4.msi",
		Stream:      "v2",
		SHA256:      "bb",
		Size:        123,
	}

	спросить := func(query string) (int, AppVersion) {
		t.Helper()
		resp, err := http.Get(ts.URL + "/api/v1/app/version" + query)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var got AppVersion
		if resp.StatusCode == http.StatusOK {
			if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
				t.Fatal(err)
			}
		}
		return resp.StatusCode, got
	}

	status, got := спросить("")
	if status != http.StatusOK || got.VersionCode != 5 {
		t.Fatalf("без платформы ответ обязан быть прежним (Android): %d %+v", status, got)
	}
	if status, got = спросить("?platform=android"); status != http.StatusOK || got.VersionCode != 5 {
		t.Fatalf("android: %d %+v", status, got)
	}
	status, got = спросить("?platform=windows")
	if status != http.StatusOK || got.VersionCode != 4 {
		t.Fatalf("windows: %d %+v", status, got)
	}
	if got.SHA256 != "bb" || got.Size != 123 {
		t.Fatalf("хэш и размер обязаны доехать до клиента: %+v", got)
	}

	// Неизвестная платформа получает 204, а не пакет для Android: предложить iOS
	// поставить APK значит предложить то, что у человека не запустится.
	if status, _ = спросить("?platform=ios"); status != http.StatusNoContent {
		t.Fatalf("неизвестная платформа: ожидался 204, получен %d", status)
	}

	// Платформа настроена наполовину — ссылки нет — это тоже 204: предложение без
	// пакета отправляет человека в никуда.
	srv.AppVerWin.PackageURL = ""
	if status, _ = спросить("?platform=windows"); status != http.StatusNoContent {
		t.Fatalf("windows без ссылки: ожидался 204, получен %d", status)
	}
}

// TestAppVersionПорогСовместимости — поле min_client (О5, задача С3.1).
//
// Проверяется, что порог доезжает до клиента и что БЕЗ него поля в ответе нет вовсе:
// `omitempty` здесь не украшение. Клиент читает отсутствие поля как «гейта нет», и
// нулевой порог в ответе означал бы то же самое — но лишний ноль в JSON однажды
// прочитают как «порог задан и равен нулю».
func TestAppVersionПорогСовместимости(t *testing.T) {
	ts, srv := setup(t)
	srv.AppVer = &AppVersion{
		VersionCode: 9,
		VersionName: "2.0.9-dev",
		PackageURL:  "https://api.example.com/download/TIMA-9.apk",
		Stream:      "v2",
	}

	тело := func() string {
		t.Helper()
		resp, err := http.Get(ts.URL + "/api/v1/app/version")
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		raw, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatal(err)
		}
		return string(raw)
	}

	if strings.Contains(тело(), "min_client") {
		t.Fatalf("порог не задан, а поле в ответе есть: %s", тело())
	}

	srv.AppVer.MinClient = 7
	var got AppVersion
	if err := json.Unmarshal([]byte(тело()), &got); err != nil {
		t.Fatal(err)
	}
	if got.MinClient != 7 {
		t.Fatalf("порог не доехал: %+v", got)
	}
}
