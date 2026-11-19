package api

import (
	"testing"
)

// Секретные сущности и приглашения (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2): секретное не находится нигде, войти — только
// по приглашению. По одной проверке на каждое место из списка «не находится».


func TestСекретныйКаналНеВКаталогеИПодпискаТолькоПоПриглашению(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008101")
	stranger := registerDevice(t, ts, "+79990008102")

	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]any{"title": "тайный канал", "hidden": true}, &created); code != 201 {
		t.Fatalf("создание: %d", code)
	}
	var found struct {
		Channels []struct {
			ChannelID string `json:"channel_id"`
		} `json:"channels"`
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/channels/discover", &found); code != 200 {
		t.Fatalf("discover: %d", code)
	}
	for _, c := range found.Channels {
		if c.ChannelID == created.ChannelID {
			t.Fatal("секретный канал нашёлся в discover")
		}
	}
	if code := postAuthed(t, ts, stranger.token, "POST", "/api/v1/channels/"+created.ChannelID+"/subscribe", nil, nil); code != 404 {
		t.Fatalf("подписка без приглашения: ждали 404, пришло %d", code)
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/channels/"+created.ChannelID+"/posts", nil); code != 404 {
		t.Fatalf("лента секретного постороннему: ждали 404, пришло %d", code)
	}

	// Приглашение: принял — подписан.
	var inv struct {
		InviteID string `json:"invite_id"`
		Hidden   bool   `json:"hidden"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+created.ChannelID+"/invites",
		map[string]any{"user_id": stranger.userID}, &inv); code != 201 {
		t.Fatalf("приглашение: %d", code)
	}
	var mine struct {
		Invites []struct {
			InviteID string `json:"invite_id"`
			Title    string `json:"title"`
			Hidden   bool   `json:"hidden"`
		} `json:"invites"`
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/users/me/invites", &mine); code != 200 || len(mine.Invites) != 1 {
		t.Fatalf("мои приглашения: %d, %d шт.", code, len(mine.Invites))
	}
	if mine.Invites[0].Title != "тайный канал" || !mine.Invites[0].Hidden {
		t.Fatalf("приглашение без названия или признака: %+v", mine.Invites[0])
	}
	if code := postAuthed(t, ts, stranger.token, "PUT", "/api/v1/invites/"+inv.InviteID, map[string]any{"accept": true}, nil); code != 200 {
		t.Fatalf("принять: %d", code)
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/channels/"+created.ChannelID+"/posts", nil); code != 200 {
		t.Fatalf("лента после приглашения: %d", code)
	}
	// Ответ один: второй раз — нет.
	if code := postAuthed(t, ts, stranger.token, "PUT", "/api/v1/invites/"+inv.InviteID, map[string]any{"accept": false}, nil); code != 404 {
		t.Fatalf("второй ответ: ждали 404, пришло %d", code)
	}
}

func TestСекретнаяГруппаНевидимаПостороннемуИКарточкаСтирается(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008111")
	friend := registerDevice(t, ts, "+79990008112")
	stranger := registerDevice(t, ts, "+79990008113")

	// Публичная — видна; стала секретной — нет.
	public := makeGroup(t, ts, owner.token, "public")
	if code := getAuthed(t, ts, stranger.token, "/api/v1/groups/"+public, nil); code != 200 {
		t.Fatalf("публичная постороннему: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PATCH", "/api/v1/groups/"+public, map[string]any{"hidden": true}, nil); code != 200 {
		t.Fatalf("сделать секретной: %d", code)
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/groups/"+public, nil); code != 404 {
		t.Fatalf("секретная публичная постороннему: ждали 404, пришло %d", code)
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/groups/"+public+"/showcase", nil); code != 404 {
		t.Fatalf("описание секретной постороннему: ждали 404, пришло %d", code)
	}

	// Личная с карточкой другу: стала секретной — карточка стёрта, другу «не найдено».
	personal := makeGroup(t, ts, owner.token, "private")
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/groups/"+personal+"/audience",
		map[string]any{"user_ids": []string{friend.userID}}, nil); code != 200 {
		t.Fatalf("карточка другу: %d", code)
	}
	if code := getAuthed(t, ts, friend.token, "/api/v1/groups/"+personal, nil); code != 200 {
		t.Fatalf("карточка до секретности: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PATCH", "/api/v1/groups/"+personal, map[string]any{"hidden": true}, nil); code != 200 {
		t.Fatalf("сделать секретной: %d", code)
	}
	if code := getAuthed(t, ts, friend.token, "/api/v1/groups/"+personal, nil); code != 404 {
		t.Fatalf("карточка после секретности: ждали 404, пришло %d", code)
	}

	// Приглашённому — видна; принял — участник.
	var inv struct {
		InviteID string `json:"invite_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/groups/"+personal+"/invites",
		map[string]any{"user_id": friend.userID}, &inv); code != 201 {
		t.Fatalf("приглашение: %d", code)
	}
	if code := getAuthed(t, ts, friend.token, "/api/v1/groups/"+personal, nil); code != 200 {
		t.Fatalf("приглашённому: %d", code)
	}
	if code := postAuthed(t, ts, friend.token, "PUT", "/api/v1/invites/"+inv.InviteID, map[string]any{"accept": true}, nil); code != 200 {
		t.Fatalf("принять: %d", code)
	}
	var g struct {
		MyRole string `json:"my_role"`
		Hidden bool   `json:"hidden"`
	}
	if code := getAuthed(t, ts, friend.token, "/api/v1/groups/"+personal, &g); code != 200 || g.MyRole != "member" || !g.Hidden {
		t.Fatalf("после приглашения: %d, %+v", code, g)
	}
	// Участник без роли не приглашает.
	if code := postAuthed(t, ts, friend.token, "POST", "/api/v1/groups/"+personal+"/invites",
		map[string]any{"user_id": stranger.userID}, nil); code != 403 {
		t.Fatalf("приглашение участником: ждали 403, пришло %d", code)
	}
}

