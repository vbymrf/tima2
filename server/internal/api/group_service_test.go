package api

// Интеграционные тесты Group Service: CRUD группы, membership и матрица прав
// (owner > admin > moderator > member), сцепка с API групповых ключей —
// в groups_test.go.

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// createGroupAPI создаёт private-группу, создатель становится owner.
func createGroupAPI(t *testing.T, ts *httptest.Server, token string) string {
	t.Helper()
	var resp struct {
		GroupID string `json:"group_id"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/groups", token,
		map[string]any{"kind": "private", "title": "тестовая группа"}, &resp)
	if code != http.StatusCreated || resp.GroupID == "" {
		t.Fatalf("создание группы: %d", code)
	}
	return resp.GroupID
}

func addMemberAPI(t *testing.T, ts *httptest.Server, adminToken, groupID, userID, role string) {
	t.Helper()
	code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members", adminToken,
		map[string]string{"user_id": userID, "role": role}, nil)
	if code != http.StatusCreated {
		t.Fatalf("добавление участника %s (%s): %d", userID, role, code)
	}
}

func TestGroupServiceCRUDAndRoles(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79994440001")
	admin := registerDevice(t, ts, "+79994440002")
	member := registerDevice(t, ts, "+79994440003")
	outsider := registerDevice(t, ts, "+79994440004")

	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, admin.userID, "admin")
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")

	// GET: участник видит группу и свою роль, не-участник private-группы — 404
	var info struct {
		Kind   string `json:"kind"`
		MyRole string `json:"my_role"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, member.token, nil, &info); code != http.StatusOK {
		t.Fatalf("GET группы участником: %d", code)
	}
	if info.Kind != "private" || info.MyRole != "member" {
		t.Fatalf("GET группы: kind=%s my_role=%s", info.Kind, info.MyRole)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, outsider.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("GET private-группы не-участником: ожидался 404, получен %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/не-uuid", owner.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("GET по мусорному id: ожидался 404, получен %d", code)
	}

	// Список участников: только участникам; в списке трое
	var members struct {
		Members []struct {
			UserID string `json:"user_id"`
			Role   string `json:"role"`
		} `json:"members"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/members", member.token, nil, &members); code != http.StatusOK {
		t.Fatalf("список участников: %d", code)
	}
	if len(members.Members) != 3 || members.Members[0].Role != "owner" {
		t.Fatalf("участники: %+v", members.Members)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/members", outsider.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("список не-участнику: ожидался 404, получен %d", code)
	}

	// Права добавления: member не добавляет; admin не назначает admin (не ниже своей)
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members", member.token,
		map[string]string{"user_id": outsider.userID}, nil); code != http.StatusForbidden {
		t.Fatalf("добавление участником: ожидался 403, получен %d", code)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members", admin.token,
		map[string]string{"user_id": outsider.userID, "role": "admin"}, nil); code != http.StatusForbidden {
		t.Fatalf("admin назначает admin: ожидался 403, получен %d", code)
	}
	// Несуществующий пользователь → 404
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members", owner.token,
		map[string]string{"user_id": "eeeeeeee-dead-dead-dead-eeeeeeeeeeee"}, nil); code != http.StatusNotFound {
		t.Fatalf("несуществующий user: ожидался 404, получен %d", code)
	}

	// PATCH настроек: member → 403, admin → 200
	if code := authedJSON(t, ts, "PATCH", "/api/v1/groups/"+groupID, member.token,
		map[string]any{"title": "взлом"}, nil); code != http.StatusForbidden {
		t.Fatalf("PATCH участником: ожидался 403, получен %d", code)
	}
	var patched struct {
		Title       string `json:"title"`
		SlowModeSec int32  `json:"slow_mode_sec"`
	}
	if code := authedJSON(t, ts, "PATCH", "/api/v1/groups/"+groupID, admin.token,
		map[string]any{"title": "новое имя", "slow_mode_sec": 30}, &patched); code != http.StatusOK {
		t.Fatalf("PATCH админом: %d", code)
	}
	if patched.Title != "новое имя" || patched.SlowModeSec != 30 {
		t.Fatalf("PATCH не применился: %+v", patched)
	}

	// Роли: admin не трогает owner; owner повышает member до moderator
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/members/"+owner.userID+"/role",
		admin.token, map[string]string{"role": "member"}, nil); code != http.StatusForbidden {
		t.Fatalf("admin понижает owner: ожидался 403, получен %d", code)
	}
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/members/"+member.userID+"/role",
		owner.token, map[string]string{"role": "moderator"}, nil); code != http.StatusOK {
		t.Fatalf("owner назначает moderator: %d", code)
	}

	// Бан: теперь-модератор банит... некого ниже нет — добавим и забаним
	addMemberAPI(t, ts, owner.token, groupID, outsider.userID, "member")
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members/"+outsider.userID+"/ban",
		member.token, map[string]any{"seconds": 3600}, nil); code != http.StatusNoContent {
		t.Fatalf("бан модератором: %d", code)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members/"+member.userID+"/ban",
		outsider.token, map[string]any{"seconds": 60}, nil); code != http.StatusForbidden {
		t.Fatalf("бан участником модератора: ожидался 403, получен %d", code)
	}

	// Выход: сам — можно; owner — 409 (передача владения — позже)
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+groupID+"/members/"+outsider.userID,
		outsider.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("самовыход: %d", code)
	}
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+groupID+"/members/"+owner.userID,
		owner.token, nil, nil); code != http.StatusConflict {
		t.Fatalf("выход owner: ожидался 409, получен %d", code)
	}
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+groupID+"/members/"+admin.userID,
		admin.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("самовыход админа: %d", code)
	}
	// Вышедший не видит private-группу
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, admin.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("GET после выхода: ожидался 404, получен %d", code)
	}

	// Удаление группы: не-owner → 403, owner → 204, после — 404 всем
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+groupID, member.token, nil, nil); code != http.StatusForbidden {
		t.Fatalf("удаление модератором: ожидался 403, получен %d", code)
	}
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+groupID, owner.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("удаление owner-ом: %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, owner.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("GET после удаления: ожидался 404, получен %d", code)
	}
}
