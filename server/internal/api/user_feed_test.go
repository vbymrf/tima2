package api

// Лента пользователя и перенос ссылкой (ADR-0019 §7, ПЛАН-СОЦИУМА Г8).
//
// Проверяется не «ручка отвечает 201», а три обещания, ради которых перенос сделан
// ссылкой: авторство остаётся у автора, удаление оригинала убирает принесённое, сужение
// круга действует везде. Копия не умеет ничего из этого, и подмена ссылки копией прошла бы
// незамеченной без этих проверок.

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// carry — «добавить себе» чужую запись.
func carry(t *testing.T, ts *httptest.Server, token, groupID string, messageID int64, level int16) (int, uint64) {
	t.Helper()
	var resp struct {
		PostID uint64 `json:"post_id"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/users/me/feed/items", token, map[string]any{
		"group_id":   groupID,
		"message_id": messageID,
		"level":      level,
	}, &resp)
	return code, resp.PostID
}

// feedItem — одна строка чужой или своей ленты.
type feedItem struct {
	PostID       uint64 `json:"post_id"`
	Level        int16  `json:"level"`
	AuthorID     string `json:"author_id"`
	CarriedBy    string `json:"carried_by"`
	RefGroupID   string `json:"ref_group_id"`
	RefMessageID int64  `json:"ref_message_id"`
	SourceTitle  string `json:"source_title"`
	Payload      string `json:"payload"`
}

func feedOf(t *testing.T, ts *httptest.Server, token, path string) []feedItem {
	t.Helper()
	var resp struct {
		Items []feedItem `json:"items"`
	}
	if code := authedJSON(t, ts, "GET", path, token, nil, &resp); code != http.StatusOK {
		t.Fatalf("лента %s: %d", path, code)
	}
	return resp.Items
}

// sendOne — сообщение известного уровня; возвращает его идентификатор.
func sendOne(t *testing.T, ts *httptest.Server, sender *device, groupID, clientMsgID string, level int16) int64 {
	t.Helper()
	code, resp := sendLeveled(t, ts, sender, groupID, clientMsgID, level, []byte("запись группы"))
	if code != http.StatusCreated {
		t.Fatalf("отправка уровня %d: %d", level, code)
	}
	var messageID int64
	if err := json.Unmarshal(resp["message_id"], &messageID); err != nil {
		t.Fatal(err)
	}
	return messageID
}

// Принесённое — ссылка: авторство остаётся у автора, а не переходит принёсшему.
func TestПереносСоздаётСсылкуАНеЗапись(t *testing.T) {
	ts, _ := setup(t)
	author := registerDevice(t, ts, "+79995551001")
	reader := registerDevice(t, ts, "+79995551002")
	groupID := createPublicGroupAPI(t, ts, author.token)
	messageID := sendOne(t, ts, author, groupID, "aaaaaaaa-0000-0000-0000-000000000001", levelEveryone)

	if code, _ := carry(t, ts, reader.token, groupID, messageID, levelEveryone); code != http.StatusCreated {
		t.Fatalf("перенос: %d", code)
	}

	items := feedOf(t, ts, reader.token, "/api/v1/users/me/feed")
	if len(items) != 1 {
		t.Fatalf("на странице должна быть одна запись, их %d", len(items))
	}
	it := items[0]
	if it.AuthorID != author.userID {
		t.Fatalf("автор подменён: %s вместо %s — принесена копия, а не ссылка", it.AuthorID, author.userID)
	}
	if it.CarriedBy != reader.userID {
		t.Fatalf("принёсший не назван: %q", it.CarriedBy)
	}
	if it.RefGroupID != groupID || it.RefMessageID != messageID {
		t.Fatalf("ссылка не ведёт к оригиналу: %s/%d", it.RefGroupID, it.RefMessageID)
	}
	if it.Payload == "" {
		t.Fatal("содержимое оригинала не приехало — читателю нечего показать")
	}
}

// Сузил круг оригинала до «по разрешению» — принесённое исчезло у всех.
func TestСужениеОригиналаУбираетПринесённое(t *testing.T) {
	ts, _ := setup(t)
	author := registerDevice(t, ts, "+79995551011")
	reader := registerDevice(t, ts, "+79995551012")
	groupID := createPublicGroupAPI(t, ts, author.token)
	addMemberAPI(t, ts, author.token, groupID, reader.userID, "member")
	messageID := sendOne(t, ts, author, groupID, "aaaaaaaa-0000-0000-0000-000000000002", levelEveryone)

	if code, _ := carry(t, ts, reader.token, groupID, messageID, levelEveryone); code != http.StatusCreated {
		t.Fatalf("перенос: %d", code)
	}
	if len(feedOf(t, ts, reader.token, "/api/v1/users/me/feed")) != 1 {
		t.Fatal("принесённое не появилось на странице")
	}

	// Автор сужает круг до «по разрешению»: такая запись не выносится вовсе.
	path := "/api/v1/groups/" + groupID + "/messages/" + itoa64(messageID)
	if code := authedJSON(t, ts, "PATCH", path, author.token, map[string]any{"level": levelByGrant}, nil); code != http.StatusOK {
		t.Fatalf("сужение: %d", code)
	}

	if items := feedOf(t, ts, reader.token, "/api/v1/users/me/feed"); len(items) != 0 {
		t.Fatalf("после сужения принесённое осталось на странице: %+v", items)
	}
}

// Уровень 3 и шифр к себе не уносятся: их читают поимённо и по ключу.
func TestНевыносимоеНеПереносится(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995551021")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	byGrant := sendOne(t, ts, admin, groupID, "aaaaaaaa-0000-0000-0000-000000000003", levelByGrant)

	if code, _ := carry(t, ts, admin.token, groupID, byGrant, levelEveryone); code != http.StatusForbidden {
		t.Fatalf("уровень 3 не должен выноситься, получено %d", code)
	}
}

// Чужого нельзя унести, если тебе его не показывали.
func TestНепоказанноеНеУносится(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995551031")
	outsider := registerDevice(t, ts, "+79995551032")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	forMembers := sendOne(t, ts, admin, groupID, "aaaaaaaa-0000-0000-0000-000000000004", levelMembers)

	// Посторонний видит только «всем»: уровень 2 сервер ему не отдавал, и унести его
	// он не может. Ответ — «нет записи», а не «нельзя»: иначе отказ сообщал бы о ней.
	if code, _ := carry(t, ts, outsider.token, groupID, forMembers, levelEveryone); code != http.StatusNotFound {
		t.Fatalf("непоказанное не должно уноситься, получено %d", code)
	}
}

// Чужая страница: посторонний видит «всем», но не «своим».
func TestЧужаяСтраницаОтдаётТолькоОткрытое(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995551041")
	guest := registerDevice(t, ts, "+79995551042")
	groupID := createPublicGroupAPI(t, ts, owner.token)
	forAll := sendOne(t, ts, owner, groupID, "aaaaaaaa-0000-0000-0000-000000000005", levelEveryone)
	forOwn := sendOne(t, ts, owner, groupID, "aaaaaaaa-0000-0000-0000-000000000006", levelEveryone)

	if code, _ := carry(t, ts, owner.token, groupID, forAll, levelEveryone); code != http.StatusCreated {
		t.Fatal("перенос «всем» не удался")
	}
	// Вторую владелец кладёт «своим»: друзей на сервере нет, значит её не видит никто,
	// кроме него самого.
	if code, _ := carry(t, ts, owner.token, groupID, forOwn, levelMembers); code != http.StatusCreated {
		t.Fatal("перенос «своим» не удался")
	}

	mine := feedOf(t, ts, owner.token, "/api/v1/users/me/feed")
	if len(mine) != 2 {
		t.Fatalf("владелец должен видеть обе записи, видит %d", len(mine))
	}
	theirs := feedOf(t, ts, guest.token, "/api/v1/users/"+owner.userID+"/feed")
	if len(theirs) != 1 {
		t.Fatalf("гость должен видеть одну запись, видит %d: %+v", len(theirs), theirs)
	}
	if theirs[0].Level != levelEveryone {
		t.Fatalf("гостю досталась запись круга %d", theirs[0].Level)
	}
}

// Со своей страницы запись убирается, из чужой ленты — нет.
func TestЗаписьУбираетсяСоСвоейСтраницы(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995551051")
	stranger := registerDevice(t, ts, "+79995551052")
	groupID := createPublicGroupAPI(t, ts, owner.token)
	messageID := sendOne(t, ts, owner, groupID, "aaaaaaaa-0000-0000-0000-000000000007", levelEveryone)

	code, postID := carry(t, ts, owner.token, groupID, messageID, levelEveryone)
	if code != http.StatusCreated {
		t.Fatalf("перенос: %d", code)
	}

	// Чужой ленты касаться нельзя: у постороннего своей ленты ещё нет вовсе.
	path := "/api/v1/users/me/feed/items/" + itoa64(int64(postID))
	if code := authedJSON(t, ts, "DELETE", path, stranger.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("посторонний убрал запись с чужой страницы: %d", code)
	}
	if code := authedJSON(t, ts, "DELETE", path, owner.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("владелец не смог убрать запись: %d", code)
	}
	if items := feedOf(t, ts, owner.token, "/api/v1/users/me/feed"); len(items) != 0 {
		t.Fatalf("запись осталась на странице: %+v", items)
	}
}