func TestСекретноеСообществоИСекретныйЭлементНеВидныПостороннему(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008121")
	stranger := registerDevice(t, ts, "+79990008122")

	// Открытое сообщество с секретной группой: посторонний её в составе не видит.
	open := makeCommunity(t, ts, owner, "Открытое")
	secretGroup := makeGroup(t, ts, owner.token, "public")
	if code := postAuthed(t, ts, owner.token, "PATCH", "/api/v1/groups/"+secretGroup, map[string]any{"hidden": true}, nil); code != 200 {
		t.Fatalf("секретная группа: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+open+"/items",
		map[string]any{"kind": "group", "id": secretGroup}, nil); code != 201 {
		t.Fatalf("связывание: %d", code)
	}
	var page communityPageAnswer
	if code := getAuthed(t, ts, stranger.token, "/api/v1/communities/"+open, &page); code != 200 {
		t.Fatalf("страница: %d", code)
	}
	for _, it := range page.Items {
		if it.ID == secretGroup {
			t.Fatal("секретный элемент в составе у постороннего")
		}
	}

	// Секретное сообщество: не в discover, страница и подписка — «не найдено».
	secret := makeCommunity(t, ts, owner, "Тайное")
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/communities/"+secret+"/hidden", map[string]any{"hidden": true}, nil); code != 200 {
		t.Fatalf("сделать секретным: %d", code)
	}
	var found struct {
		Communities []struct {
			CommunityID string `json:"community_id"`
		} `json:"communities"`
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/communities/discover", &found); code != 200 {
		t.Fatalf("discover: %d", code)
	}
	for _, c := range found.Communities {
		if c.CommunityID == secret {
			t.Fatal("секретное сообщество в discover")
		}
	}
	if code := getAuthed(t, ts, stranger.token, "/api/v1/communities/"+secret, nil); code != 404 {
		t.Fatalf("страница секретного: ждали 404, пришло %d", code)
	}
	if code := postAuthed(t, ts, stranger.token, "POST", "/api/v1/communities/"+secret+"/subscribe", nil, nil); code != 404 {
		t.Fatalf("подписка на секретное: ждали 404, пришло %d", code)
	}
	// Посторонний «секретное» не ставит.
	if code := postAuthed(t, ts, stranger.token, "PUT", "/api/v1/communities/"+open+"/hidden", map[string]any{"hidden": true}, nil); code != 403 {
		t.Fatalf("чужой ставит секретность: ждали 403, пришло %d", code)
	}
}

// Лента человека — тоже канал, но в «Каталог» Социума не идёт: сервер отмечает её полем feed (ПЛАН-(СЦ) СЦ3).
func TestСвоиКаналыОтмечаютЛентуИМодератора(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008131")
	mod := registerDevice(t, ts, "+79990008132")
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]any{"title": "Анонсы"}, &created); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "POST", "/api/v1/channels/"+created.ChannelID+"/subscribe", nil, nil); code != 200 {
		t.Fatalf("подписка: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+created.ChannelID+"/moderators",
		map[string]any{"user_id": mod.userID}, nil); code != 200 && code != 201 && code != 204 {
		t.Fatalf("модератор: %d", code)
	}
	var mine struct {
		Channels []struct {
			ChannelID string `json:"channel_id"`
			Feed      bool   `json:"feed"`
			Moderator bool   `json:"moderator"`
		} `json:"channels"`
	}
	if code := getAuthed(t, ts, mod.token, "/api/v1/channels", &mine); code != 200 {
		t.Fatalf("свои каналы: %d", code)
	}
	found := false
	for _, c := range mine.Channels {
		if c.ChannelID == created.ChannelID {
			found = true
			if c.Feed || !c.Moderator {
				t.Fatalf("канал отмечен не так: %+v", c)
			}
		}
	}
	if !found {
		t.Fatal("канала нет в своих")
	}
}
