package api

// Ник (ПЛАН-КОНТАКТОВ.md, Д1).
//
// Проверяются не ответы ручек, а четыре обещания, на которых ник держится:
// он один на всех, регистр не создаёт второго, короткие зарезервированы, он
// переживает смену личности — иначе ссылка на человека обрывается там, где он
// как раз и остался собой, — и задаётся один раз на личность (0050).

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"tima/server/internal/store"
)

func занятьНик(t *testing.T, ts *httptest.Server, token, nick string) int {
	t.Helper()
	return authedJSON(t, ts, "PATCH", "/api/v1/users/me/nickname", token,
		map[string]any{"nickname": nick}, nil)
}

func свободенЛи(t *testing.T, ts *httptest.Server, token, nick string) (int, bool) {
	t.Helper()
	var resp struct {
		Free bool `json:"free"`
	}
	code := authedJSON(t, ts, "GET", "/api/v1/nicknames/"+nick+"/free", token, nil, &resp)
	return code, resp.Free
}

func поНику(t *testing.T, ts *httptest.Server, token, nick string) (int, string) {
	t.Helper()
	var resp struct {
		UserID string `json:"user_id"`
	}
	code := authedJSON(t, ts, "GET", "/api/v1/nicknames/"+nick, token, nil, &resp)
	return code, resp.UserID
}

func TestНикЗанимаетсяИНаходится(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000001")
	анна := registerDevice(t, ts, "+79990000002")

	if code := занятьНик(t, ts, пётр.token, "petr_smirnov"); code != http.StatusOK {
		t.Fatalf("занять ник: %d", code)
	}
	code, id := поНику(t, ts, анна.token, "petr_smirnov")
	if code != http.StatusOK {
		t.Fatalf("поиск по нику: %d", code)
	}
	if id != пётр.userID {
		t.Fatalf("ник ведёт не к тому: %s вместо %s", id, пётр.userID)
	}
}

func TestНикНеЗанимаетсяДважды(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000003")
	анна := registerDevice(t, ts, "+79990000004")

	if code := занятьНик(t, ts, пётр.token, "odin_na_dvoih"); code != http.StatusOK {
		t.Fatalf("первый занял: %d", code)
	}
	if code := занятьНик(t, ts, анна.token, "odin_na_dvoih"); code != http.StatusConflict {
		t.Fatalf("второму отдали занятый ник: %d", code)
	}
}

func TestРегистрНеДелаетВторойНик(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000005")
	анна := registerDevice(t, ts, "+79990000006")

	if code := занятьНик(t, ts, пётр.token, "petrovich_77"); code != http.StatusOK {
		t.Fatalf("занять: %d", code)
	}
	// Ровно та подмена, ради которой уникальность и сделана без учёта регистра.
	if code := занятьНик(t, ts, анна.token, "Petrovich_77"); code != http.StatusConflict {
		t.Fatalf("ник в другом регистре достался второму: %d", code)
	}
	code, id := поНику(t, ts, анна.token, "PETROVICH_77")
	if code != http.StatusOK || id != пётр.userID {
		t.Fatalf("поиск не нашёл ник в другом регистре: %d %s", code, id)
	}
}

func TestКороткийНикОтвергается(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000007")

	// Девять знаков — короткие зарезервированы.
	if code := занятьНик(t, ts, пётр.token, "petr12345"); code != http.StatusBadRequest {
		t.Fatalf("короткий ник приняли: %d", code)
	}
	if code := занятьНик(t, ts, пётр.token, "оченьдлинноеимя"); code != http.StatusBadRequest {
		t.Fatalf("кириллицу приняли: %d", code)
	}
	if code := занятьНик(t, ts, пётр.token, "petr smirnov"); code != http.StatusBadRequest {
		t.Fatalf("пробел приняли: %d", code)
	}
	if code := занятьНик(t, ts, пётр.token, "petr_smirnov_ochen_dlinnyy"); code != http.StatusBadRequest {
		t.Fatalf("двадцать шесть знаков приняли: %d", code)
	}
}

