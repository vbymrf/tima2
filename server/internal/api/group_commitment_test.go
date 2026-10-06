package api

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"testing"

	timacrypto "tima/server/internal/crypto"
)

// Привязка ключа у сообщения группы (ADR-0013 для групп; решение заказчика 2026-10-06 «отменяем
// решение 5»): сообщение версии 2 несёт обязательство по ключу группы в подписи; сервер его
// сверяет с подписью, хранит и отдаёт; подменить его нельзя; у открытого сообщения его нет.
func TestGroupMessageKeyCommitment(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995550101")
	member := registerDevice(t, ts, "+79995550102")
	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	gk, code := doRotate(t, ts, owner.token, groupID, 1, "periodic", []*device{owner, member})
	if code != http.StatusCreated {
		t.Fatalf("ротация: %d", code)
	}
	payload := sealGK(t, gk, []byte("версия 2"))
	commitment := timacrypto.KeyCommitment(gk[:])
	b64 := base64.RawURLEncoding

	send := func(clientID string, level int, signed, sent []byte, gkVersion int32) int {
		meta := timacrypto.GroupMessageMeta{GroupID: groupID, SenderID: member.userID, SenderDevice: member.id,
			Kind: 1, CreatedAtUnixMs: 1_750_000_000_000, GKVersion: uint32(gkVersion)}
		sig := ed25519.Sign(member.signKey, timacrypto.GroupMessageCanonicalBytesV2(meta, payload, signed))
		body := map[string]any{
			"client_msg_id": clientID, "kind": 1, "gk_version": gkVersion, "level": level,
			"payload": b64.EncodeToString(payload), "created_at_unix_ms": meta.CreatedAtUnixMs,
			"signature": b64.EncodeToString(sig), "key_commitment": b64.EncodeToString(sent),
		}
		return authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/messages", member.token, body, nil)
	}

	if code := send("cccccccc-0000-0000-0000-00000000c001", -1, commitment, commitment, 1); code != http.StatusCreated {
		t.Fatalf("версия 2: ожидался 201, получен %d", code)
	}
	// Подпись над одним обязательством, в поле — другое: подмена.
	other := timacrypto.KeyCommitment(bytes.Repeat([]byte{7}, 32))
	if code := send("cccccccc-0000-0000-0000-00000000c002", -1, commitment, other, 1); code != http.StatusForbidden {
		t.Fatalf("подменённое обязательство: ожидался 403, получен %d", code)
	}
	// У открытого сообщения ключа нет — и обязательства тоже.
	if code := send("cccccccc-0000-0000-0000-00000000c003", 0, commitment, commitment, 0); code != http.StatusBadRequest {
		t.Fatalf("обязательство у открытого: ожидался 400, получен %d", code)
	}

	// Обязательство уходит получателю: он сверит его со своим ключом и проверит подпись v2.
	items := fetchGroupMessages(t, ts, owner.token, groupID, "")
	var got string
	for _, it := range items {
		if raw, ok := it["key_commitment"]; ok {
			_ = json.Unmarshal(raw, &got)
		}
	}
	if got != b64.EncodeToString(commitment) {
		t.Fatalf("в истории нет обязательства или оно не то: %q", got)
	}
}
