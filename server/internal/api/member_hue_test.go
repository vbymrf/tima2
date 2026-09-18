package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// Счётчик профиля (0052) и цвет участника (0053) — решение заказчика 2026-09-19.

func revsOf(t *testing.T, ts *httptest.Server, token string, ids ...string) map[string]int32 {
	t.Helper()
	var resp struct {
		Revs map[string]int32 `json:"profile_revs"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/users/names", token,
		map[string]any{"ids": ids}, &resp); code != http.StatusOK {
		t.Fatalf("имена: %d", code)
	}
	return resp.Revs
}

func TestСчётчикПрофиляРастётНаИмениНикеИАватаре(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000020")
	анна := registerDevice(t, ts, "+79990000021")

	before := revsOf(t, ts, анна.token, пётр.userID)[пётр.userID]

	if code := jsonAuth(t, ts, "PATCH", "/api/v1/users/me/name", пётр.token,
		map[string]string{"display_name": "Пётр"}, nil); code != http.StatusOK {
		t.Fatalf("имя: %d", code)
	}
	afterName := revsOf(t, ts, анна.token, пётр.userID)[пётр.userID]
	if afterName != before+1 {
		t.Fatalf("смена имени: счётчик %d → %d, ожидался +1", before, afterName)
	}

	if code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/nickname", пётр.token,
		map[string]any{"nickname": "petr_schetchik"}, nil); code != http.StatusOK {
		t.Fatalf("ник: %d", code)
	}
	afterNick := revsOf(t, ts, анна.token, пётр.userID)[пётр.userID]
	if afterNick != afterName+1 {
		t.Fatalf("смена ника: счётчик %d → %d, ожидался +1", afterName, afterNick)
	}

	// Снятие аватара — тоже смена: у получателя картинка должна исчезнуть.
	if code := authedJSON(t, ts, "PATCH", "/api/v1/users/me/avatar", пётр.token,
		map[string]any{"media_id": ""}, nil); code != http.StatusOK {
		t.Fatalf("аватар: %d", code)
	}
	afterAvatar := revsOf(t, ts, анна.token, пётр.userID)[пётр.userID]
	if afterAvatar != afterNick+1 {
		t.Fatalf("снятие аватара: счётчик %d → %d, ожидался +1", afterNick, afterAvatar)
	}
	// Чужой счётчик не тронут.
	if revsOf(t, ts, пётр.token, анна.userID)[анна.userID] != 0 {
		t.Fatalf("счётчик Анны сдвинулся от правок Петра")
	}
}

func TestЦветУчастникаСтавитсяЗанятыйЗапрещёнСбрасывается(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000030")
	пётр := registerDevice(t, ts, "+79990000031")
	анна := registerDevice(t, ts, "+79990000032")
	чужой := registerDevice(t, ts, "+79990000033")
	groupID := createGroupWith(t, ts, owner, пётр, анна)
	path := "/api/v1/groups/" + groupID + "/members/me/color"

	// Пётр берёт №7.
	if code := authedJSON(t, ts, "PUT", path, пётр.token, map[string]any{"hue": 7}, nil); code != http.StatusNoContent {
		t.Fatalf("поставить цвет: %d", code)
	}
	// Анна тот же №7 — занят: участников трое, это меньше 80 % оттенков.
	if code := authedJSON(t, ts, "PUT", path, анна.token, map[string]any{"hue": 7}, nil); code != http.StatusConflict {
		t.Fatalf("занятый цвет принят: %d", code)
	}
	// Свой же №7 повторно — можно: это не «у другого».
	if code := authedJSON(t, ts, "PUT", path, пётр.token, map[string]any{"hue": 7}, nil); code != http.StatusNoContent {
		t.Fatalf("свой цвет повторно: %d", code)
	}
	// За краем и не участник.
	if code := authedJSON(t, ts, "PUT", path, анна.token, map[string]any{"hue": 100}, nil); code != http.StatusBadRequest {
		t.Fatalf("hue 100 принят: %d", code)
	}
	if code := authedJSON(t, ts, "PUT", path, чужой.token, map[string]any{"hue": 3}, nil); code != http.StatusNotFound {
		t.Fatalf("не участник поставил цвет: %d", code)
	}

	// В списке участников цвет виден всем, у не выбиравших поля нет.
	var members struct {
		Members []struct {
			UserID string `json:"user_id"`
			Hue    *int16 `json:"hue"`
		} `json:"members"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/members", анна.token, nil, &members); code != http.StatusOK {
		t.Fatalf("участники: %d", code)
	}
	seen := map[string]*int16{}
	for _, m := range members.Members {
		seen[m.UserID] = m.Hue
	}
	if seen[пётр.userID] == nil || *seen[пётр.userID] != 7 {
		t.Fatalf("цвет Петра не приехал в список: %v", seen[пётр.userID])
	}
	if seen[анна.userID] != nil {
		t.Fatalf("у Анны цвет, которого она не выбирала: %d", *seen[анна.userID])
	}

	// Сброс — и №7 снова свободен.
	if code := authedJSON(t, ts, "DELETE", path, пётр.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("сбросить цвет: %d", code)
	}
	if code := authedJSON(t, ts, "PUT", path, анна.token, map[string]any{"hue": 7}, nil); code != http.StatusNoContent {
		t.Fatalf("освободившийся цвет не принят: %d", code)
	}
}