func TestЗанятостьОтвечаетДоСохранения(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000008")
	анна := registerDevice(t, ts, "+79990000009")

	code, free := свободенЛи(t, ts, анна.token, "svobodnyy_nik")
	if code != http.StatusOK || !free {
		t.Fatalf("незанятый ник назван занятым: %d %v", code, free)
	}
	if code := занятьНик(t, ts, пётр.token, "svobodnyy_nik"); code != http.StatusOK {
		t.Fatalf("занять: %d", code)
	}
	code, free = свободенЛи(t, ts, анна.token, "svobodnyy_nik")
	if code != http.StatusOK || free {
		t.Fatalf("занятый ник назван свободным: %d %v", code, free)
	}
}

func TestНикЗадаётсяОдинРазНаЛичность(t *testing.T) {
	// Решение заказчика 2026-09-15 (0050): ник — то, чем человека находят, и менять
	// его по настроению значит рвать чужие ссылки. Один раз на личность.
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000010")

	if code := занятьНик(t, ts, пётр.token, "pervyy_nik_00"); code != http.StatusOK {
		t.Fatalf("первый ник: %d", code)
	}
	var resp struct {
		Error string `json:"code"`
	}
	code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/nickname", пётр.token,
		map[string]any{"nickname": "vtoroy_nik_000"}, &resp)
	if code != http.StatusConflict || resp.Error != "nickname_locked" {
		t.Fatalf("вторая попытка той же личностью должна быть 409 nickname_locked, а не %d %q", code, resp.Error)
	}
	// Прежний ник на месте: отказ ничего не тронул.
	if code, id := поНику(t, ts, пётр.token, "pervyy_nik_00"); code != http.StatusOK || id != пётр.userID {
		t.Fatalf("после отказа ник потерян: %d %s", code, id)
	}
}

func TestНоваяЛичностьМожетСменитьНикОдинРаз(t *testing.T) {
	// «Начать заново» заводит новую личность — и у неё право на ник появляется снова.
	// Не воспользовалась — прежний ник остаётся: он принадлежит аккаунту, а не фразе.
	ts, srv := setup(t)
	ctx := context.Background()
	пётр := registerDevice(t, ts, "+79990000010")
	анна := registerDevice(t, ts, "+79990000011")

	if code := занятьНик(t, ts, пётр.token, "pervyy_nik_00"); code != http.StatusOK {
		t.Fatalf("первый ник: %d", code)
	}
	person, err := srv.Store.PersonOfUser(ctx, пётр.userID)
	if err != nil {
		t.Fatal(err)
	}
	second, err := srv.Store.StartNewIdentity(ctx, person, пётр.userID, []byte("proof"))
	if err != nil {
		t.Fatal(err)
	}

	// Пока новая личность ничего не решила — ник прежний, и ведёт он к аккаунту.
	if code, _ := поНику(t, ts, анна.token, "pervyy_nik_00"); code != http.StatusOK {
		t.Fatalf("после смены личности ник пропал: %d", code)
	}

	// Новая личность меняет — можно, ровно один раз.
	if err := srv.Store.SetNickname(ctx, second, "vtoroy_nik_000"); err != nil {
		t.Fatalf("новая личность не смогла сменить ник: %v", err)
	}
	if err := srv.Store.SetNickname(ctx, second, "tretiy_nik_0000"); !errors.Is(err, store.ErrNicknameLocked) {
		t.Fatalf("вторая смена той же новой личностью должна быть заперта, а вышло: %v", err)
	}
	// Смена меняет ник, а не заводит второй: по прежнему уже никого нет.
	if code, _ := поНику(t, ts, анна.token, "pervyy_nik_00"); code != http.StatusNotFound {
		t.Fatalf("прежний ник всё ещё ведёт к человеку: %d", code)
	}
}

