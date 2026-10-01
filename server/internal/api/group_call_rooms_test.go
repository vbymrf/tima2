package api

// Групповой звонок в личной группе (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ.md ГЗ1–ГЗ2). Проверяется то,
// что решает сервер: кто начинает, кого зовут, кто входит, что может создатель, сколько
// живёт временная группа. Медиа не трогаем — это зона LiveKit.

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"tima/server/internal/store"
)

type roomDoor struct {
	CallID    string         `json:"call_id"`
	Room      string         `json:"room"`
	Token     string         `json:"token"`
	Type      string         `json:"type"`
	GroupID   string         `json:"group_id"`
	CreatorID string         `json:"creator_id"`
	Kind      string         `json:"kind"`
	Paused    bool           `json:"paused"`
	Rules     GroupCallRules `json:"rules"`
}

func startRoomCallAs(t *testing.T, ts *httptest.Server, d *device, groupID string, body map[string]any) (roomDoor, int) {
	t.Helper()
	var out roomDoor
	code := jsonAuth(t, ts, "POST", "/api/v1/groups/"+groupID+"/call", d.token, body, &out)
	return out, code
}

// roomChanges — слова ленты звонков человека по этому звонку.
func roomChanges(t *testing.T, ts *httptest.Server, d *device, callID string) []string {
	t.Helper()
	var feed struct {
		Updates []struct {
			CallID string `json:"call_id"`
			Change string `json:"change"`
			Call   struct {
				Type    string `json:"type"`
				GroupID string `json:"group_id"`
			} `json:"call"`
		} `json:"updates"`
	}
	if code := getAuthed(t, ts, d.token, "/api/v1/calls/updates?after=0", &feed); code != 200 {
		t.Fatalf("лента звонков: %d", code)
	}
	var out []string
	for _, u := range feed.Updates {
		if u.CallID == callID {
			if u.Call.Type != "group" || u.Call.GroupID == "" {
				t.Fatalf("снимок группового звонка без группы: %+v", u.Call)
			}
			out = append(out, u.Change)
		}
	}
	return out
}

// Начинает модератор и выше; без модераторов — владелец (решение 14). Участник — нет.
func TestRoomCallStartedByModeratorOrOwner(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990070001")
	member := registerDevice(t, ts, "+79990070002")
	moder := registerDevice(t, ts, "+79990070003")
	groupID := createGroupWith(t, ts, owner, member, moder)

	if _, code := startRoomCallAs(t, ts, member, groupID, map[string]any{}); code != http.StatusForbidden {
		t.Fatalf("участник начал звонок: %d, ожидали 403", code)
	}
	if code := jsonAuth(t, ts, "PUT", "/api/v1/groups/"+groupID+"/members/"+moder.userID+"/role", owner.token,
		map[string]any{"role": "moderator"}, nil); code != 200 && code != 204 {
		t.Fatalf("роль модератора: %d", code)
	}
	door, code := startRoomCallAs(t, ts, moder, groupID, map[string]any{"video": true})
	if code != http.StatusCreated {
		t.Fatalf("модератор не начал звонок: %d", code)
	}
	if door.Type != "group" || door.GroupID != groupID || door.CreatorID != moder.userID || door.Token == "" {
		t.Fatalf("неполная дверь: %+v", door)
	}
	if door.Rules.Max != 25 || len(door.Rules.Video) != 2 || door.Rules.Video[0].Height != 720 {
		t.Fatalf("правила не те: %+v", door.Rules)
	}

	// Повторный звонок в группе с идущим — вход в тот же, нового не заводим (решение 1).
	again, code := startRoomCallAs(t, ts, owner, groupID, map[string]any{})
	if code != http.StatusOK || again.CallID != door.CallID {
		t.Fatalf("второй звонок в группе: %d, %s против %s", code, again.CallID, door.CallID)
	}
	// И даже участник без прав входит в идущий: войти может любой участник группы.
	if in, code := startRoomCallAs(t, ts, member, groupID, map[string]any{}); code != http.StatusOK || in.CallID != door.CallID {
		t.Fatalf("участник не вошёл в идущий: %d", code)
	}
}

