package api

import (
	"crypto/ed25519"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Обновление токена подписью устройства.
//
// Проверяется то, на чём эта ручка держится: подпись сходится только у своего устройства,
// просроченная метка не принимается, а отозванное устройство токена не получает — иначе
// отзыв перестал бы что-либо значить.

func обновитьТокен(t *testing.T, ts *httptest.Server, body map[string]any) (int, map[string]string) {
	t.Helper()
	answer := map[string]string{}
	status := postJSON(t, ts, "/api/v1/auth/device/token", body, &answer)
	return status, answer
}

// подписьУстройства собирает те же байты, что и сервер, и подписывает их.
func подписьУстройства(key ed25519.PrivateKey, userID, deviceID string, at int64) string {
	return base64.RawURLEncoding.EncodeToString(
		ed25519.Sign(key, deviceTokenSigningBytes(userID, deviceID, at)),
	)
}

func TestТокенОбновляетсяПодписьюУстройства(t *testing.T) {
	ts, _ := setup(t)
	d := registerDevice(t, ts, "+70000000911")

	now := time.Now().Unix()
	status, answer := обновитьТокен(t, ts, map[string]any{
		"user_id":   d.userID,
		"device_id": d.id,
		"issued_at": now,
		"signature": подписьУстройства(d.signKey, d.userID, d.id, now),
	})

	if status != http.StatusOK {
		t.Fatalf("своё устройство обязано получить токен: %d %v", status, answer)
	}
	if answer["access_token"] == "" {
		t.Fatal("токен не вернулся — обновлять нечем")
	}

	// Новый токен обязан работать: иначе обновление ничего не чинит.
	req, err := http.NewRequest(http.MethodGet, ts.URL+"/api/v1/devices", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+answer["access_token"])
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("выданный токен не принимается: %d", resp.StatusCode)
	}
}

func TestЧужаяПодписьТокенаНеДаёт(t *testing.T) {
	ts, _ := setup(t)
	свой := registerDevice(t, ts, "+70000000912")
	чужой := registerDevice(t, ts, "+70000000913")

	now := time.Now().Unix()
	// Подпись чужим ключом под своими идентификаторами — ровно то, что должен
	// отсекать этот механизм.
	status, answer := обновитьТокен(t, ts, map[string]any{
		"user_id":   свой.userID,
		"device_id": свой.id,
		"issued_at": now,
		"signature": подписьУстройства(чужой.signKey, свой.userID, свой.id, now),
	})

	if status != http.StatusUnauthorized {
		t.Fatalf("чужая подпись обязана получить отказ: %d", status)
	}
	if answer["code"] != "bad_signature" {
		t.Fatalf("код отказа обязан называть причину: %q", answer["code"])
	}
}

func TestСтараяПодписьНеПринимается(t *testing.T) {
	ts, _ := setup(t)
	d := registerDevice(t, ts, "+70000000914")

	// Час назад: подпись без срока годилась бы вечно, и перехваченная однажды давала
	// бы токены всегда.
	old := time.Now().Add(-time.Hour).Unix()
	status, answer := обновитьТокен(t, ts, map[string]any{
		"user_id":   d.userID,
		"device_id": d.id,
		"issued_at": old,
		"signature": подписьУстройства(d.signKey, d.userID, d.id, old),
	})

	if status != http.StatusUnauthorized || answer["code"] != "stale_signature" {
		t.Fatalf("просроченная подпись: %d %v", status, answer)
	}
}
