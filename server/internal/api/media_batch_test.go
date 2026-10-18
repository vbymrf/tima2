package api

// Ссылки пачкой и квота загрузок (Решения §14): до 100 номеров одним запросом, незавершённое —
// в `missing`; заявленное за сутки больше квоты — 429.

import (
	"context"
	"net/http"
	"testing"

	"tima/server/internal/store"
)

func TestMediaURLsBatch(t *testing.T) {
	ts, _ := setupWithBlob(t)
	user := registerDevice(t, ts, "+79991110301")
	upload := func(data []byte) string {
		var init struct {
			MediaID    string   `json:"media_id"`
			UploadURLs []string `json:"upload_urls"`
		}
		if code := authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{
			"size_bytes": len(data), "mime": "image/jpeg", "is_encrypted": true,
		}, &init); code != http.StatusCreated {
			t.Fatalf("init: %d", code)
		}
		httpPut(t, init.UploadURLs[0], data)
		if code := authedJSON(t, ts, "POST", "/api/v1/media/complete", user.token, map[string]string{"media_id": init.MediaID}, nil); code != http.StatusOK {
			t.Fatalf("complete: %d", code)
		}
		return init.MediaID
	}
	a, b := upload([]byte("первая")), upload([]byte("вторая"))
	var pending struct {
		MediaID string `json:"media_id"`
	}
	authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{"size_bytes": 5, "mime": "image/jpeg", "is_encrypted": true}, &pending)

	var resp struct {
		Items []struct {
			MediaID string   `json:"media_id"`
			URLs    []string `json:"urls"`
		} `json:"items"`
		Missing []string `json:"missing"`
	}
	ghost := "00000000-0000-0000-0000-000000000000"
	if code := authedJSON(t, ts, "POST", "/api/v1/media/urls", user.token, map[string]any{"media_ids": []string{a, b, pending.MediaID, ghost}}, &resp); code != http.StatusOK {
		t.Fatalf("media/urls: %d", code)
	}
	if len(resp.Items) != 2 || resp.Items[0].MediaID != a || string(httpGet(t, resp.Items[1].URLs[0])) != "вторая" {
		t.Fatalf("пачка: %+v", resp.Items)
	}
	if len(resp.Missing) != 2 {
		t.Fatalf("незавершённое и пропавшее — в missing: %v", resp.Missing)
	}
	tooMany := make([]string, 101)
	for i := range tooMany {
		tooMany[i] = a
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/urls", user.token, map[string]any{"media_ids": tooMany}, nil); code != http.StatusBadRequest {
		t.Fatalf("больше 100: ожидался 400, получен %d", code)
	}
}

func TestMediaUploadQuota(t *testing.T) {
	ts, srv := setupWithBlob(t)
	user := registerDevice(t, ts, "+79991110302")
	// Сутки уже почти исчерпаны: заявлено 1 ГБ без 10 байт.
	if _, err := srv.Store.CreateMedia(context.Background(), store.Media{OwnerID: user.userID, Mime: "x", SizeBytes: 1<<30 - 10, ChunkCount: 1}, nil); err != nil {
		t.Fatal(err)
	}
	init := func(size int) int {
		return authedJSON(t, ts, "POST", "/api/v1/media/init", user.token, map[string]any{"size_bytes": size, "mime": "x", "is_encrypted": true}, nil)
	}
	if code := init(10); code != http.StatusCreated {
		t.Fatalf("ровно до квоты: %d", code)
	}
	if code := init(1); code != http.StatusTooManyRequests {
		t.Fatalf("сверх квоты: ожидался 429, получен %d", code)
	}
}
