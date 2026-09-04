package api

// Уровень сообщения (ADR-0019): граница выдачи по правам, сужение и запрет расширения,
// инварианты вида группы, предел открытого текста.

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	timacrypto "tima/server/internal/crypto"
)

// createPublicGroupAPI — публичная группа: в ней шифра нет, значит уровни 0…3.
func createPublicGroupAPI(t *testing.T, ts *httptest.Server, token string) string {
	t.Helper()
	var resp struct {
		GroupID string `json:"group_id"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/groups", token,
		map[string]any{"kind": "public", "title": "публичная тестовая"}, &resp)
	if code != http.StatusCreated || resp.GroupID == "" {
		t.Fatalf("создание публичной группы: %d", code)
	}
	return resp.GroupID
}

// sendLeveled шлёт открытое сообщение с явным уровнем (gk_version нет: не шифр).
func sendLeveled(t *testing.T, ts *httptest.Server, sender *device, groupID string,
	clientMsgID string, level int16, payload []byte) (int, map[string]json.RawMessage) {
	t.Helper()
	const createdAt = 1_750_000_000_000
	cb := timacrypto.GroupMessageCanonicalBytes(timacrypto.GroupMessageMeta{
		GroupID:         groupID,
		SenderID:        sender.userID,
		SenderDevice:    sender.id,
		CreatedAtUnixMs: createdAt,
	}, payload)
	b64 := base64.RawURLEncoding
	body := map[string]any{
		"client_msg_id":      clientMsgID,
		"level":              level,
		"payload":            b64.EncodeToString(payload),
		"created_at_unix_ms": createdAt,
		"signature":          b64.EncodeToString(ed25519.Sign(sender.signKey, cb)),
	}
	var resp map[string]json.RawMessage
	code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/messages", sender.token, body, &resp)
	return code, resp
}

// levelsOf — уровни сообщений, которые видит этот токен.
func levelsOf(t *testing.T, ts *httptest.Server, token, groupID string) []int16 {
	t.Helper()
	var resp struct {
		Messages []struct {
			Level int16 `json:"level"`
		} `json:"messages"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/messages", token, nil, &resp); code != http.StatusOK {
		t.Fatalf("список сообщений: %d", code)
	}
	out := make([]int16, 0, len(resp.Messages))
	for _, m := range resp.Messages {
		out = append(out, m.Level)
	}
	return out
}

func содержит(levels []int16, v int16) bool {
	for _, l := range levels {
		if l == v {
			return true
		}
	}
	return false
}

// Участник видит до «вступившим», администрация — всё.
func TestУровеньГраницаВыдачи(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995550001")
	member := registerDevice(t, ts, "+79995550002")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")

	for _, c := range []struct {
		id    string
		level int16
	}{
		{"11111111-1111-1111-1111-111111111111", levelPublicShowcase},
		{"22222222-2222-2222-2222-222222222222", levelEveryone},
		{"33333333-3333-3333-3333-333333333333", levelMembers},
		{"44444444-4444-4444-4444-444444444444", levelByGrant},
	} {
		if code, _ := sendLeveled(t, ts, admin, groupID, c.id, c.level, []byte("уровень")); code != http.StatusCreated {
			t.Fatalf("отправка уровня %d: %d", c.level, code)
		}
	}

	got := levelsOf(t, ts, member.token, groupID)
	if len(got) != 3 {
		t.Fatalf("участник должен видеть три сообщения (0,1,2), а видит %d: %v", len(got), got)
	}
	if содержит(got, levelByGrant) {
		t.Fatal("участнику досталcя уровень 3 без разрешения")
	}
	if adminSees := levelsOf(t, ts, admin.token, groupID); len(adminSees) != 4 {
		t.Fatalf("администрация должна видеть все четыре, видит %d: %v", len(adminSees), adminSees)
	}
}

// Уровень сужается и не расширяется — никем, включая владельца.
func TestУровеньТолькоСужается(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995550001")
	groupID := createPublicGroupAPI(t, ts, admin.token)

	code, resp := sendLeveled(t, ts, admin, groupID, "55555555-5555-5555-5555-555555555555", levelEveryone, []byte("текст"))
	if code != http.StatusCreated {
		t.Fatalf("отправка: %d", code)
	}
	var messageID int64
	if err := json.Unmarshal(resp["message_id"], &messageID); err != nil {
		t.Fatal(err)
	}
	path := "/api/v1/groups/" + groupID + "/messages/" + itoa64(messageID)

	// сузить 1 → 2 можно
	if code := authedJSON(t, ts, "PATCH", path, admin.token, map[string]any{"level": levelMembers}, nil); code != http.StatusOK {
		t.Fatalf("сужение 1 → 2: %d", code)
	}
	// расширить 2 → 1 нельзя даже владельцу
	if code := authedJSON(t, ts, "PATCH", path, admin.token, map[string]any{"level": levelEveryone}, nil); code != http.StatusConflict {
		t.Fatalf("расширение 2 → 1 должно отклоняться, получено %d", code)
	}
	// и тот же уровень повторно — тоже отказ: это не сужение
	if code := authedJSON(t, ts, "PATCH", path, admin.token, map[string]any{"level": levelMembers}, nil); code != http.StatusConflict {
		t.Fatalf("повтор того же уровня должен отклоняться, получено %d", code)
	}
	if got := levelsOf(t, ts, admin.token, groupID); len(got) != 1 || got[0] != levelMembers {
		t.Fatalf("уровень должен остаться 2, а он %v", got)
	}
}

