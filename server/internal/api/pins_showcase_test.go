package api

// Закреплённое и описание (ПЛАН-(ЗО) ЗО1–ЗО2): кто закрепляет, что можно закрепить, удалённое
// открепляется само; описание — только окном, не больше 50, от старых к новым, суперсообщение
// ставит владелец.

import (
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
)

type pinAnswer struct {
	MessageID int64  `json:"message_id"`
	By        string `json:"by"`
}

func TestЗакреплениеЛичнойПереписки(t *testing.T) {
	ts, srv := setup(t)
	a := registerDevice(t, ts, "+79995590101")
	b := registerDevice(t, ts, "+79995590102")
	c := registerDevice(t, ts, "+79995590103")
	if code, _ := editReply(t, post(t, ts, sealEnvelope(t, a, []*device{b}, 1, []byte("первое")), a.token,
		"eeeeeeee-1111-0000-0000-000000000001")); code != http.StatusCreated {
		t.Fatalf("сообщение: %d", code)
	}
	chat := chatIDFor(a, []*device{b})
	path := "/api/v1/chats/" + chat + "/pin"

	if code := authedJSON(t, ts, "PUT", path, c.token, map[string]any{"message_id": 1}, nil); code != http.StatusNotFound {
		t.Fatalf("посторонний закрепил: ожидался 404, получен %d", code)
	}
	if code := authedJSON(t, ts, "PUT", path, b.token, map[string]any{"message_id": 99}, nil); code != http.StatusConflict {
		t.Fatalf("закрепление несуществующего: ожидался 409, получен %d", code)
	}
	// Закрепляет любой из двух — здесь получатель.
	if code := authedJSON(t, ts, "PUT", path, b.token, map[string]any{"message_id": 1}, nil); code != http.StatusOK {
		t.Fatalf("закрепление получателем: %d", code)
	}
	var got pinAnswer
	if code := authedJSON(t, ts, "GET", path, a.token, nil, &got); code != http.StatusOK || got.MessageID != 1 || got.By != b.userID {
		t.Fatalf("закреплённое у второго: %d %+v", code, got)
	}
	if n := eventsOf(t, srv, a.id, "message.pinned"); n != 1 {
		t.Fatalf("событий «закреплено» у второго %d, ожидалось 1", n)
	}
	// Удалённое открепляется само.
	if code := authedJSON(t, ts, "DELETE", "/api/v1/chats/"+chat+"/messages/1", a.token, nil, nil); code != http.StatusOK {
		t.Fatalf("удаление: %d", code)
	}
	got = pinAnswer{}
	if code := authedJSON(t, ts, "GET", path, b.token, nil, &got); code != http.StatusOK || got.MessageID != 0 {
		t.Fatalf("после удаления закреплённого: %d %+v", code, got)
	}
}

func TestЗакреплениеГруппыМодератором(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79995590201")
	member := registerDevice(t, ts, "+79995590202")
	groupID := createPublicGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	code, resp := sendLeveled(t, ts, member, groupID, "eeeeeeee-2222-0000-0000-000000000001", 1, []byte("x"))
	if code != http.StatusCreated {
		t.Fatalf("сообщение: %d", code)
	}
	var n int64
	_ = json.Unmarshal(resp["message_id"], &n)
	path := "/api/v1/groups/" + groupID + "/pin"
	if code := authedJSON(t, ts, "PUT", path, member.token, map[string]any{"message_id": n}, nil); code != http.StatusForbidden {
		t.Fatalf("участник закрепил: ожидался 403, получен %d", code)
	}
	if code := authedJSON(t, ts, "PUT", path, owner.token, map[string]any{"message_id": n}, nil); code != http.StatusOK {
		t.Fatalf("закрепление владельцем: %d", code)
	}
	var got pinAnswer
	if code := authedJSON(t, ts, "GET", path, member.token, nil, &got); code != http.StatusOK || got.MessageID != n {
		t.Fatalf("закреплённое у участника: %d %+v", code, got)
	}
	if e := eventsOf(t, srv, member.id, "message.pinned"); e != 1 {
		t.Fatalf("событий «закреплено» у участника %d, ожидалось 1", e)
	}
	if code := authedJSON(t, ts, "DELETE", path, owner.token, nil, nil); code != http.StatusOK {
		t.Fatalf("снятие: %d", code)
	}
	got = pinAnswer{}
	if code := authedJSON(t, ts, "GET", path, member.token, nil, &got); code != http.StatusOK || got.MessageID != 0 {
		t.Fatalf("после снятия: %d %+v", code, got)
	}
}

