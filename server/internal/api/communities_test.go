package api

import (
	"net/http/httptest"
	"testing"
)

// Сообщества: связывание готового (ПЛАН-СООБЩЕСТВ С1…С3).
//
// Проверяется главное обещание плана: связывание — это одна ссылка, а не переезд.
// Переписка группы после внесения обязана остаться той же строка в строку.

type communityPageAnswer struct {
	Community struct {
		CommunityID string `json:"community_id"`
		Title       string `json:"title"`
		Subscribed  bool   `json:"subscribed"`
		Owner       bool   `json:"owner"`
		Admin       bool   `json:"admin"`
	} `json:"community"`
	Items []struct {
		Kind     string `json:"kind"`
		ID       string `json:"id"`
		Title    string `json:"title"`
		Personal bool   `json:"personal"`
	} `json:"items"`
	Description []struct {
		MessageID int64    `json:"message_id"`
		Nodes     []string `json:"nodes"`
		Level     int16    `json:"level"`
	} `json:"description"`
}

// makeGroup — группа нужного вида: `public` — обычная, `private` — личная.
func makeGroup(t *testing.T, ts *httptest.Server, token, kind string) string {
	t.Helper()
	var resp struct {
		GroupID string `json:"group_id"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups", token,
		map[string]any{"kind": kind, "title": "группа " + kind}, &resp); code != 201 {
		t.Fatalf("создание группы %s: %d", kind, code)
	}
	return resp.GroupID
}

func makeCommunity(t *testing.T, ts *httptest.Server, owner *device, title string) string {
	t.Helper()
	var created struct {
		CommunityID string `json:"community_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities",
		map[string]any{"title": title}, &created); code != 201 {
		t.Fatalf("создание сообщества: %d", code)
	}
	return created.CommunityID
}

func TestСвязываниеНеТрогаетПерепискуГруппы(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000200")

	groupID := makeGroup(t, ts, owner.token, "public")
	if code, _ := sendGroupMessage(t, ts, owner, groupID, groupMsg{
		ClientMsgID: "11111111-0000-0000-0000-000000000001",
		Payload:     []byte("было сказано до сообщества"),
	}, false); code != 201 {
		t.Fatalf("сообщение в группу: %d", code)
	}
	before := fetchGroupMessages(t, ts, owner.token, groupID, "")

	community := makeCommunity(t, ts, owner, "Ядро")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/items",
		map[string]any{"kind": "group", "id": groupID}, nil); code != 201 {
		t.Fatalf("связывание группы: %d", code)
	}

	after := fetchGroupMessages(t, ts, owner.token, groupID, "")
	if len(before) != len(after) || len(after) == 0 {
		t.Fatalf("переписка изменилась: было %d, стало %d", len(before), len(after))
	}
	// Ни одной строки содержимого связывание не трогает — это и есть смысл сообщества.
	if string(before[0]["message_id"]) != string(after[0]["message_id"]) ||
		string(before[0]["payload"]) != string(after[0]["payload"]) {
		t.Fatalf("сообщение изменилось: %v → %v", before[0], after[0])
	}

	var page communityPageAnswer
	if code := getAuthed(t, ts, owner.token, "/api/v1/communities/"+community, &page); code != 200 {
		t.Fatalf("страница сообщества: %d", code)
	}
	if len(page.Items) != 1 || page.Items[0].ID != groupID || page.Items[0].Kind != "group" {
		t.Fatalf("состав: %+v", page.Items)
	}
	if !page.Community.Owner || !page.Community.Subscribed {
		t.Fatal("владелец обязан быть подписан на своё сообщество со дня основания")
	}
}

func TestЧужуюГруппуСвязатьНельзя(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000201")
	outsider := registerDevice(t, ts, "+79990000202")

	community := makeCommunity(t, ts, owner, "Ядро")
	foreign := makeGroup(t, ts, outsider.token, "public")

	// Право — владелец сообщества И владелец элемента одновременно (развилка С-2).
	// Иначе связывание становится способом присвоить чужую группу.
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/items",
		map[string]any{"kind": "group", "id": foreign}, nil); code != 403 {
		t.Fatalf("чужая группа связана: %d, ожидалось 403", code)
	}
}

