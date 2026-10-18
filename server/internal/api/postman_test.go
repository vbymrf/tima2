package api

// Почтальон (Решения §6, ПЛАН-(ПФ) ПФ4): файл вне пузыря в личной переписке живёт, пока его не
// заберут. Первое скачивание получателем в течение часа — до конца часа; позже — сразу после;
// никто — неделя. Удалённый по сроку — 410; опоздавший просит снова, у кого файл есть — загружает.

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	"tima/server/internal/blob"
	"tima/server/internal/worker"
)

func TestPostman(t *testing.T) {
	ts, srv := setupWithBlob(t)
	a := registerDevice(t, ts, "+79991110501")
	b := registerDevice(t, ts, "+79991110502")
	stranger := registerDevice(t, ts, "+79991110503")
	db := testDB(t)
	chat := personalChatID(a.userID, b.userID)

	initPostman := func(dev *device, peer string, chatID string, size int) (int, string, []string) {
		var resp struct {
			MediaID    string   `json:"media_id"`
			UploadURLs []string `json:"upload_urls"`
		}
		code := authedJSON(t, ts, "POST", "/api/v1/media/init", dev.token, map[string]any{
			"size_bytes": size, "mime": "application/pdf", "is_encrypted": true,
			"postman": map[string]string{"chat_id": chatID, "peer_id": peer},
		}, &resp)
		return code, resp.MediaID, resp.UploadURLs
	}

	// Пределы: другому — до 10 МБ, себе — до 50; переписка — только своя.
	if code, _, _ := initPostman(a, b.userID, chat, 11<<20); code != http.StatusBadRequest {
		t.Fatalf("11 МБ другому: ожидался 400, получен %d", code)
	}
	if code, _, _ := initPostman(a, a.userID, personalChatID(a.userID, a.userID), 20<<20); code != http.StatusCreated {
		t.Fatalf("20 МБ себе: %d", code)
	}
	if code, _, _ := initPostman(a, b.userID, personalChatID(a.userID, stranger.userID), 100); code != http.StatusBadRequest {
		t.Fatalf("чужая переписка: ожидался 400, получен %d", code)
	}

	file := []byte("зашифрованный файл почтальона")
	code, id, urls := initPostman(a, b.userID, chat, len(file))
	if code != http.StatusCreated {
		t.Fatalf("init: %d", code)
	}
	httpPut(t, urls[0], file)
	if code := authedJSON(t, ts, "POST", "/api/v1/media/complete", a.token, map[string]string{"media_id": id}, nil); code != http.StatusOK {
		t.Fatalf("complete: %d", code)
	}
	expiry := func() (time.Time, time.Time) {
		var up, exp time.Time
		if err := db.QueryRow(context.Background(), `SELECT uploaded_at, expires_at FROM media_objects WHERE media_id = $1`, id).Scan(&up, &exp); err != nil {
			t.Fatal(err)
		}
		return up, exp
	}
	if up, exp := expiry(); exp.Sub(up) < 6*24*time.Hour {
		t.Fatalf("до скачивания срок — неделя, а не %s", exp.Sub(up))
	}

	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", stranger.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("посторонний: ожидался 404, получен %d", code)
	}
	// Своё устройство отправителя срок не трогает.
	authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", a.token, nil, nil)
	if up, exp := expiry(); exp.Sub(up) < 6*24*time.Hour {
		t.Fatal("скачивание отправителем поставило срок")
	}
	// Получатель в течение часа — до конца часа; отправителю — «забрало».
	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", b.token, nil, nil); code != http.StatusOK {
		t.Fatalf("получатель: %d", code)
	}
	if up, exp := expiry(); exp.Sub(up) != time.Hour {
		t.Fatalf("первое скачивание в течение часа — срок час от загрузки, а не %s", exp.Sub(up))
	}
	if n := eventsOf(t, srv, a.id, "media.fetched"); n != 1 {
		t.Fatalf("отправителю событий «забрало» %d, ожидалось 1", n)
	}

	// Срок вышел — уборщик удаляет объект, строка gone, ссылка — 410.
	if _, err := db.Exec(context.Background(), `UPDATE media_objects SET expires_at = now() - interval '1 second' WHERE media_id = $1`, id); err != nil {
		t.Fatal(err)
	}
	w := &worker.Worker{Store: srv.Store, Blobs: srv.Blob}
	if n, err := w.SweepPostman(context.Background()); err != nil || n != 1 {
		t.Fatalf("уборка: %d, %v", n, err)
	}
	if _, err := srv.Blob.Size(context.Background(), "postman/"+id); !errors.Is(err, blob.ErrNotFound) {
		t.Fatalf("объект после уборки: %v", err)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", b.token, nil, nil); code != http.StatusGone {
		t.Fatalf("удалённый: ожидался 410, получен %d", code)
	}

	// Опоздавшее устройство просит снова — событие тем, у кого файл может быть.
	if code := authedJSON(t, ts, "POST", "/api/v1/media/"+id+"/request", b.token, nil, nil); code != http.StatusAccepted {
		t.Fatalf("просьба: %d", code)
	}
	if n := eventsOf(t, srv, a.id, "media.wanted"); n != 1 {
		t.Fatalf("отправителю просьб %d, ожидалась 1", n)
	}
	// Загружает снова получатель — у него файл есть; дальше — как новый, неделя.
	var re struct {
		UploadURLs []string `json:"upload_urls"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/"+id+"/reupload", b.token, nil, &re); code != http.StatusOK || len(re.UploadURLs) != 1 {
		t.Fatalf("повторная загрузка: %d", code)
	}
	httpPut(t, re.UploadURLs[0], file)
	if code := authedJSON(t, ts, "POST", "/api/v1/media/complete", b.token, map[string]string{"media_id": id}, nil); code != http.StatusOK {
		t.Fatalf("complete получателем: %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", a.token, nil, nil); code != http.StatusOK {
		t.Fatalf("после повторной загрузки: %d", code)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/"+id+"/reupload", stranger.token, nil, nil); code != http.StatusConflict {
		t.Fatalf("посторонний не загружает: %d", code)
	}

	// Позже часа — уходит сразу после скачивания (как только истечёт ссылка).
	if _, err := db.Exec(context.Background(), `UPDATE media_objects SET uploaded_at = now() - interval '2 hours' WHERE media_id = $1`, id); err != nil {
		t.Fatal(err)
	}
	authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", b.token, nil, nil)
	if _, exp := expiry(); time.Until(exp) > downloadURLTTL+time.Minute {
		t.Fatalf("скачивание позже часа — уходит со ссылкой, а срок через %s", time.Until(exp))
	}
}