func TestЗакреплениеКанала(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995590301")
	reader := registerDevice(t, ts, "+79995590302")
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]string{"title": "Закрепы"}, &created); code != http.StatusCreated {
		t.Fatalf("канал: %d", code)
	}
	ch := created.ChannelID
	var posted struct {
		PostID int64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts",
		map[string]any{"text": "запись"}, &posted); code != http.StatusCreated {
		t.Fatalf("запись: %d", code)
	}
	path := "/api/v1/channels/" + ch + "/pin"
	if code := authedJSON(t, ts, "PUT", path, reader.token, map[string]any{"message_id": posted.PostID}, nil); code != http.StatusForbidden {
		t.Fatalf("читатель закрепил: ожидался 403, получен %d", code)
	}
	if code := authedJSON(t, ts, "PUT", path, owner.token, map[string]any{"message_id": posted.PostID}, nil); code != http.StatusOK {
		t.Fatalf("закрепление владельцем: %d", code)
	}
	var got pinAnswer
	if code := authedJSON(t, ts, "GET", path, reader.token, nil, &got); code != http.StatusOK || got.MessageID != posted.PostID {
		t.Fatalf("закреплённое у читателя: %d %+v", code, got)
	}
	if code := authedJSON(t, ts, "DELETE", fmt.Sprintf("/api/v1/channels/%s/posts/%d", ch, posted.PostID), owner.token, nil, nil); code/100 != 2 {
		t.Fatalf("удаление записи: %d", code)
	}
	got = pinAnswer{}
	if code := authedJSON(t, ts, "GET", path, reader.token, nil, &got); code != http.StatusOK || got.MessageID != 0 {
		t.Fatalf("после удаления закреплённого: %d %+v", code, got)
	}
}

