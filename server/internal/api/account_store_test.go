package api

import (
	"encoding/base64"
	"testing"
)

// Копия личных данных аккаунта (ПЛАН-РАЗДЕЛОВ Р2а).
//
// Проверяется ровно то, ради чего ручки заведены: блоб уходит и возвращается тем же, ревизия
// растёт только на единицу, два устройства не затирают друг друга, а служебная группа одна
// на аккаунт. Что внутри блоба — сервер не знает, и тест этого не знает тоже.

func blobOf(t *testing.T, s string) string {
	t.Helper()
	return base64.RawURLEncoding.EncodeToString([]byte(s))
}

func TestКопияУходитИВозвращаетсяТойЖе(t *testing.T) {
	ts, _ := setup(t)
	me := registerDevice(t, ts, "+79990000240")

	var got struct {
		Revision int64  `json:"revision"`
		Blob     string `json:"blob"`
	}
	if code := getAuthed(t, ts, me.token, "/api/v1/users/me/store/book", &got); code != 204 {
		t.Fatalf("до первого сохранения ждём 204, получили %d", code)
	}
	if code := postAuthed(t, ts, me.token, "PUT", "/api/v1/users/me/store/book",
		map[string]any{"revision": 1, "blob": blobOf(t, "книга-1")}, nil); code != 200 {
		t.Fatalf("первое сохранение: %d", code)
	}
	if code := getAuthed(t, ts, me.token, "/api/v1/users/me/store/book", &got); code != 200 {
		t.Fatalf("чтение: %d", code)
	}
	if got.Revision != 1 || got.Blob != blobOf(t, "книга-1") {
		t.Fatalf("вернулось не то, что положили: %+v", got)
	}
}

func TestРевизияПринимаетсяТолькоСледующая(t *testing.T) {
	ts, _ := setup(t)
	me := registerDevice(t, ts, "+79990000241")

	// Первая обязана быть единицей: иначе второе устройство никогда не совпадёт по счёту.
	if code := postAuthed(t, ts, me.token, "PUT", "/api/v1/users/me/store/book",
		map[string]any{"revision": 3, "blob": blobOf(t, "x")}, nil); code != 409 {
		t.Fatalf("первая ревизия не 1 — ждём 409, получили %d", code)
	}
	for _, rev := range []int{1, 2} {
		if code := postAuthed(t, ts, me.token, "PUT", "/api/v1/users/me/store/book",
			map[string]any{"revision": rev, "blob": blobOf(t, "x")}, nil); code != 200 {
			t.Fatalf("ревизия %d: %d", rev, code)
		}
	}
	// Повтор той же ревизии — тот случай, когда второе устройство сохраняет по устаревшему
	// счёту. В ответе — текущее, чтобы не ходить дважды.
	var conflict struct {
		Code     string `json:"code"`
		Revision int64  `json:"revision"`
		Blob     string `json:"blob"`
	}
	if code := postAuthed(t, ts, me.token, "PUT", "/api/v1/users/me/store/book",
		map[string]any{"revision": 2, "blob": blobOf(t, "устаревшее")}, &conflict); code != 409 {
		t.Fatalf("устаревшая ревизия — ждём 409, получили %d", code)
	}
	if conflict.Code != "revision_conflict" || conflict.Revision != 2 || conflict.Blob != blobOf(t, "x") {
		t.Fatalf("в отказе нет текущего: %+v", conflict)
	}
	// Перескок через ревизию — тоже отказ.
	if code := postAuthed(t, ts, me.token, "PUT", "/api/v1/users/me/store/book",
		map[string]any{"revision": 5, "blob": blobOf(t, "x")}, nil); code != 409 {
		t.Fatalf("перескок — ждём 409, получили %d", code)
	}
}

func TestНеизвестныйВидКопииНеЗаводится(t *testing.T) {
	ts, _ := setup(t)
	me := registerDevice(t, ts, "+79990000242")
	if code := postAuthed(t, ts, me.token, "PUT", "/api/v1/users/me/store/pasport",
		map[string]any{"revision": 1, "blob": blobOf(t, "x")}, nil); code != 404 {
		t.Fatalf("неизвестный вид — ждём 404, получили %d", code)
	}
}

func TestСлужебнаяГруппаОднаНаАккаунт(t *testing.T) {
	ts, _ := setup(t)
	me := registerDevice(t, ts, "+79990000243")

	var first, second struct {
		GroupID string `json:"group_id"`
	}
	if code := getAuthed(t, ts, me.token, "/api/v1/users/me/store/group", &first); code != 200 || first.GroupID == "" {
		t.Fatalf("служебная группа: %d %+v", code, first)
	}
	if code := getAuthed(t, ts, me.token, "/api/v1/users/me/store/group", &second); code != 200 {
		t.Fatalf("повторно: %d", code)
	}
	if first.GroupID != second.GroupID {
		t.Fatalf("второй вопрос завёл вторую группу: %s ≠ %s", first.GroupID, second.GroupID)
	}
	// Группа настоящая: ручки ключей её знают — ротировать пока нечего, но версия 0 отдаётся.
	var keys struct {
		Current int `json:"current_version"`
	}
	if code := getAuthed(t, ts, me.token, "/api/v1/groups/"+first.GroupID+"/keys", &keys); code != 200 {
		t.Fatalf("ключи служебной группы: %d", code)
	}
	if keys.Current != 0 {
		t.Fatalf("у новой служебной группы версия ключа не 0: %d", keys.Current)
	}
}

func TestСлужебнаяГруппаНеВСпискеГрупп(t *testing.T) {
	ts, _ := setup(t)
	me := registerDevice(t, ts, "+79990000244")
	var g struct {
		GroupID string `json:"group_id"`
	}
	if code := getAuthed(t, ts, me.token, "/api/v1/users/me/store/group", &g); code != 200 {
		t.Fatalf("служебная группа: %d", code)
	}
	var mine struct {
		Groups []struct {
			GroupID string `json:"group_id"`
		} `json:"groups"`
	}
	if code := getAuthed(t, ts, me.token, "/api/v1/groups", &mine); code != 200 {
		t.Fatalf("список групп: %d", code)
	}
	for _, x := range mine.Groups {
		if x.GroupID == g.GroupID {
			t.Fatalf("служебная группа попала в список групп человека: %s", g.GroupID)
		}
	}
}
