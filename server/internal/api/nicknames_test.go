package api

// Ник (ПЛАН-КОНТАКТОВ.md, Д1).
//
// Проверяются не ответы ручек, а четыре обещания, на которых ник держится:
// он один на всех, регистр не создаёт второго, короткие зарезервированы, и он
// переживает смену личности — иначе ссылка на человека обрывается там, где он
// как раз и остался собой.

import (
	"net/http"
	"net/http/httptest"
	"testing"
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

func TestНикСменяетсяИСтарыйНеОсвобождается(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000010")
	анна := registerDevice(t, ts, "+79990000011")

	if code := занятьНик(t, ts, пётр.token, "pervyy_nik_00"); code != http.StatusOK {
		t.Fatalf("первый ник: %d", code)
	}
	if code := занятьНик(t, ts, пётр.token, "vtoroy_nik_000"); code != http.StatusOK {
		t.Fatalf("смена ника: %d", code)
	}
	// Смена меняет ник, а не заводит второй: по прежнему уже никого нет.
	if code, _ := поНику(t, ts, анна.token, "pervyy_nik_00"); code != http.StatusNotFound {
		t.Fatalf("прежний ник всё ещё ведёт к человеку: %d", code)
	}
	code, id := поНику(t, ts, анна.token, "vtoroy_nik_000")
	if code != http.StatusOK || id != пётр.userID {
		t.Fatalf("новый ник не ведёт к человеку: %d %s", code, id)
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
