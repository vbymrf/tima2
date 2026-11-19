package api

import (
	"encoding/base64"
	"fmt"
	"testing"
)

// Переписка «сущность — человек» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.9, СЦ15): проверки сервера из плана.

func threadBody(text string) map[string]any {
	return map[string]any{"body": base64.RawURLEncoding.EncodeToString([]byte(text)), "preview": text}
}

type userThreadAnswer struct {
	EntityKind string `json:"entity_kind"`
	EntityID   string `json:"entity_id"`
	Accepts    bool   `json:"accepts"`
	Thread     *struct {
		ThreadID string `json:"thread_id"`
	} `json:"thread"`
	Messages []map[string]any `json:"messages"`
}

func TestЛентаСущностиОднаНаПаруАвторСкрыт(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008401")
	mod := registerDevice(t, ts, "+79990008402")
	person := registerDevice(t, ts, "+79990008403")
	stranger := registerDevice(t, ts, "+79990008404")
	group := makeGroup(t, ts, owner.token, "public")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/groups/"+group+"/members", map[string]any{"user_id": mod.userID, "role": "moderator"}, nil); code != 201 {
		t.Fatalf("модератор: %d", code)
	}
	// Человек пишет первым — лента заводится.
	if code := postAuthed(t, ts, person.token, "POST", "/api/v1/groups/"+group+"/thread/messages", threadBody("можно с собакой?"), nil); code != 201 {
		t.Fatalf("человек пишет: %d", code)
	}
	// Отвечают двое — владелец и модератор; лента одна.
	for _, who := range []*device{owner, mod} {
		if code := postAuthed(t, ts, who.token, "POST", "/api/v1/groups/"+group+"/threads/"+person.userID+"/messages", threadBody("можно"), nil); code != 201 {
			t.Fatalf("ответ за сущность: %d", code)
		}
	}
	var mine userThreadAnswer
	if code := getAuthed(t, ts, person.token, "/api/v1/groups/"+group+"/thread/messages", &mine); code != 200 || mine.Thread == nil || len(mine.Messages) != 3 {
		t.Fatalf("лента у человека: %d %+v", code, mine)
	}
	for _, m := range mine.Messages {
		if _, has := m["author_id"]; has {
			t.Fatalf("человеку отдан автор: %+v", m)
		}
	}
	// Команде автор виден.
	var staff struct {
		Messages []map[string]any `json:"messages"`
	}
	if code := getAuthed(t, ts, mod.token, "/api/v1/groups/"+group+"/threads/"+person.userID+"/messages", &staff); code != 200 || len(staff.Messages) != 3 || staff.Messages[1]["author_id"] != owner.userID {
		t.Fatalf("команде автор: %d %+v", code, staff.Messages)
	}
	// Без роли — ни чтения, ни записи за сущность.
	if code := getAuthed(t, ts, stranger.token, "/api/v1/groups/"+group+"/threads", nil); code != 403 {
		t.Fatalf("чужой читает «Обращения»: ждали 403, пришло %d", code)
	}
	if code := postAuthed(t, ts, stranger.token, "POST", "/api/v1/groups/"+group+"/threads/"+person.userID+"/messages", threadBody("x"), nil); code != 403 {
		t.Fatalf("чужой пишет за сущность: ждали 403, пришло %d", code)
	}
	// Мои ленты — одна строка.
	var list struct {
		Threads []struct {
			EntityID string `json:"entity_id"`
			Preview  string `json:"preview"`
			Unread   int    `json:"unread"`
		} `json:"threads"`
	}
	if code := getAuthed(t, ts, person.token, "/api/v1/users/me/threads", &list); code != 200 || len(list.Threads) != 1 || list.Threads[0].Unread != 2 {
		t.Fatalf("мои ленты: %d %+v", code, list)
	}
}

