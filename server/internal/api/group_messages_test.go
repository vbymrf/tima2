package api

// Интеграционные тесты сообщений групп: SecretBox(GK) поверх ротации из
// groups_test, подпись по group_message_canonical_bytes, дедуп, ветки,
// slow mode, бан, live-доставка по WS.

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/crypto/nacl/secretbox"

	timacrypto "tima/server/internal/crypto"
)

type groupMsg struct {
	ClientMsgID     string
	Kind            uint32
	GKVersion       int32
	Payload         []byte
	ThreadRoot      int64
	ReplyTo         int64
	CreatedAtUnixMs int64
}

// sealGK шифрует plaintext групповым ключом (SecretBox, nonce‖box — как Kodium).
func sealGK(t *testing.T, gk [32]byte, plaintext []byte) []byte {
	t.Helper()
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		t.Fatal(err)
	}
	return secretbox.Seal(nonce[:], plaintext, &nonce, &gk)
}

// sendGroupMessage подписывает по канону и шлёт POST /groups/{id}/messages.
// tamper позволяет испортить подпись после сборки.
func sendGroupMessage(t *testing.T, ts *httptest.Server, sender *device, groupID string, m groupMsg, tamper bool) (int, map[string]json.RawMessage) {
	t.Helper()
	if m.CreatedAtUnixMs == 0 {
		m.CreatedAtUnixMs = 1_750_000_000_000
	}
	cb := timacrypto.GroupMessageCanonicalBytes(timacrypto.GroupMessageMeta{
		GroupID:         groupID,
		SenderID:        sender.userID,
		SenderDevice:    sender.id,
		Kind:            m.Kind,
		CreatedAtUnixMs: m.CreatedAtUnixMs,
		ThreadRoot:      uint64(m.ThreadRoot),
		ReplyTo:         uint64(m.ReplyTo),
		GKVersion:       uint32(m.GKVersion),
	}, m.Payload)
	sig := ed25519.Sign(sender.signKey, cb)
	if tamper {
		sig[0] ^= 1
	}
	b64 := base64.RawURLEncoding
	body := map[string]any{
		"client_msg_id":      m.ClientMsgID,
		"kind":               m.Kind,
		"gk_version":         m.GKVersion,
		"payload":            b64.EncodeToString(m.Payload),
		"thread_root":        m.ThreadRoot,
		"reply_to":           m.ReplyTo,
		"created_at_unix_ms": m.CreatedAtUnixMs,
		"signature":          b64.EncodeToString(sig),
	}
	var resp map[string]json.RawMessage
	code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/messages", sender.token, body, &resp)
	return code, resp
}

// verifyAndOpen — сторона получателя: проверка подписи по канону и расшифровка GK.
func verifyAndOpen(t *testing.T, groupID string, item map[string]json.RawMessage, signingPub ed25519.PublicKey, gk [32]byte) []byte {
	t.Helper()
	var m struct {
		MessageID       int64  `json:"message_id"`
		SenderID        string `json:"sender_id"`
		SenderDevice    string `json:"sender_device"`
		Kind            uint32 `json:"kind"`
		GKVersion       int32  `json:"gk_version"`
		Payload         string `json:"payload"`
		ThreadRoot      int64  `json:"thread_root"`
		ReplyTo         int64  `json:"reply_to"`
		CreatedAtUnixMs int64  `json:"created_at_unix_ms"`
		Signature       string `json:"signature"`
	}
	raw, _ := json.Marshal(item)
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	b64 := base64.RawURLEncoding
	payload, err1 := b64.DecodeString(m.Payload)
	sig, err2 := b64.DecodeString(m.Signature)
	if err1 != nil || err2 != nil {
		t.Fatal("битые base64url в сообщении группы")
	}
	cb := timacrypto.GroupMessageCanonicalBytes(timacrypto.GroupMessageMeta{
		GroupID:         groupID,
		SenderID:        m.SenderID,
		SenderDevice:    m.SenderDevice,
		Kind:            m.Kind,
		CreatedAtUnixMs: m.CreatedAtUnixMs,
		ThreadRoot:      uint64(m.ThreadRoot),
		ReplyTo:         uint64(m.ReplyTo),
		GKVersion:       uint32(m.GKVersion),
	}, payload)
	if !timacrypto.VerifyEnvelopeSignature(signingPub, cb, sig) {
		t.Fatal("подпись сообщения группы не сошлась на стороне получателя")
	}
	var nonce [24]byte
	copy(nonce[:], payload[:24])
	opened, ok := secretbox.Open(nil, payload[24:], &nonce, &gk)
	if !ok {
		t.Fatal("payload не открылся групповым ключом")
	}
	return opened
}

