package api

// Приём версии 1 отозван (поправка к ADR-0013, заказчик 2026-10-18): личный конверт без
// обязательства по ключу и зашифрованное сообщение группы без него получают отказ. Открытое
// сообщение группы — по-прежнему v1: ключа у него нет.

import (
	"crypto/ed25519"
	"encoding/base64"
	"fmt"
	"net/http"
	"testing"

	timacrypto "tima/server/internal/crypto"
)

func TestLegacyVersionRefused(t *testing.T) {
	ts, _ := setup(t)
	a := registerDevice(t, ts, "+79995570101")
	b := registerDevice(t, ts, "+79995570102")

	env := sealEnvelope(t, a, []*device{b}, 1, []byte("версия 1"))
	env.FormatVersion = 1
	env.KeyCommitment = nil
	m := env.GetMeta()
	cb := timacrypto.CanonicalBytesV2(1, timacrypto.EnvelopeMeta{
		MessageID: m.GetMessageId(), ChatID: m.GetChatId(), SenderID: m.GetSenderId(), SenderDevice: m.GetSenderDevice(),
		Kind: uint32(m.GetKind()), CreatedAtUnixMs: m.GetCreatedAtUnixMs(),
	}, env.GetEncryptedPayload(),
		append(append([]byte{}, env.GetEscrow().GetMlkemCt()...), env.GetEscrow().GetWrappedMessageKey()...),
		env.GetSenderEphemeralPub(), nil, nil)
	env.Signature = ed25519.Sign(a.signKey, cb)
	if code, _ := editReply(t, post(t, ts, env, a.token, "aaaaaaaa-0000-0000-0000-000000000701")); code != http.StatusBadRequest {
		t.Fatalf("личный конверт v1: ожидался 400, получен %d", code)
	}

	groupID := createGroupAPI(t, ts, a.token)
	gk, code := doRotate(t, ts, a.token, groupID, 1, "periodic", []*device{a})
	if code != http.StatusCreated {
		t.Fatalf("ротация: %d", code)
	}
	payload := sealGK(t, gk, []byte("без обязательства"))
	meta := timacrypto.GroupMessageMeta{GroupID: groupID, SenderID: a.userID, SenderDevice: a.id, Kind: 1,
		CreatedAtUnixMs: 1_750_000_000_000, GKVersion: 1}
	b64 := base64.RawURLEncoding
	var resp struct {
		Code string `json:"code"`
	}
	status := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/messages", a.token, map[string]any{
		"client_msg_id": fmt.Sprintf("aaaaaaaa-0000-0000-0000-%012d", 702), "kind": 1, "gk_version": 1,
		"payload": b64.EncodeToString(payload), "created_at_unix_ms": meta.CreatedAtUnixMs,
		"signature": b64.EncodeToString(ed25519.Sign(a.signKey, timacrypto.GroupMessageCanonicalBytes(meta, payload))),
	}, &resp)
	if status != http.StatusBadRequest || resp.Code != "no_commitment" {
		t.Fatalf("зашифрованное v1 в группе: ожидался 400 no_commitment, получен %d %s", status, resp.Code)
	}
}