func TestОписаниеГруппыТолькоОкном(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79995590401")
	member := registerDevice(t, ts, "+79995590402")
	outsider := registerDevice(t, ts, "+79995590403")
	groupID := createPublicGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	send := func(id string, level int16) int64 {
		code, resp := sendLeveled(t, ts, owner, groupID, id, level, []byte("x"))
		if code != http.StatusCreated {
			t.Fatalf("сообщение уровня %d: %d", level, code)
		}
		var n int64
		_ = json.Unmarshal(resp["message_id"], &n)
		return n
	}
	first := send("eeeeeeee-4444-0000-0000-000000000001", 0)
	second := send("eeeeeeee-4444-0000-0000-000000000002", 0)
	usual := send("eeeeeeee-4444-0000-0000-000000000003", 1)

	// Уровень 0 не рассылается: участник получил событие только об обычном.
	if e := eventsOf(t, srv, member.id, "message.group"); e != 1 {
		t.Fatalf("событий о сообщениях у участника %d, ожидалось 1 — описание не рассылается", e)
	}
	// История с no_showcase=1 — без описания; без параметра — как прежде (установленные клиенты).
	var hist struct {
		Messages []struct {
			MessageID int64 `json:"message_id"`
		} `json:"messages"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/messages?no_showcase=1", member.token, nil, &hist); code != http.StatusOK {
		t.Fatalf("история: %d", code)
	}
	if len(hist.Messages) != 1 || hist.Messages[0].MessageID != usual {
		t.Fatalf("история без описания: %+v", hist.Messages)
	}
	if got := levelsOf(t, ts, member.token, groupID); len(got) != 3 {
		t.Fatalf("история без параметра: %v, ожидалось три сообщения", got)
	}
	// Описание видит и не вступивший — от старых к новым.
	var sc struct {
		Super    int64 `json:"super"`
		Messages []struct {
			MessageID int64 `json:"message_id"`
		} `json:"messages"`
	}
	path := "/api/v1/groups/" + groupID + "/showcase"
	if code := authedJSON(t, ts, "GET", path, outsider.token, nil, &sc); code != http.StatusOK {
		t.Fatalf("описание постороннему: %d", code)
	}
	if len(sc.Messages) != 2 || sc.Messages[0].MessageID != first || sc.Messages[1].MessageID != second || sc.Super != 0 {
		t.Fatalf("описание: %+v", sc)
	}
	// Суперсообщение: только владелец и только уровень 0.
	if code := authedJSON(t, ts, "PUT", path+"/super", member.token, map[string]any{"message_id": second}, nil); code != http.StatusForbidden {
		t.Fatalf("суперсообщение участником: ожидался 403, получен %d", code)
	}
	if code := authedJSON(t, ts, "PUT", path+"/super", owner.token, map[string]any{"message_id": usual}, nil); code != http.StatusConflict {
		t.Fatalf("суперсообщение уровня 1: ожидался 409, получен %d", code)
	}
	if code := authedJSON(t, ts, "PUT", path+"/super", owner.token, map[string]any{"message_id": second}, nil); code != http.StatusOK {
		t.Fatalf("суперсообщение владельцем: %d", code)
	}
	sc.Super = 0
	if code := authedJSON(t, ts, "GET", path, outsider.token, nil, &sc); code != http.StatusOK || sc.Super != second {
		t.Fatalf("суперсообщение в описании: %d %+v", code, sc)
	}
	if code := authedJSON(t, ts, "DELETE", path+"/super", owner.token, nil, nil); code != http.StatusOK {
		t.Fatalf("снятие суперсообщения: %d", code)
	}
}

func TestОписаниеНеБольше50(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995590501")
	reader := registerDevice(t, ts, "+79995590502")
	community := makeCommunity(t, ts, owner, "Описание")
	path := "/api/v1/communities/" + community + "/messages"
	for i := 0; i < 50; i++ {
		if code := authedJSON(t, ts, "POST", path, owner.token, map[string]any{"text": fmt.Sprintf("часть %d", i)}, nil); code != http.StatusCreated {
			t.Fatalf("описание %d: %d", i, code)
		}
	}
	if code := authedJSON(t, ts, "POST", path, owner.token, map[string]any{"text": "51-е"}, nil); code != http.StatusConflict {
		t.Fatalf("51-е сообщение уровня 0: ожидался 409, получен %d", code)
	}
	var sc struct {
		Messages []struct {
			MessageID int64 `json:"message_id"`
		} `json:"messages"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/communities/"+community+"/showcase", reader.token, nil, &sc); code != http.StatusOK || len(sc.Messages) != 50 {
		t.Fatalf("описание сообщества: %d, %d сообщений", code, len(sc.Messages))
	}
	if sc.Messages[0].MessageID > sc.Messages[49].MessageID {
		t.Fatal("описание обязано идти от старых к новым")
	}
	// Суперсообщение у сообщества — так же, как у группы и канала.
	if code := authedJSON(t, ts, "PUT", "/api/v1/communities/"+community+"/showcase/super", owner.token,
		map[string]any{"message_id": sc.Messages[0].MessageID}, nil); code != http.StatusOK {
		t.Fatalf("суперсообщение сообщества: %d", code)
	}
}

func TestОписаниеКанала(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995590601")
	reader := registerDevice(t, ts, "+79995590602")
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]string{"title": "С описанием"}, &created); code != http.StatusCreated {
		t.Fatalf("канал: %d", code)
	}
	ch := created.ChannelID
	for _, level := range []int16{0, 1} {
		if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts",
			map[string]any{"text": "запись", "level": level}, nil); code != http.StatusCreated {
			t.Fatalf("запись уровня %d: %d", level, code)
		}
	}
	var feed struct {
		Posts []struct {
			Level int16 `json:"level"`
		} `json:"posts"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/channels/"+ch+"/posts?no_showcase=1", reader.token, nil, &feed); code != http.StatusOK {
		t.Fatalf("лента: %d", code)
	}
	if len(feed.Posts) != 1 || feed.Posts[0].Level != 1 {
		t.Fatalf("лента без описания: %+v", feed.Posts)
	}
	var sc struct {
		Posts []struct {
			Level int16 `json:"level"`
		} `json:"posts"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/channels/"+ch+"/showcase", reader.token, nil, &sc); code != http.StatusOK || len(sc.Posts) != 1 || sc.Posts[0].Level != 0 {
		t.Fatalf("описание канала: %d %+v", code, sc.Posts)
	}
}
