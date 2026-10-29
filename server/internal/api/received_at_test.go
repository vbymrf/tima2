package api

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

// Время приёма сервером (2026-10-29, беда «сообщения в прошлом»): часы отправителя могут спешить, поэтому
// сервер отдаёт своё время приёма — в ответе на отправку и в истории, у личных и у групп, — и клиент ставит
// сообщение на место по нему. Время в конверте (подписанное) при этом не меняется.
func TestReceivedAtIsServerTimeNotSenderClock(t *testing.T) {
	ts, _ := setup(t)
	sender := registerDevice(t, ts, "+79990002901")
	peer := registerDevice(t, ts, "+79990002902")
	before := time.Now().UnixMilli()

	// Личное: в ответе на отправку.
	resp := post(t, ts, sealEnvelope(t, sender, []*device{peer}, 2901, []byte("часы спешат")), sender.token, "eeeeeeee-0000-0000-0000-000000002901")
	var sent struct {
		MessageID  uint64 `json:"message_id"`
		ReceivedAt int64  `json:"received_at_unix_ms"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&sent); err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	after := time.Now().UnixMilli()
	if resp.StatusCode != http.StatusCreated || sent.ReceivedAt < before || sent.ReceivedAt > after {
		t.Fatalf("время приёма в ответе: %d, %d вне [%d, %d]", resp.StatusCode, sent.ReceivedAt, before, after)
	}

	// Личное: в истории получателя — то же время, с точностью до миллисекунды записи в базу.
	var page struct {
		Messages []struct {
			MessageID  uint64 `json:"message_id"`
			ReceivedAt int64  `json:"received_at_unix_ms"`
		} `json:"messages"`
	}
	chat := personalChatID(sender.userID, peer.userID)
	if code := getAuthed(t, ts, peer.token, "/api/v1/chats/"+chat+"/messages", &page); code != http.StatusOK || len(page.Messages) != 1 {
		t.Fatalf("история: %d, %d сообщений", code, len(page.Messages))
	}
	if d := page.Messages[0].ReceivedAt - sent.ReceivedAt; d < -1 || d > 1 {
		t.Fatalf("время в истории %d расходится с ответом %d", page.Messages[0].ReceivedAt, sent.ReceivedAt)
	}

	// Группа: конверт подписан временем 2025 года — сервер отдаёт своё.
	groupID := makeGroup(t, ts, sender.token, "public")
	code, reply := sendLeveled(t, ts, sender, groupID, "eeeeeeee-2901-0000-0000-000000000001", 1, openBody(t, "Группа"))
	if code != http.StatusCreated {
		t.Fatalf("сообщение в группу: %d", code)
	}
	var groupAt int64
	_ = json.Unmarshal(reply["received_at_unix_ms"], &groupAt)
	if groupAt < before || groupAt > time.Now().UnixMilli() {
		t.Fatalf("время приёма группы в ответе: %d", groupAt)
	}
	var list struct {
		Messages []struct {
			CreatedAt  int64 `json:"created_at_unix_ms"`
			ReceivedAt int64 `json:"received_at_unix_ms"`
		} `json:"messages"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/messages", sender.token, nil, &list); code != http.StatusOK || len(list.Messages) == 0 {
		t.Fatalf("история группы: %d", code)
	}
	got := list.Messages[0]
	if got.CreatedAt != 1_750_000_000_000 {
		t.Fatalf("подписанное время отправителя изменилось: %d", got.CreatedAt)
	}
	if d := got.ReceivedAt - groupAt; d < -1 || d > 1 {
		t.Fatalf("время в истории группы %d расходится с ответом %d", got.ReceivedAt, groupAt)
	}
}