func fetchGroupMessages(t *testing.T, ts *httptest.Server, token, groupID, query string) []map[string]json.RawMessage {
	t.Helper()
	var resp struct {
		Messages []map[string]json.RawMessage `json:"messages"`
	}
	code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/messages"+query, token, nil, &resp)
	if code != http.StatusOK {
		t.Fatalf("GET messages: %d", code)
	}
	return resp.Messages
}

func TestGroupMessagesEndToEnd(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995550001")
	member := registerDevice(t, ts, "+79995550002")
	outsider := registerDevice(t, ts, "+79995550003")

	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	gk1, code := doRotate(t, ts, owner.token, groupID, 1, "periodic", []*device{owner, member})
	if code != http.StatusCreated {
		t.Fatalf("ротация v1: %d", code)
	}

	// member шлёт зашифрованное GK сообщение
	plaintext := []byte("групповое сообщение, зашифрованное GK v1 🎯")
	msg := groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000001", Kind: 1, GKVersion: 1, Payload: sealGK(t, gk1, plaintext)}
	code, resp := sendGroupMessage(t, ts, member, groupID, msg, false)
	if code != http.StatusCreated {
		t.Fatalf("POST group message: %d", code)
	}
	var firstID int64
	_ = json.Unmarshal(resp["message_id"], &firstID)

	// Повтор того же client_msg_id → duplicate, тот же id
	code, resp = sendGroupMessage(t, ts, member, groupID, msg, false)
	var dup bool
	var dupID int64
	_ = json.Unmarshal(resp["duplicate"], &dup)
	_ = json.Unmarshal(resp["message_id"], &dupID)
	if code != http.StatusOK || !dup || dupID != firstID {
		t.Fatalf("дедуп: code=%d dup=%v id=%d (ожидался %d)", code, dup, dupID, firstID)
	}

	// Отказы: не-участник → 404; битая подпись → 403; чужая версия GK → 400; без GK → 400
	if code, _ := sendGroupMessage(t, ts, outsider, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000002", GKVersion: 1, Payload: sealGK(t, gk1, plaintext)}, false); code != http.StatusNotFound {
		t.Fatalf("сообщение не-участника: ожидался 404, получен %d", code)
	}
	if code, _ := sendGroupMessage(t, ts, member, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000003", GKVersion: 1, Payload: sealGK(t, gk1, plaintext)}, true); code != http.StatusForbidden {
		t.Fatalf("битая подпись: ожидался 403, получен %d", code)
	}
	if code, _ := sendGroupMessage(t, ts, member, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000004", GKVersion: 9, Payload: sealGK(t, gk1, plaintext)}, false); code != http.StatusBadRequest {
		t.Fatalf("неизвестный gk_version: ожидался 400, получен %d", code)
	}
	if code, _ := sendGroupMessage(t, ts, member, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000005", Payload: sealGK(t, gk1, plaintext)}, false); code != http.StatusBadRequest {
		t.Fatalf("private без gk_version: ожидался 400, получен %d", code)
	}

	// Ветка: ответ с thread_root на первое сообщение; битая ссылка → 400
	reply := groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000006", Kind: 1, GKVersion: 1,
		Payload: sealGK(t, gk1, []byte("ответ в ветке")), ThreadRoot: firstID, ReplyTo: firstID}
	if code, _ := sendGroupMessage(t, ts, owner, groupID, reply, false); code != http.StatusCreated {
		t.Fatalf("ответ в ветке: %d", code)
	}
	if code, _ := sendGroupMessage(t, ts, owner, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000007", GKVersion: 1,
			Payload: sealGK(t, gk1, plaintext), ThreadRoot: 999999}, false); code != http.StatusBadRequest {
		t.Fatalf("битый thread_root: ожидался 400, получен %d", code)
	}

	// История: owner читает, проверяет подпись member-а и расшифровывает GK
	msgs := fetchGroupMessages(t, ts, owner.token, groupID, "")
	if len(msgs) != 2 {
		t.Fatalf("в истории %d сообщений, ожидалось 2", len(msgs))
	}
	got := verifyAndOpen(t, groupID, msgs[1], member.signKey.Public().(ed25519.PublicKey), gk1)
	if !bytes.Equal(got, plaintext) {
		t.Fatalf("plaintext не совпал: %q", got)
	}
	// Фильтр ветки
	thread := fetchGroupMessages(t, ts, owner.token, groupID, fmt.Sprintf("?thread=%d", firstID))
	if len(thread) != 1 {
		t.Fatalf("в ветке %d сообщений, ожидалось 1", len(thread))
	}
	// Не-участнику история недоступна
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/messages", outsider.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("история не-участнику: ожидался 404, получен %d", code)
	}

	// Slow mode: 60 сек, member (уже писал) → 429; owner освобождён → 201
	if code := authedJSON(t, ts, "PATCH", "/api/v1/groups/"+groupID, owner.token,
		map[string]any{"slow_mode_sec": 60}, nil); code != http.StatusOK {
		t.Fatalf("PATCH slow_mode: %d", code)
	}
	code, _ = sendGroupMessage(t, ts, member, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000008", GKVersion: 1, Payload: sealGK(t, gk1, plaintext)}, false)
	if code != http.StatusTooManyRequests {
		t.Fatalf("slow mode: ожидался 429, получен %d", code)
	}
	if code, _ := sendGroupMessage(t, ts, owner, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000009", GKVersion: 1, Payload: sealGK(t, gk1, plaintext)}, false); code != http.StatusCreated {
		t.Fatalf("slow mode не должен действовать на owner: %d", code)
	}

	// Бан: заблокированный участник не пишет даже вне slow mode
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members/"+member.userID+"/ban",
		owner.token, map[string]any{"seconds": 3600}, nil); code != http.StatusNoContent {
		t.Fatalf("бан: %d", code)
	}
	code, _ = sendGroupMessage(t, ts, member, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-00000000000a", GKVersion: 1, Payload: sealGK(t, gk1, plaintext)}, false)
	if code != http.StatusForbidden {
		t.Fatalf("сообщение под баном: ожидался 403, получен %d", code)
	}
}

func TestWSDeliversGroupMessage(t *testing.T) {
	ts, _ := setupWithEvents(t)
	owner := registerDevice(t, ts, "+79995550011")
	member := registerDevice(t, ts, "+79995550012")

	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	gk1, code := doRotate(t, ts, owner.token, groupID, 1, "periodic", []*device{owner, member})
	if code != http.StatusCreated {
		t.Fatalf("ротация v1: %d", code)
	}

	conn := dialWS(t, ts, member.token)

	plaintext := []byte("live-сообщение группы по WS")
	if code, _ := sendGroupMessage(t, ts, owner, groupID,
		groupMsg{ClientMsgID: "cccccccc-0000-0000-0000-000000000011", Kind: 1, GKVersion: 1,
			Payload: sealGK(t, gk1, plaintext)}, false); code != http.StatusCreated {
		t.Fatalf("POST group message: %d", code)
	}

	frame := readEvent(t, conn, "message.group")
	got := verifyAndOpen(t, groupID, frame, owner.signKey.Public().(ed25519.PublicKey), gk1)
	if !bytes.Equal(got, plaintext) {
		t.Fatalf("plaintext из WS не совпал: %q", got)
	}
}
