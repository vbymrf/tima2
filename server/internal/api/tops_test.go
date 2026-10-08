package api

import (
	"context"
	"testing"
)

// «Зашли, забрали» и уведомления по настройке на сервере (ПЛАН-(ОУ)).

func eventsOf(t *testing.T, srv *Server, deviceID, eventType string) int {
	t.Helper()
	events, err := srv.Store.ListDeviceEvents(context.Background(), deviceID, 0, 500)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, e := range events {
		if e.EventType == eventType {
			n++
		}
	}
	return n
}

func topOf(t *testing.T, page statesPage, entity string) *stateRow {
	t.Helper()
	var found *stateRow
	for i := range page.States {
		s := page.States[i]
		if s.Kind == "top" && s.EntityID == entity {
			found = &s
		}
	}
	return found
}

// TestПостКаналаВершинойАНеКопией — пост не заводит событий в журналах подписчиков (ОУ1); у
// подписчика вершина канала и число непрочитанного; отметка «прочитал до» гасит число; отключённые
// уведомления — строкой ленты (ОУ2, ОУ3).
func TestПостКаналаВершинойАНеКопией(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990000720")
	reader := registerDevice(t, ts, "+79990000721")
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]string{"title": "Анонсы"}, &created); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	ch := created.ChannelID
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/channels/"+ch+"/subscribe", nil, nil); code != 200 {
		t.Fatalf("подписка: %d", code)
	}
	var post struct {
		PostID int64 `json:"post_id"`
	}
	for i := 0; i < 3; i++ {
		if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts",
			map[string]string{"text": "пост"}, &post); code != 201 {
			t.Fatalf("пост: %d", code)
		}
	}
	if n := eventsOf(t, srv, reader.id, "channel.post"); n != 0 {
		t.Fatalf("пост скопирован в журнал подписчика: %d", n)
	}
	var page statesPage
	getAuthed(t, ts, reader.token, "/api/v1/users/me/states?after=0", &page)
	top := topOf(t, page, ch)
	if top == nil || top.EntityKind != "channel" || top.TopID != post.PostID || top.Unread != 3 {
		t.Fatalf("вершина канала не та: %+v", top)
	}
	// У автора своё новым не считается — вершины нет вовсе.
	var mine statesPage
	getAuthed(t, ts, owner.token, "/api/v1/users/me/states?after=0", &mine)
	if topOf(t, mine, ch) != nil {
		t.Fatal("автору заведена вершина своего канала")
	}

	if code := jsonAuth(t, ts, "PUT", "/api/v1/users/me/reads", reader.token,
		map[string]any{"kind": "channel", "entity_id": ch, "read_id": post.PostID}, nil); code != 204 {
		t.Fatalf("прочитал: %d", code)
	}
	getAuthed(t, ts, reader.token, "/api/v1/users/me/states?after=0", &page)
	if top := topOf(t, page, ch); top == nil || top.Unread != 0 {
		t.Fatalf("отметка не погасила число: %+v", top)
	}

	if code := jsonAuth(t, ts, "PUT", "/api/v1/users/me/notify", reader.token,
		map[string]any{"kind": "channel", "entity_id": ch, "off": true}, nil); code != 204 {
		t.Fatalf("отключить: %d", code)
	}
	getAuthed(t, ts, reader.token, "/api/v1/users/me/states?after=0", &page)
	off := false
	for _, s := range page.States {
		if s.Kind == "notify" && s.EntityID == ch && s.Off {
			off = true
		}
	}
	if !off {
		t.Fatalf("отключение не легло в ленту: %+v", page.States)
	}
	if code := jsonAuth(t, ts, "PUT", "/api/v1/users/me/notify", reader.token,
		map[string]any{"kind": "planet", "entity_id": ch, "off": true}, nil); code != 400 {
		t.Fatalf("неизвестный вид принят: %d", code)
	}
}

// TestОткрытаяГруппаПоСпособуДоставки — устройство, заявившее «зашли, забрали», получает
// вершину, а не тело; прежнее устройство — тело, как раньше (ОУ4).
func TestОткрытаяГруппаПоСпособуДоставки(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990000730")
	fresh := registerDevice(t, ts, "+79990000731")
	old := registerDevice(t, ts, "+79990000732")
	var created struct {
		GroupID string `json:"group_id"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/groups", owner.token,
		map[string]any{"title": "Открытая", "kind": "public"}, &created); code != 201 {
		t.Fatalf("группа: %d", code)
	}
	g := created.GroupID
	for _, m := range []*device{fresh, old} {
		if code := jsonAuth(t, ts, "POST", "/api/v1/groups/"+g+"/members", owner.token,
			map[string]any{"user_id": m.userID}, nil); code != 201 && code != 200 {
			t.Fatalf("участник: %d", code)
		}
	}
	if code := jsonAuth(t, ts, "PUT", "/api/v1/devices/me/delivery", fresh.token, map[string]any{"mode": "tops"}, nil); code != 204 {
		t.Fatalf("способ доставки: %d", code)
	}
	code, _ := sendGroupMessage(t, ts, owner, g, groupMsg{
		ClientMsgID: "cccccccc-0000-0000-0000-000000000731", Payload: []byte("открыто"),
	}, false)
	if code != 201 {
		t.Fatalf("сообщение: %d", code)
	}
	if n := eventsOf(t, srv, fresh.id, "message.group"); n != 0 {
		t.Fatalf("новому устройству тело в журнал: %d", n)
	}
	if n := eventsOf(t, srv, old.id, "message.group"); n != 1 {
		t.Fatalf("прежнему устройству тела нет: %d", n)
	}
	var page statesPage
	getAuthed(t, ts, fresh.token, "/api/v1/users/me/states?after=0", &page)
	if top := topOf(t, page, g); top == nil || top.EntityKind != "group" || top.Unread != 1 {
		t.Fatalf("вершина открытой группы не та: %+v", top)
	}
}
