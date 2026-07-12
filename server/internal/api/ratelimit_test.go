package api

// Интеграционный тест rate limiting auth (Redis из dev-compose): лимит SMS на
// телефон и лимит попыток verify на request_id (перебор 6-значного кода).
// Ключи изолируются случайным префиксом — прогоны не мешают друг другу.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"tima/server/internal/ratelimit"
)

func setupWithLimiter(t *testing.T) (*httptest.Server, *Server) {
	t.Helper()
	ts, srv := setup(t)
	redisURL := os.Getenv("TIMA_TEST_REDIS_URL")
	if redisURL == "" {
		redisURL = "redis://:tima-dev-only@localhost:6379"
	}
	limiter, err := ratelimit.New(context.Background(), redisURL)
	if err != nil {
		t.Skipf("Redis недоступен (%v) — подними deploy/docker-compose.dev.yml", err)
	}
	t.Cleanup(func() { limiter.Close() })
	var pfx [8]byte
	if _, err := rand.Read(pfx[:]); err != nil {
		t.Fatal(err)
	}
	limiter.Prefix = "test:" + hex.EncodeToString(pfx[:]) + ":"
	srv.Limit = limiter
	return ts, srv
}

func TestAuthRateLimits(t *testing.T) {
	ts, _ := setupWithLimiter(t)

	// SMS на телефон: 3 в окно, 4-я → 429 с Retry-After
	const phone = "+79998880001"
	for i := 1; i <= 3; i++ {
		if code := postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": phone}, nil); code != http.StatusOK {
			t.Fatalf("sms/request #%d: %d", i, code)
		}
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": phone}, nil); code != http.StatusTooManyRequests {
		t.Fatalf("4-я SMS: ожидался 429, получен %d", code)
	}
	// Другой телефон не задет лимитом первого
	var smsResp struct {
		RequestID string `json:"request_id"`
		DevCode   string `json:"dev_code"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": "+79998880002"}, &smsResp); code != http.StatusOK {
		t.Fatalf("SMS другому телефону: %d", code)
	}

	// Перебор кода: 5 попыток на request_id, 6-я → 429 даже с верным кодом
	for i := 1; i <= 5; i++ {
		if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
			map[string]string{"request_id": smsResp.RequestID, "code": "000000"}, nil); code != http.StatusForbidden {
			t.Fatalf("verify #%d с неверным кодом: ожидался 403, получен %d", i, code)
		}
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
		map[string]string{"request_id": smsResp.RequestID, "code": smsResp.DevCode}, nil); code != http.StatusTooManyRequests {
		t.Fatalf("6-я попытка verify: ожидался 429, получен %d", code)
	}
}