func TestЭлементНеБываетВДвухСообществах(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000203")

	first := makeCommunity(t, ts, owner, "Первое")
	second := makeCommunity(t, ts, owner, "Второе")
	groupID := makeGroup(t, ts, owner.token, "public")

	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+first+"/items",
		map[string]any{"kind": "group", "id": groupID}, nil); code != 201 {
		t.Fatalf("первое связывание: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+second+"/items",
		map[string]any{"kind": "group", "id": groupID}, nil); code != 409 {
		t.Fatalf("группа попала во второе сообщество: %d, ожидалось 409", code)
	}

	// Отвязанная возвращается в отдельное состояние и связывается заново.
	if code := postAuthed(t, ts, owner.token, "DELETE",
		"/api/v1/communities/"+first+"/items/group/"+groupID, nil, nil); code != 204 {
		t.Fatalf("отвязывание: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+second+"/items",
		map[string]any{"kind": "group", "id": groupID}, nil); code != 201 {
		t.Fatalf("повторное связывание после отвязывания: %d", code)
	}
}

func TestЛичнаяГруппаВСоставеНеПоказываетсяПостороннему(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000204")
	outsider := registerDevice(t, ts, "+79990000205")

	community := makeCommunity(t, ts, owner, "Ядро")
	personal := makeGroup(t, ts, owner.token, "private")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/items",
		map[string]any{"kind": "group", "id": personal}, nil); code != 201 {
		t.Fatalf("связывание личной группы: %d", code)
	}

	var mine communityPageAnswer
	if code := getAuthed(t, ts, owner.token, "/api/v1/communities/"+community, &mine); code != 200 {
		t.Fatalf("своя страница: %d", code)
	}
	if len(mine.Items) != 1 || !mine.Items[0].Personal {
		t.Fatalf("владелец обязан видеть свой состав: %+v", mine.Items)
	}

	var theirs communityPageAnswer
	if code := getAuthed(t, ts, outsider.token, "/api/v1/communities/"+community, &theirs); code != 200 {
		t.Fatalf("чужая страница: %d", code)
	}
	// Личная группа не ищется никогда (ADR-0018 п. 5): показать её постороннему значило
	// бы сообщить о её существовании.
	if len(theirs.Items) != 0 {
		t.Fatalf("посторонний видит личную группу: %+v", theirs.Items)
	}
}

func TestОписаниеСообществаЭтоСообщенияУровня0(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000206")
	outsider := registerDevice(t, ts, "+79990000207")
	community := makeCommunity(t, ts, owner, "Ядро")

	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/messages",
		map[string]any{"text": "мы делаем мессенджер"}, nil); code != 201 {
		t.Fatalf("описание: %d", code)
	}
	// Пишет владелец и админы; посторонний — нет.
	if code := postAuthed(t, ts, outsider.token, "POST", "/api/v1/communities/"+community+"/messages",
		map[string]any{"text": "а я тут напишу"}, nil); code != 403 {
		t.Fatalf("посторонний написал описание: %d", code)
	}

	var page communityPageAnswer
	if code := getAuthed(t, ts, outsider.token, "/api/v1/communities/"+community, &page); code != 200 {
		t.Fatalf("страница: %d", code)
	}
	if len(page.Description) != 1 || page.Description[0].Nodes[0] != "мы делаем мессенджер" {
		t.Fatalf("описание: %+v", page.Description)
	}
	// «Всем и всегда» — уровень 0, и он назван прямо.
	if page.Description[0].Level != levelPublicShowcase {
		t.Fatalf("круг описания: %d", page.Description[0].Level)
	}
}

func TestПодпискаНаСообществоИРоли(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000208")
	reader := registerDevice(t, ts, "+79990000209")
	community := makeCommunity(t, ts, owner, "Ядро")

	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/communities/"+community+"/subscribe", nil, nil); code != 200 {
		t.Fatalf("подписка: %d", code)
	}
	var mine struct {
		Communities []struct {
			CommunityID string `json:"community_id"`
			Admin       bool   `json:"admin"`
		} `json:"communities"`
	}
	if code := getAuthed(t, ts, reader.token, "/api/v1/communities", &mine); code != 200 || len(mine.Communities) != 1 {
		t.Fatalf("свои сообщества: %d, %+v", code, mine.Communities)
	}
	if mine.Communities[0].Admin {
		t.Fatal("подписка не делает админом")
	}

	// Роли раздаёт владелец, и только он.
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/communities/"+community+"/admins",
		map[string]any{"user_id": reader.userID}, nil); code != 403 {
		t.Fatalf("подписчик назначил себя админом: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/admins",
		map[string]any{"user_id": reader.userID}, nil); code != 201 {
		t.Fatalf("назначение админа: %d", code)
	}
	// Админ сообщества пишет описание — и это всё, что даёт ему роль здесь.
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/communities/"+community+"/messages",
		map[string]any{"text": "от админа"}, nil); code != 201 {
		t.Fatalf("админ не смог написать описание: %d", code)
	}
}
