package api

import (
	"encoding/base64"
	"net/http"
	"testing"
)

// История на новом устройстве (ИУ1–ИУ3): новое своё устройство узнаёт переписку и
// собеседника, старое отдаёт ему обёртки, и сервер выдаёт историю с эфемералом обёртки.
func TestPersonalChatsListAndHandedOverHistory(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRecord
	peer := registerDevice(t, ts, "+79990000321")
	old := registerDevice(t, ts, "+79990000322")
	resp := post(t, ts, sealEnvelope(t, peer, []*device{old}, 3201, []byte("до нового")), peer.token, "eeeeeeee-0000-0000-0000-000000003201")
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("отправка: %d", resp.StatusCode)
	}
	fresh := registerDevice(t, ts, "+79990000322")
	if fresh.userID != old.userID {
		t.Fatal("второе устройство того же номера — другая личность")
	}

	var list struct {
		Chats []struct {
			ChatID        string `json:"chat_id"`
			PeerID        string `json:"peer_id"`
			LastMessageID uint64 `json:"last_message_id"`
		} `json:"chats"`
	}
	if code := getAuthed(t, ts, fresh.token, "/api/v1/chats/personal", &list); code != 200 {
		t.Fatalf("список переписок: %d", code)
	}
	chat := personalChatID(peer.userID, old.userID)
	found := false
	for _, c := range list.Chats {
		if c.ChatID == chat && c.PeerID == peer.userID && c.LastMessageID == 3201 {
			found = true
		}
	}
	if !found {
		t.Fatalf("переписки с собеседником нет в списке: %+v", list.Chats)
	}

	// Обёрток для нового устройства ещё нет — истории ему не видно.
	var page struct {
		Messages []struct {
			MessageID uint64 `json:"message_id"`
			WrapEph   string `json:"wrap_ephemeral"`
		} `json:"messages"`
	}
	getAuthed(t, ts, fresh.token, "/api/v1/chats/"+chat+"/messages", &page)
	if len(page.Messages) != 0 {
		t.Fatalf("история до передачи: %d", len(page.Messages))
	}
	b64 := base64.RawURLEncoding
	eph := make([]byte, 32)
	eph[0] = 7
	code := postAuthed(t, ts, old.token, "POST", "/api/v1/chats/"+chat+"/recover/provide", map[string]any{
		"requester_device": fresh.id,
		"keys": []map[string]any{{"message_id": 3201, "sender_ephemeral_pub": b64.EncodeToString(eph),
			"wrapped": b64.EncodeToString(make([]byte, 24+16+32))}},
	}, nil)
	if code != http.StatusCreated {
		t.Fatalf("передача обёрток: %d", code)
	}
	getAuthed(t, ts, fresh.token, "/api/v1/chats/"+chat+"/messages", &page)
	if len(page.Messages) != 1 || page.Messages[0].WrapEph != b64.EncodeToString(eph) {
		t.Fatalf("история после передачи: %+v", page.Messages)
	}

	// Строгий режим: незаверенному список собеседников не отдаётся.
	srv.DeviceTrust = trustRequire
	if code := getAuthed(t, ts, fresh.token, "/api/v1/chats/personal", nil); code != http.StatusForbidden {
		t.Fatalf("незаверенное в строгом режиме: ожидался 403, получен %d", code)
	}
}

// «Сообщение недоступно, запросить» (заказчик 2026-10-06): заверенное устройство просит ключи
// переписки без подписи фразой — заверить его без фразы было нельзя; незаверенное — только с ней.
func TestChatRecoverCertifiedNeedsNoPhrase(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRecord
	owner := newTrustAccount()
	phone, code, why := registerProof(t, ts, "+79990000331", owner.identityPub(), owner.withAsk)
	if code != 201 {
		t.Fatalf("телефон хозяина: %d %s", code, why)
	}
	peer := registerDevice(t, ts, "+79990000332")
	resp := post(t, ts, sealEnvelope(t, peer, []*device{phone}, 3301, []byte("до компьютера")), peer.token, "eeeeeeee-0000-0000-0000-000000003301")
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("отправка: %d", resp.StatusCode)
	}
	chat := personalChatID(peer.userID, phone.userID)

	pc, code, why := registerProof(t, ts, "+79990000331", owner.identityPub(), owner.byPhrase)
	if code != 201 {
		t.Fatalf("компьютер по фразе: %d %s", code, why)
	}
	var out struct {
		Helpers int `json:"helpers"`
	}
	if code := postAuthed(t, ts, pc.token, "POST", "/api/v1/chats/"+chat+"/recover", map[string]any{}, &out); code != 200 || out.Helpers == 0 {
		t.Fatalf("заверенное без подписи: ожидался 200 с помощниками, получен %d, помощников %d", code, out.Helpers)
	}

	stranger, code, _ := registerProof(t, ts, "+79990000331", owner.identityPub(), nil)
	if code != 201 {
		t.Fatalf("незаверенное в «записывать»: %d", code)
	}
	if code := postAuthed(t, ts, stranger.token, "POST", "/api/v1/chats/"+chat+"/recover", map[string]any{}, nil); code != http.StatusForbidden {
		t.Fatalf("незаверенное без подписи: ожидался 403, получен %d", code)
	}
}
