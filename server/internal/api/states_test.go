package api

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// Лента состояний (ПЛАН-(ОП)): «доставлено» на подтверждение, «прочитано» ручкой,
// «печатает» в список собеседника, «в сети» смотрящему.

type stateRow struct {
	Kind        string `json:"kind"`
	Rev         int64  `json:"rev"`
	ChatID      string `json:"chat_id"`
	PeerID      string `json:"peer_id"`
	FromID      string `json:"from_id"`
	UserID      string `json:"user_id"`
	DeliveredMs int64  `json:"delivered_ms"`
	ReadMs      int64  `json:"read_ms"`
	UntilMs     int64  `json:"until_ms"`
	Online      bool   `json:"online"`
	LastSeenMs  int64  `json:"last_seen_ms"`
}

type statesPage struct {
	Rev    int64      `json:"rev"`
	States []stateRow `json:"states"`
}

func sendFrame(t *testing.T, conn *websocket.Conn, frame map[string]any) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	raw, _ := json.Marshal(frame)
	if err := conn.Write(ctx, websocket.MessageText, raw); err != nil {
		t.Fatal(err)
	}
}

// waitState ждёт строку нужного вида в ленте человека: кадры живого канала обрабатываются
// своим потоком, и ответ ленты появляется не мгновенно.
func waitState(t *testing.T, get func() statesPage, want func(stateRow) bool) stateRow {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		for _, s := range get().States {
			if want(s) {
				return s
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("строки ленты состояний не дождались: %+v", get().States)
	return stateRow{}
}

// TestДоставленоИПрочитаноУОтправителя — получатель забрал сообщение и подтвердил: у отправителя
// «доставлено до» времени написания; открыл переписку — «прочитано».
func TestДоставленоИПрочитаноУОтправителя(t *testing.T) {
	ts, _ := setupWithEvents(t)
	sender := registerDevice(t, ts, "+79993340001")
	recipient := registerDevice(t, ts, "+79993340002")
	get := func(d *device) statesPage {
		var page statesPage
		getAuthed(t, ts, d.token, "/api/v1/users/me/states?after=0", &page)
		return page
	}

	conn := dialWS(t, ts, recipient.token)
	env := sealEnvelope(t, sender, []*device{recipient}, 3401, []byte("привет"))
	resp := post(t, ts, env, sender.token, "eeeeeeee-0000-0000-0000-000000003401")
	resp.Body.Close()
	frame := readByPoke(t, conn, "message.new")
	var eventID int64
	_ = json.Unmarshal(frame["event_id"], &eventID)

	if len(get(sender).States) != 0 {
		t.Fatalf("доставлено раньше подтверждения: %+v", get(sender).States)
	}
	sendFrame(t, conn, map[string]any{"event": "ack", "event_id": eventID})
	chat := chatIDFor(sender, []*device{recipient})
	got := waitState(t, func() statesPage { return get(sender) }, func(s stateRow) bool {
		return s.Kind == "receipt" && s.ChatID == chat && s.DeliveredMs > 0
	})
	if got.PeerID != recipient.userID || got.ReadMs != 0 {
		t.Fatalf("доставлено не то: %+v", got)
	}

	// Прочитал: отметка только вверх и только в своей переписке.
	if code := jsonAuth(t, ts, "PUT", "/api/v1/chats/"+chat+"/read", recipient.token,
		map[string]any{"up_to_ms": got.DeliveredMs}, nil); code != 204 {
		t.Fatalf("прочитано: %d", code)
	}
	read := waitState(t, func() statesPage { return get(sender) }, func(s stateRow) bool {
		return s.Kind == "receipt" && s.ReadMs == got.DeliveredMs
	})
	if read.Rev <= got.Rev {
		t.Fatalf("номер ленты не вырос: %d → %d", got.Rev, read.Rev)
	}
	stranger := registerDevice(t, ts, "+79993340003")
	if code := jsonAuth(t, ts, "PUT", "/api/v1/chats/"+chat+"/read", stranger.token,
		map[string]any{"up_to_ms": 1}, nil); code != 404 {
		t.Fatalf("посторонний отметил чужую переписку: %d", code)
	}
}

// TestПечатаетВСписокСобеседника — кадр typing кладёт строку в ленту собеседника со сроком;
// отмена ставит срок в ноль.
func TestПечатаетВСписокСобеседника(t *testing.T) {
	ts, _ := setupWithEvents(t)
	anna := registerDevice(t, ts, "+79993340011")
	boris := registerDevice(t, ts, "+79993340012")
	get := func() statesPage {
		var page statesPage
		getAuthed(t, ts, boris.token, "/api/v1/users/me/states?after=0", &page)
		return page
	}
	conn := dialWS(t, ts, anna.token)
	chat := chatIDFor(anna, []*device{boris})
	sendFrame(t, conn, map[string]any{"event": "typing", "chat_id": chat, "to": boris.userID, "on": true})
	on := waitState(t, get, func(s stateRow) bool { return s.Kind == "typing" && s.FromID == anna.userID && s.UntilMs > 0 })
	if on.ChatID != chat || on.UntilMs < time.Now().UnixMilli() {
		t.Fatalf("печатает не то: %+v", on)
	}
	sendFrame(t, conn, map[string]any{"event": "typing", "chat_id": chat, "to": boris.userID, "on": false})
	waitState(t, get, func(s stateRow) bool { return s.Kind == "typing" && s.UntilMs == 0 })
}

// TestВСетиСмотрящему — смотрящий переписку сразу получает «в сети» собеседника и его смену;
// закрыл соединение — «не в сети» и «был(а)».
func TestВСетиСмотрящему(t *testing.T) {
	ts, _ := setupWithEvents(t)
	anna := registerDevice(t, ts, "+79993340021")
	boris := registerDevice(t, ts, "+79993340022")
	get := func() statesPage {
		var page statesPage
		getAuthed(t, ts, boris.token, "/api/v1/users/me/states?after=0", &page)
		return page
	}
	borisConn := dialWS(t, ts, boris.token)
	sendFrame(t, borisConn, map[string]any{"event": "watch", "user_id": anna.userID})
	waitState(t, get, func(s stateRow) bool { return s.Kind == "presence" && s.UserID == anna.userID && !s.Online })

	annaConn := dialWS(t, ts, anna.token)
	sendFrame(t, annaConn, map[string]any{"event": "presence", "on": true})
	on := waitState(t, get, func(s stateRow) bool { return s.Kind == "presence" && s.Online })
	if on.UntilMs < time.Now().UnixMilli() {
		t.Fatalf("«в сети» без срока: %+v", on)
	}

	annaConn.Close(websocket.StatusNormalClosure, "")
	off := waitState(t, get, func(s stateRow) bool { return s.Kind == "presence" && !s.Online && s.LastSeenMs > 0 })
	if off.UserID != anna.userID {
		t.Fatalf("не о том: %+v", off)
	}
}
