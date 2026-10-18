package api

// Выдача одного сообщения по номеру — один механизм доступа по ссылке (ПЛАН-(РС) РС3):
// сообщение или заглушка «недоступно» по обычному правилу выдачи.

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

type byNumber struct {
	Message     map[string]any `json:"message"`
	Unavailable *struct {
		MessageID uint64 `json:"message_id"`
		Reason    string `json:"reason"`
		Level     *int16 `json:"level"`
	} `json:"unavailable"`
}

func fetchByNumber(t *testing.T, ts *httptest.Server, token, path string) byNumber {
	t.Helper()
	var out byNumber
	if code := authedJSON(t, ts, "GET", path, token, nil, &out); code != http.StatusOK {
		t.Fatalf("GET %s: %d", path, code)
	}
	return out
}

// Публичная группа: участник получает своё, выше права — заглушку с уровнем; нет — «missing».
func TestВыдачаПоНомеруПубличнаяГруппа(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995552001")
	member := registerDevice(t, ts, "+79995552002")
	outsider := registerDevice(t, ts, "+79995552003")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")
	forAll := sendOne(t, ts, admin, groupID, "bbbbbbbb-0000-0000-0000-000000000001", levelEveryone)
	forMembers := sendOne(t, ts, admin, groupID, "bbbbbbbb-0000-0000-0000-000000000002", levelMembers)
	byGrant := sendOne(t, ts, admin, groupID, "bbbbbbbb-0000-0000-0000-000000000003", levelByGrant)
	base := "/api/v1/groups/" + groupID + "/messages/"

	if got := fetchByNumber(t, ts, member.token, base+itoa64(forMembers)); got.Message == nil {
		t.Fatalf("участник не получил сообщение «своим»: %+v", got.Unavailable)
	}
	got := fetchByNumber(t, ts, member.token, base+itoa64(byGrant))
	if got.Message != nil || got.Unavailable == nil || got.Unavailable.Reason != unavailableLevel ||
		got.Unavailable.Level == nil || *got.Unavailable.Level != levelByGrant {
		t.Fatalf("уровень 3 без разрешения: ожидалась заглушка с уровнем 3, пришло %+v", got)
	}
	if got := fetchByNumber(t, ts, member.token, base+"999999"); got.Unavailable == nil || got.Unavailable.Reason != unavailableMissing {
		t.Fatalf("несуществующее: ожидалось missing, пришло %+v", got)
	}
	// Не вступивший: «всем» — да, «своим» — заглушка с уровнем 2 (ему есть что сделать: вступить).
	if got := fetchByNumber(t, ts, outsider.token, base+itoa64(forAll)); got.Message == nil {
		t.Fatalf("не вступивший не получил сообщение «всем»: %+v", got.Unavailable)
	}
	got = fetchByNumber(t, ts, outsider.token, base+itoa64(forMembers))
	if got.Unavailable == nil || got.Unavailable.Level == nil || *got.Unavailable.Level != levelMembers {
		t.Fatalf("не вступивший и «своим»: ожидалась заглушка с уровнем 2, пришло %+v", got)
	}
}