// Чужое сообщение сужает админ; участнику это недоступно.
func TestУровеньЧужогоМеняетАдмин(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995550001")
	member := registerDevice(t, ts, "+79995550002")
	other := registerDevice(t, ts, "+79995550003")
	groupID := createPublicGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")
	addMemberAPI(t, ts, admin.token, groupID, other.userID, "member")

	code, resp := sendLeveled(t, ts, member, groupID, "66666666-6666-6666-6666-666666666666", levelEveryone, []byte("моё"))
	if code != http.StatusCreated {
		t.Fatalf("отправка участником: %d", code)
	}
	var messageID int64
	if err := json.Unmarshal(resp["message_id"], &messageID); err != nil {
		t.Fatal(err)
	}
	path := "/api/v1/groups/" + groupID + "/messages/" + itoa64(messageID)

	if code := authedJSON(t, ts, "PATCH", path, other.token, map[string]any{"level": levelMembers}, nil); code != http.StatusForbidden {
		t.Fatalf("участник не может сужать чужое, получено %d", code)
	}
	if code := authedJSON(t, ts, "PATCH", path, member.token, map[string]any{"level": levelMembers}, nil); code != http.StatusOK {
		t.Fatalf("автор сужает своё: %d", code)
	}
	if code := authedJSON(t, ts, "PATCH", path, admin.token, map[string]any{"level": levelByGrant}, nil); code != http.StatusOK {
		t.Fatalf("админ сужает чужое: %d", code)
	}
}

// Личная группа знает только -1 и 0; публичная не знает -1.
func TestУровниПоВидуГруппы(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995550001")

	private := createGroupAPI(t, ts, admin.token)
	if code, _ := sendLeveled(t, ts, admin, private, "77777777-7777-7777-7777-777777777777", levelEveryone, []byte("наружу")); code != http.StatusBadRequest {
		t.Fatalf("уровень 1 в личной группе должен отклоняться, получено %d", code)
	}
	if code, _ := sendLeveled(t, ts, admin, private, "88888888-8888-8888-8888-888888888888", levelPublicShowcase, []byte("описание")); code != http.StatusCreated {
		t.Fatalf("уровень 0 в личной группе должен приниматься, получено %d", code)
	}

	public := createPublicGroupAPI(t, ts, admin.token)
	if code, _ := sendLeveled(t, ts, admin, public, "99999999-9999-9999-9999-999999999999", levelSecret, []byte("шифр")); code != http.StatusBadRequest {
		t.Fatalf("уровень -1 в публичной группе должен отклоняться, получено %d", code)
	}
}

// Открытый текст ограничен по размеру: иначе сервер становится хостингом.
func TestОткрытоеСообщениеОграниченоПоРазмеру(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79995550001")
	groupID := createPublicGroupAPI(t, ts, admin.token)

	big := make([]byte, maxPlainPayloadBytes+1)
	for i := range big {
		big[i] = 'a'
	}
	if code, _ := sendLeveled(t, ts, admin, groupID, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", levelEveryone, big); code != http.StatusBadRequest {
		t.Fatalf("сообщение больше предела должно отклоняться, получено %d", code)
	}
	ok := big[:maxPlainPayloadBytes]
	if code, _ := sendLeveled(t, ts, admin, groupID, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", levelEveryone, ok); code != http.StatusCreated {
		t.Fatalf("сообщение ровно по пределу должно приниматься, получено %d", code)
	}
}

func itoa64(v int64) string {
	if v == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	return string(buf[i:])
}

// Открытое сообщение не зовёт ротацию ключа.
//
// Инвариант ADR-0017 §2 говорит про отправку, которая пользовалась ключом: escrow-блоб у
// группы один на версию GK, и восстанавливать по ордеру нужно то, что закрыто. Описание
// личной группы — сообщение уровня 0 — ключа не касается, и гнать из-за него тихую группу
// на фан-аут обёрток незачем.
func TestОткрытоеСообщениеНеЗовётРотацию(t *testing.T) {
	ts, srv := setup(t)
	admin := registerDevice(t, ts, "+79995551001")
	groupID := createGroupAPI(t, ts, admin.token) // личная: в ней бывают -1 и 0

	before := countRotationEvents(t, srv, admin.id)

	if code, _ := sendLeveled(t, ts, admin, groupID, "e0000000-0000-0000-0000-000000000001",
		levelPublicShowcase, []byte("описание группы")); code != http.StatusCreated {
		t.Fatalf("описание уровня 0: %d", code)
	}

	if after := countRotationEvents(t, srv, admin.id); after != before {
		t.Fatalf("открытое сообщение позвало ротацию: событий было %d, стало %d", before, after)
	}
}

func countRotationEvents(t *testing.T, srv *Server, deviceID string) int {
	t.Helper()
	events, err := srv.Store.ListDeviceEvents(context.Background(), deviceID, 0, 500)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, e := range events {
		if e.EventType == "group.rotation_needed" {
			n++
		}
	}
	return n
}