// «Звонить» — только отмеченным (решения 3, 15); остальные входят сами.
func TestRoomCallRingsOnlyInvited(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990071001")
	marked := registerDevice(t, ts, "+79990071002")
	other := registerDevice(t, ts, "+79990071003")
	outsider := registerDevice(t, ts, "+79990071004")
	groupID := createGroupWith(t, ts, owner, marked, other)

	door, code := startRoomCallAs(t, ts, owner, groupID, map[string]any{
		"ring": true, "invited": []string{marked.userID, outsider.userID},
	})
	if code != http.StatusCreated {
		t.Fatalf("звонок: %d", code)
	}
	if got := roomChanges(t, ts, marked, door.CallID); len(got) != 1 || got[0] != "ringing" {
		t.Fatalf("отмеченному: %v, ожидали ringing", got)
	}
	if got := roomChanges(t, ts, other, door.CallID); len(got) != 0 {
		t.Fatalf("неотмеченному звонили: %v", got)
	}
	// Посторонний не позван, даже если его отметили: звать можно только из группы.
	parts, _ := srv.Store.GroupCallParticipants(t.Context(), door.CallID)
	for _, p := range parts {
		if p.UserID == outsider.userID {
			t.Fatal("посторонний попал в звонок через список отмеченных")
		}
	}
	// Неотмеченный входит сам — и отмечен как вошедший сам, а не позванный.
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/join", other.token, nil, nil); code != 200 {
		t.Fatalf("неотмеченный участник не вошёл: %d", code)
	}
	parts, _ = srv.Store.GroupCallParticipants(t.Context(), door.CallID)
	for _, p := range parts {
		if p.UserID == other.userID && p.Invited {
			t.Fatal("вошедший сам записан позванным")
		}
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/join", outsider.token, nil, nil); code != http.StatusForbidden {
		t.Fatalf("посторонний вошёл: %d", code)
	}

	// Без «Звонить» — ни одного вызова.
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/control", owner.token,
		map[string]any{"action": "stop"}, nil); code != 200 {
		t.Fatalf("остановка: %d", code)
	}
	quiet, _ := startRoomCallAs(t, ts, owner, groupID, map[string]any{"invited": []string{marked.userID}})
	if got := roomChanges(t, ts, marked, quiet.CallID); len(got) != 0 {
		t.Fatalf("без «Звонить» звонили: %v", got)
	}
}

// Команды — только создателю; «удалить» — из звонка, не из группы (решения 6, 17).
func TestRoomCallControlByCreatorOnly(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990072001")
	member := registerDevice(t, ts, "+79990072002")
	groupID := createGroupWith(t, ts, owner, member)
	door, _ := startRoomCallAs(t, ts, owner, groupID, map[string]any{"ring": true})

	control := func(d *device, action, user string) int {
		return jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/control", d.token,
			map[string]any{"action": action, "user_id": user}, nil)
	}
	if code := control(member, "remove", owner.userID); code != http.StatusForbidden {
		t.Fatalf("участник командует: %d, ожидали 403", code)
	}
	if code := control(owner, "pause", ""); code != 200 {
		t.Fatalf("пауза: %d", code)
	}
	var state struct {
		Call *struct {
			Paused bool `json:"paused"`
		} `json:"call"`
		CanStart bool `json:"can_start"`
	}
	getAuthed(t, ts, member.token, "/api/v1/groups/"+groupID+"/call", &state)
	if state.Call == nil || !state.Call.Paused {
		t.Fatalf("пауза не видна участнику: %+v", state.Call)
	}
	if state.CanStart {
		t.Fatal("участнику сказано, что он может начать звонок")
	}
	if code := control(owner, "resume", ""); code != 200 {
		t.Fatalf("продолжение: %d", code)
	}

	if code := control(owner, "remove", member.userID); code != 200 {
		t.Fatalf("удаление: %d", code)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/join", member.token, nil, nil); code != http.StatusForbidden {
		t.Fatalf("удалённый вошёл обратно: %d", code)
	}
	if role, err := srv.Store.GroupRole(t.Context(), groupID, member.userID); err != nil || role == "" {
		t.Fatalf("удалённый из звонка выпал из группы: %q %v", role, err)
	}

	if code := control(owner, "stop", ""); code != 200 {
		t.Fatalf("остановка: %d", code)
	}
	getAuthed(t, ts, member.token, "/api/v1/groups/"+groupID+"/call", &state)
	if state.Call != nil {
		t.Fatal("после остановки звонок всё ещё идёт")
	}
}

// Предел участников — с сервера; занятые места считаются по тем, кто в комнате (решение 4).
func TestRoomCallFull(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	srv.CallGroups = GroupCallRules{Max: 2, Video: DefaultGroupCallRules.Video, TTL: time.Hour}
	owner := registerDevice(t, ts, "+79990073001")
	a := registerDevice(t, ts, "+79990073002")
	b := registerDevice(t, ts, "+79990073003")
	groupID := createGroupWith(t, ts, owner, a, b)
	door, _ := startRoomCallAs(t, ts, owner, groupID, map[string]any{"invited": []string{a.userID}})
	for _, d := range []*device{owner, a} {
		if err := srv.Store.SetParticipantState(t.Context(), door.CallID, d.userID, store.PartJoined, time.Now()); err != nil {
			t.Fatal(err)
		}
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/join", b.token, nil, nil); code != http.StatusConflict {
		t.Fatalf("третий вошёл в звонок на двоих: %d, ожидали 409", code)
	}
	// Вернувшийся место не занимает дважды.
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/join", a.token, nil, nil); code != 200 {
		t.Fatalf("бывший в комнате не вернулся: %d", code)
	}
}