func TestПределПятьЛюдейВСуткиИБлокировки(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008411")
	var ch struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]any{"title": "Объявления"}, &ch); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	path := "/api/v1/channels/" + ch.ChannelID
	people := make([]*device, 6)
	for i := range people {
		people[i] = registerDevice(t, ts, fmt.Sprintf("+7999000842%d", i))
	}
	for i := 0; i < 5; i++ {
		if code := postAuthed(t, ts, owner.token, "POST", path+"/threads/"+people[i].userID+"/messages", threadBody("привет"), nil); code != 201 {
			t.Fatalf("человек %d: %d", i, code)
		}
	}
	// Шестой — отказ, и ответом написавшему первым тоже.
	if code := postAuthed(t, ts, people[5].token, "POST", path+"/thread/messages", threadBody("вопрос"), nil); code != 201 {
		t.Fatalf("шестой пишет сам: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", path+"/threads/"+people[5].userID+"/messages", threadBody("ответ"), nil); code != 429 {
		t.Fatalf("шестой человек за сутки: ждали 429, пришло %d", code)
	}
	// Десятое сообщение одному — можно.
	for i := 0; i < 9; i++ {
		if code := postAuthed(t, ts, owner.token, "POST", path+"/threads/"+people[0].userID+"/messages", threadBody("ещё"), nil); code != 201 {
			t.Fatalf("ещё одному: %d", code)
		}
	}
	// Заблокировано человеком — сущность не пишет; сам он писать может.
	if code := postAuthed(t, ts, people[1].token, "PUT", path+"/thread/state", map[string]any{"state": "blocked"}, nil); code != 204 {
		t.Fatalf("заблокировать: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", path+"/threads/"+people[1].userID+"/messages", threadBody("x"), nil); code != 403 {
		t.Fatalf("заблокировавшему: ждали 403, пришло %d", code)
	}
	if code := postAuthed(t, ts, people[1].token, "POST", path+"/thread/messages", threadBody("я сам"), nil); code != 201 {
		t.Fatalf("заблокировавший пишет сам: %d", code)
	}
	// Сущность блокирует человека — он не пишет.
	if code := postAuthed(t, ts, owner.token, "PUT", path+"/threads/"+people[2].userID+"/block", map[string]any{"blocked": true}, nil); code != 204 {
		t.Fatalf("блок человека: %d", code)
	}
	if code := postAuthed(t, ts, people[2].token, "POST", path+"/thread/messages", threadBody("x"), nil); code != 403 {
		t.Fatalf("заблокированный сущностью пишет: ждали 403, пришло %d", code)
	}
	// «Принимать сообщения» выключено — никто не пишет.
	if code := postAuthed(t, ts, owner.token, "PUT", path+"/appeals", map[string]any{"accept": false}, nil); code != 204 {
		t.Fatalf("не принимать: %d", code)
	}
	if code := postAuthed(t, ts, people[3].token, "POST", path+"/thread/messages", threadBody("x"), nil); code != 403 {
		t.Fatalf("при выключенном приёме: ждали 403, пришло %d", code)
	}
}

func TestЭлементСообществаПишетВЛентуСообщества(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008431")
	person := registerDevice(t, ts, "+79990008432")
	community := makeCommunity(t, ts, owner, "Клуб")
	group := makeGroup(t, ts, owner.token, "public")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/items", map[string]any{"kind": "group", "id": group}, nil); code != 201 {
		t.Fatalf("связывание: %d", code)
	}
	var ans struct {
		EntityKind string `json:"entity_kind"`
		EntityID   string `json:"entity_id"`
	}
	if code := postAuthed(t, ts, person.token, "POST", "/api/v1/groups/"+group+"/thread/messages", threadBody("из группы"), &ans); code != 201 {
		t.Fatalf("пишет из элемента: %d", code)
	}
	if ans.EntityKind != "community" || ans.EntityID != community {
		t.Fatalf("лента не сообщества: %+v", ans)
	}
	var mine userThreadAnswer
	if code := getAuthed(t, ts, person.token, "/api/v1/communities/"+community+"/thread/messages", &mine); code != 200 || len(mine.Messages) != 1 {
		t.Fatalf("лента сообщества: %d %+v", code, mine)
	}
}
