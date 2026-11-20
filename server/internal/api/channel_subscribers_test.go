package api

import "testing"

// Подписчики канала (ПЛАН-(ВС) 16, заказчик 2026-11-20): видят владелец и модераторы, исключать можно; владельца —
// никто, модератора — только владелец.
func TestChannelSubscribers(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990099001")
	mod := registerDevice(t, ts, "+79990099002")
	reader := registerDevice(t, ts, "+79990099003")
	other := registerDevice(t, ts, "+79990099004")

	var ch struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]any{"title": "Новости"}, &ch); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	base := "/api/v1/channels/" + ch.ChannelID
	for _, d := range []*device{mod, reader, other} {
		if code := postAuthed(t, ts, d.token, "POST", base+"/subscribe", nil, nil); code/100 != 2 {
			t.Fatalf("подписка: %d", code)
		}
	}
	if code := postAuthed(t, ts, owner.token, "POST", base+"/moderators", map[string]string{"user_id": mod.userID}, nil); code != 201 {
		t.Fatalf("модератор: %d", code)
	}

	var list struct {
		Subscribers []string `json:"subscribers"`
		OwnerID     string   `json:"owner_id"`
		Moderators  []string `json:"moderators"`
	}
	if code := getAuthed(t, ts, reader.token, base+"/subscribers", &list); code != 403 {
		t.Fatalf("подписчик видит список: %d", code)
	}
	if code := getAuthed(t, ts, mod.token, base+"/subscribers", &list); code != 200 || !listHas(list.Subscribers, reader.userID) || list.OwnerID != owner.userID || !listHas(list.Moderators, mod.userID) {
		t.Fatalf("модератор не видит список: %d %+v", code, list)
	}
	if code := postAuthed(t, ts, reader.token, "DELETE", base+"/subscribers/"+other.userID, nil, nil); code != 403 {
		t.Fatalf("подписчик исключил: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "DELETE", base+"/subscribers/"+owner.userID, nil, nil); code != 403 {
		t.Fatalf("исключили владельца: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "DELETE", base+"/subscribers/"+reader.userID, nil, nil); code != 204 {
		t.Fatalf("модератор не исключил: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "DELETE", base+"/subscribers/"+mod.userID, nil, nil); code != 204 {
		t.Fatalf("владелец не исключил модератора: %d", code)
	}
	getAuthed(t, ts, owner.token, base+"/subscribers", &list)
	if listHas(list.Subscribers, reader.userID) || listHas(list.Subscribers, mod.userID) || listHas(list.Moderators, mod.userID) {
		t.Fatalf("исключённые остались: %+v", list)
	}
}