// Отклонил вызов — остальные устройства замолкают, пропущенного нет, звонок идёт.
func TestRoomCallDeclineLeavesCallRunning(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990074001")
	a := registerDevice(t, ts, "+79990074002")
	groupID := createGroupWith(t, ts, owner, a)
	door, _ := startRoomCallAs(t, ts, owner, groupID, map[string]any{"ring": true})
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/end", a.token, nil, nil); code != 200 {
		t.Fatalf("отказ: %d", code)
	}
	if got := roomChanges(t, ts, a, door.CallID); len(got) != 2 || got[1] != "declined" {
		t.Fatalf("лента отказавшегося: %v", got)
	}
	if live, err := srv.Store.LiveGroupCall(t.Context(), groupID); err != nil || live.CallID != door.CallID {
		t.Fatalf("отказ одного кончил звонок: %v", err)
	}
	// Остановка после отказа пропущенного не даёт: он не «не ответил», он отказался.
	jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/control", owner.token, map[string]any{"action": "stop"}, nil)
	if got := roomChanges(t, ts, a, door.CallID); len(got) != 2 {
		t.Fatalf("отказавшемуся после конца пришло ещё: %v", got)
	}
}

// Временная группа: срок у неё есть, у обычной — нет; вышедшая удаляется с перепиской,
// обычную уборщик не трогает никогда (ГЗ1).
func TestCallGroupLifetime(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	srv.CallGroups = GroupCallRules{Max: 25, Video: DefaultGroupCallRules.Video, TTL: time.Millisecond}
	owner := registerDevice(t, ts, "+79990075001")
	member := registerDevice(t, ts, "+79990075002")

	var temp struct {
		GroupID      string `json:"group_id"`
		CallTTLUntil string `json:"call_ttl_until"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/groups", owner.token,
		map[string]any{"title": "Групповой звонок", "kind": "private", "call_temp": true}, &temp); code != 201 {
		t.Fatalf("временная группа: %d", code)
	}
	if temp.CallTTLUntil == "" {
		t.Fatal("у временной группы нет срока")
	}
	jsonAuth(t, ts, "POST", "/api/v1/groups/"+temp.GroupID+"/members", owner.token, map[string]any{"user_id": member.userID}, nil)
	if code := jsonAuth(t, ts, "POST", "/api/v1/groups", owner.token,
		map[string]any{"title": "Публичная", "kind": "public", "call_temp": true}, nil); code != http.StatusBadRequest {
		t.Fatalf("временная публичная завелась: %d", code)
	}
	plain := createGroupWith(t, ts, owner, member)

	var mine struct {
		Groups []struct {
			GroupID      string `json:"group_id"`
			CallTTLUntil string `json:"call_ttl_until"`
		} `json:"groups"`
	}
	getAuthed(t, ts, member.token, "/api/v1/groups", &mine)
	for _, g := range mine.Groups {
		if g.GroupID == temp.GroupID && g.CallTTLUntil == "" {
			t.Fatal("в списке групп у временной нет срока")
		}
		if g.GroupID == plain && g.CallTTLUntil != "" {
			t.Fatal("у обычной группы появился срок")
		}
	}

	// Идущий звонок держит группу и после срока.
	door, _ := startRoomCallAs(t, ts, owner, temp.GroupID, map[string]any{})
	time.Sleep(20 * time.Millisecond)
	if ids, _ := srv.Store.ExpiredCallGroups(t.Context(), 100); hasID(ids, temp.GroupID) {
		t.Fatal("уборщик выбрал группу с идущим звонком")
	}
	jsonAuth(t, ts, "POST", "/api/v1/calls/"+door.CallID+"/control", owner.token, map[string]any{"action": "stop"}, nil)
	time.Sleep(20 * time.Millisecond)
	ids, err := srv.Store.ExpiredCallGroups(t.Context(), 100)
	if err != nil {
		t.Fatal(err)
	}
	if !hasID(ids, temp.GroupID) || hasID(ids, plain) {
		t.Fatalf("к удалению: %v", ids)
	}
	members, err := srv.Store.DeleteCallGroup(t.Context(), temp.GroupID)
	if err != nil || len(members) != 2 {
		t.Fatalf("удаление: %v, участников %d", err, len(members))
	}
	if _, err := srv.Store.DeleteCallGroup(t.Context(), plain); err == nil {
		t.Fatal("путь временной группы удалил обычную")
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/groups/"+temp.GroupID+"/call", nil); code != http.StatusNotFound {
		t.Fatalf("удалённая группа отвечает: %d", code)
	}
}

func hasID(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}