func TestКтоЯ(t *testing.T) {
	// GET /users/me — то, чем заполняется экран профиля. До ручки он открывался пустым.
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000010")

	var me struct {
		Phone          string `json:"phone"`
		DisplayName    string `json:"display_name"`
		Nickname       string `json:"nickname"`
		NicknameLocked bool   `json:"nickname_locked"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me", пётр.token, nil, &me); code != http.StatusOK {
		t.Fatalf("me: %d", code)
	}
	if me.Phone != "+79990000010" {
		t.Fatalf("свой телефон не отдан: %q", me.Phone)
	}
	if me.Nickname != "" || me.NicknameLocked {
		t.Fatalf("у нового аккаунта ник должен быть пуст и не заперт: %q %v", me.Nickname, me.NicknameLocked)
	}

	if code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/name", пётр.token,
		map[string]any{"display_name": "Пётр"}, nil); code != http.StatusOK {
		t.Fatalf("имя: %d", code)
	}
	if code := занятьНик(t, ts, пётр.token, "pervyy_nik_00"); code != http.StatusOK {
		t.Fatalf("ник: %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me", пётр.token, nil, &me); code != http.StatusOK {
		t.Fatalf("me второй раз: %d", code)
	}
	if me.DisplayName != "Пётр" || me.Nickname != "pervyy_nik_00" || !me.NicknameLocked {
		t.Fatalf("после правок me врёт: %+v", me)
	}
}

func TestНикВидноВСпискеИмён(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000012")
	анна := registerDevice(t, ts, "+79990000013")

	if code := занятьНик(t, ts, пётр.token, "vidnyy_v_spiske"); code != http.StatusOK {
		t.Fatalf("занять: %d", code)
	}
	var resp struct {
		Nicknames map[string]string `json:"nicknames"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/users/names", анна.token,
		map[string]any{"ids": []string{пётр.userID}}, &resp)
	if code != http.StatusOK {
		t.Fatalf("имена: %d", code)
	}
	if resp.Nicknames[пётр.userID] != "vidnyy_v_spiske" {
		t.Fatalf("ника нет в ответе: %v", resp.Nicknames)
	}
	// У кого ника нет — того нет и в карте: иначе клиент не отличит «ника нет»
	// от «сервер не ответил».
	if _, есть := resp.Nicknames[анна.userID]; есть {
		t.Fatalf("пустой ник попал в ответ: %v", resp.Nicknames)
	}
}

func TestАватарТолькоСвойИЗавершённый(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	пётр := registerDevice(t, ts, "+79990000010")
	анна := registerDevice(t, ts, "+79990000011")

	// Чужое медиа, пусть и завершённое, — 403.
	чужое, err := srv.Store.CreateMedia(ctx, store.Media{OwnerID: анна.userID, Mime: "image/jpeg", SizeBytes: 10, ChunkCount: 1}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.CompleteMedia(ctx, чужое.MediaID, анна.userID, 10); err != nil {
		t.Fatal(err)
	}
	if code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/avatar", пётр.token,
		map[string]any{"media_id": чужое.MediaID}, nil); code != http.StatusForbidden {
		t.Fatalf("чужое медиа стало аватаром: %d", code)
	}

	// Своё, но незавершённое — тоже 403: картинки ещё нет.
	своё, err := srv.Store.CreateMedia(ctx, store.Media{OwnerID: пётр.userID, Mime: "image/jpeg", SizeBytes: 10, ChunkCount: 1}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/avatar", пётр.token,
		map[string]any{"media_id": своё.MediaID}, nil); code != http.StatusForbidden {
		t.Fatalf("незавершённое медиа стало аватаром: %d", code)
	}

	// Завершённое своё — принимается и видно в «кто я».
	if err := srv.Store.CompleteMedia(ctx, своё.MediaID, пётр.userID, 10); err != nil {
		t.Fatal(err)
	}
	if code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/avatar", пётр.token,
		map[string]any{"media_id": своё.MediaID}, nil); code != http.StatusOK {
		t.Fatalf("своё завершённое не принято: %d", code)
	}
	var me struct {
		Avatar string `json:"avatar_media_id"`
	}
	authedJSON(t, ts, "GET", "/api/v1/users/me", пётр.token, nil, &me)
	if me.Avatar != своё.MediaID {
		t.Fatalf("аватар не отдан в me: %q", me.Avatar)
	}

	// Пустой — убирает.
	if code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/avatar", пётр.token,
		map[string]any{"media_id": ""}, nil); code != http.StatusOK {
		t.Fatalf("убрать аватар: %d", code)
	}
	authedJSON(t, ts, "GET", "/api/v1/users/me", пётр.token, nil, &me)
	if me.Avatar != "" {
		t.Fatalf("аватар не убран: %q", me.Avatar)
	}
}