// Чужая личная группа и несуществующая группа неотличимы: заглушка без подробностей.
func TestВыдачаПоНомеруЛичнаяГруппаЗакрыта(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995552011")
	outsider := registerDevice(t, ts, "+79995552012")
	var resp struct {
		GroupID string `json:"group_id"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups", owner.token,
		map[string]any{"kind": "private", "title": "личная"}, &resp); code != http.StatusCreated {
		t.Fatalf("личная группа: %d", code)
	}
	closed := fetchByNumber(t, ts, outsider.token, "/api/v1/groups/"+resp.GroupID+"/messages/1")
	missing := fetchByNumber(t, ts, outsider.token, "/api/v1/groups/00000000-0000-0000-0000-000000000000/messages/1")
	for name, got := range map[string]byNumber{"личная": closed, "нет такой": missing} {
		if got.Unavailable == nil || got.Unavailable.Reason != unavailableClosed || got.Unavailable.Level != nil {
			t.Fatalf("%s: ожидалась заглушка closed без уровня, пришло %+v", name, got)
		}
	}
}

// Личная переписка: своё сообщение — да; чужому, у кого нет обёртки, — заглушка без подробностей.
func TestВыдачаПоНомеруЛичнаяПереписка(t *testing.T) {
	ts, _ := setup(t)
	sender := registerDevice(t, ts, "+79995552021")
	recipient := registerDevice(t, ts, "+79995552022")
	stranger := registerDevice(t, ts, "+79995552023")
	env := sealEnvelope(t, sender, []*device{recipient}, 2001, []byte("по номеру"))
	resp := post(t, ts, env, sender.token, "cccccccc-0000-0000-0000-000000002001")
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("POST /messages: %d", resp.StatusCode)
	}
	resp.Body.Close()
	path := "/api/v1/chats/" + chatIDFor(sender, []*device{recipient}) + "/messages/2001"

	if got := fetchByNumber(t, ts, recipient.token, path); got.Message == nil || got.Message["envelope"] == "" {
		t.Fatalf("получатель не получил сообщение по номеру: %+v", got.Unavailable)
	}
	got := fetchByNumber(t, ts, stranger.token, path)
	if got.Message != nil || got.Unavailable == nil || got.Unavailable.Reason != unavailableClosed {
		t.Fatalf("посторонний: ожидалась заглушка closed, пришло %+v", got)
	}
}

// Лента страницы: перенесённое с суженным оригиналом — без stubs пропадает, со stubs —
// заглушка без содержимого, с уровнем оригинала.
func TestЛентаЗаглушкаВместоПропажи(t *testing.T) {
	ts, _ := setup(t)
	author := registerDevice(t, ts, "+79995552031")
	reader := registerDevice(t, ts, "+79995552032")
	groupID := createPublicGroupAPI(t, ts, author.token)
	addMemberAPI(t, ts, author.token, groupID, reader.userID, "member")
	messageID := sendOne(t, ts, author, groupID, "dddddddd-0000-0000-0000-000000000001", levelEveryone)
	if code, _ := carry(t, ts, reader.token, groupID, messageID, levelEveryone); code != http.StatusCreated {
		t.Fatalf("перенос: %d", code)
	}
	path := "/api/v1/groups/" + groupID + "/messages/" + itoa64(messageID)
	if code := authedJSON(t, ts, "PATCH", path, author.token, map[string]any{"level": levelByGrant}, nil); code != http.StatusOK {
		t.Fatalf("сужение: %d", code)
	}

	if items := feedOf(t, ts, reader.token, "/api/v1/users/me/feed"); len(items) != 0 {
		t.Fatalf("без stubs поведение прежнее — строки нет, а пришло %+v", items)
	}
	var resp struct {
		Items []struct {
			PostID        uint64 `json:"post_id"`
			Unavailable   bool   `json:"unavailable"`
			OriginalLevel *int16 `json:"original_level"`
			Payload       string `json:"payload"`
		} `json:"items"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me/feed?stubs=1", reader.token, nil, &resp); code != http.StatusOK {
		t.Fatalf("лента со stubs: %d", code)
	}
	if len(resp.Items) != 1 || !resp.Items[0].Unavailable || resp.Items[0].Payload != "" ||
		resp.Items[0].OriginalLevel == nil || *resp.Items[0].OriginalLevel != levelByGrant {
		t.Fatalf("ожидалась одна заглушка без содержимого с уровнем 3, пришло %+v", resp.Items)
	}
}

// Канал: подписчику запись «своим» — да; постороннему — заглушка с уровнем 2.
func TestВыдачаПоНомеруКанал(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995552041")
	subscriber := registerDevice(t, ts, "+79995552042")
	outsider := registerDevice(t, ts, "+79995552043")
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]string{"title": "Канал по номеру"}, &created); code != http.StatusCreated {
		t.Fatalf("канал: %d", code)
	}
	ch := created.ChannelID
	if code := postAuthed(t, ts, subscriber.token, "POST", "/api/v1/channels/"+ch+"/subscribe", nil, nil); code != http.StatusOK {
		t.Fatalf("подписка: %d", code)
	}
	var post struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts",
		map[string]any{"text": "своим", "level": levelMembers}, &post); code != http.StatusCreated || post.PostID == 0 {
		t.Fatalf("запись: %d %+v", code, post)
	}
	path := "/api/v1/channels/" + ch + "/posts/" + itoa64(int64(post.PostID))
	if got := fetchByNumber(t, ts, subscriber.token, path); got.Message == nil {
		t.Fatalf("подписчик не получил запись «своим»: %+v", got.Unavailable)
	}
	got := fetchByNumber(t, ts, outsider.token, path)
	if got.Unavailable == nil || got.Unavailable.Level == nil || *got.Unavailable.Level != levelMembers {
		t.Fatalf("посторонний: ожидалась заглушка с уровнем 2, пришло %+v", got)
	}
}
