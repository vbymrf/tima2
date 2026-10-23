package api

// Файлы на срок — голосовые (ПЛАН-(ВМ) Р3, Р20): сервер держит файл срок из правил от загрузки,
// скачиваний не считает. Личная — отправителю и собеседнику; группа — её участникам. Срок вышел —
// 410, повторной загрузки нет.

import (
	"context"
	"net/http"
	"testing"
	"time"

	"tima/server/internal/worker"
)

func TestTermMedia(t *testing.T) {
	ts, srv := setupWithBlob(t)
	a := registerDevice(t, ts, "+79991110601")
	b := registerDevice(t, ts, "+79991110602")
	stranger := registerDevice(t, ts, "+79991110603")
	db := testDB(t)
	chat := personalChatID(a.userID, b.userID)

	initTerm := func(dev *device, term map[string]string, size int) (int, string, []string) {
		var resp struct {
			MediaID    string   `json:"media_id"`
			UploadURLs []string `json:"upload_urls"`
		}
		code := authedJSON(t, ts, "POST", "/api/v1/media/init", dev.token, map[string]any{
			"size_bytes": size, "mime": "audio/ogg", "is_encrypted": true, "term": term,
		}, &resp)
		return code, resp.MediaID, resp.UploadURLs
	}
	upload := func(dev *device, term map[string]string) string {
		t.Helper()
		file := []byte("зашифрованное голосовое")
		code, id, urls := initTerm(dev, term, len(file))
		if code != http.StatusCreated {
			t.Fatalf("init: %d", code)
		}
		httpPut(t, urls[0], file)
		if code := authedJSON(t, ts, "POST", "/api/v1/media/complete", dev.token, map[string]string{"media_id": id}, nil); code != http.StatusOK {
			t.Fatalf("complete: %d", code)
		}
		return id
	}

	// Правила называют срок — неделя по умолчанию.
	var rules struct {
		TermSec int64 `json:"term_sec"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/media/rules", a.token, nil, &rules); code != http.StatusOK || rules.TermSec != 7*24*3600 {
		t.Fatalf("правила: %d, срок %d", code, rules.TermSec)
	}

	// Пределы и чужая переписка.
	personal := map[string]string{"chat_id": chat, "peer_id": b.userID}
	if code, _, _ := initTerm(a, personal, 11<<20); code != http.StatusBadRequest {
		t.Fatalf("11 МБ: ожидался 400, получен %d", code)
	}
	if code, _, _ := initTerm(a, map[string]string{"chat_id": personalChatID(a.userID, stranger.userID), "peer_id": b.userID}, 100); code != http.StatusBadRequest {
		t.Fatalf("чужая переписка: ожидался 400, получен %d", code)
	}

	// Личная: срок — неделя от загрузки; отдаётся обоим и сколько угодно раз, постороннему — нет.
	id := upload(a, personal)
	var up, exp time.Time
	if err := db.QueryRow(context.Background(), `SELECT uploaded_at, expires_at FROM media_objects WHERE media_id = $1`, id).Scan(&up, &exp); err != nil {
		t.Fatal(err)
	}
	if d := exp.Sub(up); d < 7*24*time.Hour-time.Minute || d > 7*24*time.Hour+time.Minute {
		t.Fatalf("срок — неделя, а не %s", d)
	}
	for i := 0; i < 2; i++ {
		for _, dev := range []*device{a, b} {
			if code := authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", dev.token, nil, nil); code != http.StatusOK {
				t.Fatalf("скачивание участником: %d", code)
			}
		}
	}
	var exp2 time.Time
	if err := db.QueryRow(context.Background(), `SELECT expires_at FROM media_objects WHERE media_id = $1`, id).Scan(&exp2); err != nil {
		t.Fatal(err)
	}
	if !exp2.Equal(exp) {
		t.Fatal("скачивание сдвинуло срок — скачивания не считаются")
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", stranger.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("посторонний: ожидался 404, получен %d", code)
	}
	if n := eventsOf(t, srv, a.id, "media.fetched"); n != 0 {
		t.Fatalf("у файла на срок галочек «забрали» нет, а событий %d", n)
	}

	// Группа: загружает и скачивает участник; посторонний — нет; ушёл из группы — тоже нет.
	group := createGroupWith(t, ts, a, b)
	if code, _, _ := initTerm(stranger, map[string]string{"group_id": group}, 100); code != http.StatusForbidden {
		t.Fatalf("загрузка не участником: ожидался 403, получен %d", code)
	}
	gid := upload(b, map[string]string{"group_id": group})
	var batch struct {
		Items   []map[string]any `json:"items"`
		Missing []string         `json:"missing"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/urls", a.token, map[string]any{"media_ids": []string{gid}}, &batch); code != http.StatusOK || len(batch.Items) != 1 {
		t.Fatalf("пачкой участнику: %d, %d", code, len(batch.Items))
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/urls", stranger.token, map[string]any{"media_ids": []string{gid}}, &batch); code != http.StatusOK || len(batch.Missing) != 1 {
		t.Fatalf("пачкой постороннему: %d, пропало %d", code, len(batch.Missing))
	}
	if _, err := db.Exec(context.Background(), `UPDATE memberships SET left_at = now() WHERE target_id = $1 AND user_id = $2`, group, b.userID); err != nil {
		t.Fatal(err)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+gid+"/url", b.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("ушедший из группы: ожидался 404, получен %d", code)
	}

	// Срок вышел — уборщик удаляет, отдача — 410, повторной загрузки нет.
	if _, err := db.Exec(context.Background(), `UPDATE media_objects SET expires_at = now() - interval '1 second' WHERE media_id = $1`, id); err != nil {
		t.Fatal(err)
	}
	w := &worker.Worker{Store: srv.Store, Blobs: srv.Blob}
	if n, err := w.SweepPostman(context.Background()); err != nil || n != 1 {
		t.Fatalf("уборка: %d, %v", n, err)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/media/"+id+"/url", b.token, nil, nil); code != http.StatusGone {
		t.Fatalf("удалённый: ожидался 410, получен %d", code)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/media/"+id+"/reupload", a.token, nil, nil); code != http.StatusConflict {
		t.Fatalf("повторной загрузки у файла на срок нет: %d", code)
	}
}
