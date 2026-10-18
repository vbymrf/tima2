package api

// Правка сообщения — версия 3 конверта (ADR-0045; doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §8): номер
// исходного под подписью, только автор, не больше трёх, до смены ключа; правка уходит с номером
// исходного и не считается новым.

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"testing"

	"google.golang.org/protobuf/proto"

	timacrypto "tima/server/internal/crypto"
	pb "tima/server/internal/proto"
	"tima/server/internal/store"
)

// asEdit превращает запечатанный конверт в правку editOf: версия 3, номер в meta, подпись заново.
// signedEditOf — какой номер подписать; отличный от editOf — подмена по пути.
func asEdit(t *testing.T, env *pb.Envelope, sender *device, editOf, signedEditOf uint64) *pb.Envelope {
	t.Helper()
	env.FormatVersion = timacrypto.FormatVersionEdit
	env.Meta.EditOf = editOf
	m := env.GetMeta()
	cb := timacrypto.CanonicalBytesV2(env.FormatVersion, timacrypto.EnvelopeMeta{
		MessageID: m.GetMessageId(), ChatID: m.GetChatId(), SenderID: m.GetSenderId(), SenderDevice: m.GetSenderDevice(),
		Kind: uint32(m.GetKind()), CreatedAtUnixMs: m.GetCreatedAtUnixMs(), ReplyTo: m.GetReplyTo(), EditOf: signedEditOf,
	}, env.GetEncryptedPayload(),
		append(append([]byte{}, env.GetEscrow().GetMlkemCt()...), env.GetEscrow().GetWrappedMessageKey()...),
		env.GetSenderEphemeralPub(), nil, env.GetKeyCommitment())
	env.Signature = ed25519.Sign(sender.signKey, cb)
	return env
}

func editReply(t *testing.T, resp *http.Response) (int, string) {
	t.Helper()
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var e struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(raw, &e)
	return resp.StatusCode, e.Code
}

func TestPersonalEdit(t *testing.T) {
	ts, _ := setup(t)
	a := registerDevice(t, ts, "+79995560101")
	b := registerDevice(t, ts, "+79995560102")
	db := testDB(t)
	n := 0
	cid := func() string { n++; return fmt.Sprintf("eeeeeeee-0000-0000-0000-%012d", n) }
	send := func(env *pb.Envelope, from *device) (int, string) {
		return editReply(t, post(t, ts, env, from.token, cid()))
	}

	if code, _ := send(sealEnvelope(t, a, []*device{b}, 1, []byte("исходное")), a); code != http.StatusCreated {
		t.Fatalf("исходное: %d", code)
	}

	// Правка автора — принята.
	if code, c := send(asEdit(t, sealEnvelope(t, a, []*device{b}, 2, []byte("правка 1")), a, 1, 1), a); code != http.StatusCreated {
		t.Fatalf("правка автора: %d %s", code, c)
	}

	// Номер исходного под подписью: подписан 1, отправлен 3 — подпись не сходится.
	if code, _ := send(sealEnvelope(t, a, []*device{b}, 3, []byte("третье")), a); code != http.StatusCreated {
		t.Fatalf("третье: %d", code)
	}
	if code, c := send(asEdit(t, sealEnvelope(t, a, []*device{b}, 4, []byte("подмена")), a, 3, 1), a); code != http.StatusForbidden || c != "bad_signature" {
		t.Fatalf("подменённый номер исходного: ожидался 403 bad_signature, получен %d %s", code, c)
	}

	// Версия 3 без номера и номер в версии 2 — отказ формата.
	noEditOf := asEdit(t, sealEnvelope(t, a, []*device{b}, 5, []byte("x")), a, 0, 0)
	if code, _ := send(noEditOf, a); code != http.StatusBadRequest {
		t.Fatalf("версия 3 без edit_of: ожидался 400, получен %d", code)
	}
	v2WithEditOf := sealEnvelope(t, a, []*device{b}, 6, []byte("x"))
	v2WithEditOf.Meta.EditOf = 1
	if code, _ := send(v2WithEditOf, a); code != http.StatusBadRequest {
		t.Fatalf("edit_of в версии 2: ожидался 400, получен %d", code)
	}

	// Чужое — только автор.
	if code, c := send(asEdit(t, sealEnvelope(t, b, []*device{a}, 7, []byte("чужая правка")), b, 1, 1), b); code != http.StatusForbidden || c != "edit_not_author" {
		t.Fatalf("правка чужого: ожидался 403 edit_not_author, получен %d %s", code, c)
	}
	// Нет исходного.
	if code, c := send(asEdit(t, sealEnvelope(t, a, []*device{b}, 8, []byte("в пустоту")), a, 999, 999), a); code != http.StatusConflict || c != "edit_missing" {
		t.Fatalf("правка несуществующего: ожидался 409 edit_missing, получен %d %s", code, c)
	}
	// Правку не правят.
	if code, c := send(asEdit(t, sealEnvelope(t, a, []*device{b}, 9, []byte("правка правки")), a, 2, 2), a); code != http.StatusConflict || c != "edit_of_edit" {
		t.Fatalf("правка правки: ожидался 409 edit_of_edit, получен %d %s", code, c)
	}

	// Не больше трёх: вторая и третья проходят, четвёртая — нет.
	for i, id := range []uint64{10, 11} {
		if code, c := send(asEdit(t, sealEnvelope(t, a, []*device{b}, id, []byte("ещё правка")), a, 1, 1), a); code != http.StatusCreated {
			t.Fatalf("правка %d: %d %s", i+2, code, c)
		}
	}
	if code, c := send(asEdit(t, sealEnvelope(t, a, []*device{b}, 12, []byte("четвёртая")), a, 1, 1), a); code != http.StatusConflict || c != "edit_limit" {
		t.Fatalf("четвёртая правка: ожидался 409 edit_limit, получен %d %s", code, c)
	}

	// До смены ключа: исходное прошлого месяца — эпоха сменилась.
	if _, err := db.Exec(context.Background(),
		`UPDATE personal_messages SET received_at = now() - interval '40 days' WHERE message_id = 3`); err != nil {
		t.Fatal(err)
	}
	if code, c := send(asEdit(t, sealEnvelope(t, a, []*device{b}, 13, []byte("поздно")), a, 3, 3), a); code != http.StatusConflict || c != "edit_closed" {
		t.Fatalf("правка после смены эпохи: ожидался 409 edit_closed, получен %d %s", code, c)
	}

	// Получатель видит номер исходного в конверте и проверяет подпись версии 3.
	var hist struct {
		Messages []struct {
			Envelope string `json:"envelope"`
		} `json:"messages"`
	}
	chat := chatIDFor(a, []*device{b})
	if code := authedJSON(t, ts, "GET", "/api/v1/chats/"+chat+"/messages", b.token, nil, &hist); code != http.StatusOK {
		t.Fatalf("история: %d", code)
	}
	edits := 0
	for _, it := range hist.Messages {
		raw, _ := base64.RawURLEncoding.DecodeString(it.Envelope)
		var env pb.Envelope
		if err := proto.Unmarshal(raw, &env); err != nil {
			t.Fatal(err)
		}
		if env.GetMeta().GetEditOf() == 0 {
			continue
		}
		edits++
		m := env.GetMeta()
		cb := timacrypto.CanonicalBytesV2(env.GetFormatVersion(), timacrypto.EnvelopeMeta{
			MessageID: m.GetMessageId(), ChatID: m.GetChatId(), SenderID: m.GetSenderId(), SenderDevice: m.GetSenderDevice(),
			Kind: uint32(m.GetKind()), CreatedAtUnixMs: m.GetCreatedAtUnixMs(), ReplyTo: m.GetReplyTo(), EditOf: m.GetEditOf(),
		}, env.GetEncryptedPayload(),
			append(append([]byte{}, env.GetEscrow().GetMlkemCt()...), env.GetEscrow().GetWrappedMessageKey()...),
			env.GetSenderEphemeralPub(), nil, env.GetKeyCommitment())
		if !timacrypto.VerifyEnvelopeSignature(a.signKey.Public().(ed25519.PublicKey), cb, env.GetSignature()) {
			t.Fatalf("подпись правки %d не сошлась у получателя", m.GetMessageId())
		}
	}
	if edits != 3 {
		t.Fatalf("в истории правок %d, ожидалось 3", edits)
	}
}

// Правка не новое: счёт для неоткрытого аккаунта её пропускает.
func TestNewsSkipsEdits(t *testing.T) {
	events := []store.DeviceEvent{
		{EventType: "message.new", Payload: []byte(`{"sender_id":"x"}`)},
		{EventType: "message.new", Payload: []byte(`{"sender_id":"x","edit_of":5}`)},
		{EventType: "message.group", Payload: []byte(`{"sender_id":"x","edit_of":7}`)},
	}
	if n := countNews(events, "me"); n != 1 {
		t.Fatalf("новых %d, ожидалось 1", n)
	}
}

func TestGroupEdit(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995560201")
	member := registerDevice(t, ts, "+79995560202")
	leaver := registerDevice(t, ts, "+79995560203")
	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	addMemberAPI(t, ts, owner.token, groupID, leaver.userID, "member")
	gk, code := doRotate(t, ts, owner.token, groupID, 1, "periodic", []*device{owner, member, leaver})
	if code != http.StatusCreated {
		t.Fatalf("ротация: %d", code)
	}
	b64 := base64.RawURLEncoding
	commitment := timacrypto.KeyCommitment(gk[:])
	n := 0
	send := func(from *device, gkVersion int32, key [32]byte, editOf, signedEditOf int64) (int, string, int64) {
		n++
		payload := sealGK(t, key, []byte("текст"))
		commit := timacrypto.KeyCommitment(key[:])
		meta := timacrypto.GroupMessageMeta{GroupID: groupID, SenderID: from.userID, SenderDevice: from.id,
			Kind: 1, CreatedAtUnixMs: 1_750_000_000_000, GKVersion: uint32(gkVersion)}
		cb := timacrypto.GroupMessageCanonicalBytesV2(meta, payload, commit)
		if editOf > 0 {
			cb = timacrypto.GroupMessageCanonicalBytesV3(meta, uint64(signedEditOf), payload, commit)
		}
		body := map[string]any{
			"client_msg_id": fmt.Sprintf("dddddddd-0000-0000-0000-%012d", n), "kind": 1, "gk_version": gkVersion,
			"payload": b64.EncodeToString(payload), "created_at_unix_ms": meta.CreatedAtUnixMs,
			"signature": b64.EncodeToString(ed25519.Sign(from.signKey, cb)), "key_commitment": b64.EncodeToString(commit),
		}
		if editOf > 0 {
			body["edit_of"] = editOf
		}
		var resp struct {
			MessageID int64  `json:"message_id"`
			Code      string `json:"code"`
		}
		status := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/messages", from.token, body, &resp)
		return status, resp.Code, resp.MessageID
	}

	status, _, original := send(member, 1, gk, 0, 0)
	if status != http.StatusCreated {
		t.Fatalf("исходное: %d", status)
	}
	status, c, edit := send(member, 1, gk, original, original)
	if status != http.StatusCreated {
		t.Fatalf("правка автора: %d %s", status, c)
	}
	if status, c, _ := send(member, 1, gk, original, edit); status != http.StatusForbidden || c != "bad_signature" {
		t.Fatalf("подменённый номер исходного: ожидался 403 bad_signature, получен %d %s", status, c)
	}
	if status, c, _ := send(owner, 1, gk, original, original); status != http.StatusForbidden || c != "edit_not_author" {
		t.Fatalf("правка чужого: ожидался 403 edit_not_author, получен %d %s", status, c)
	}

	// В истории правка несёт номер исходного; подпись версии 3 сходится у получателя.
	var got *map[string]json.RawMessage
	for _, it := range fetchGroupMessages(t, ts, owner.token, groupID, "") {
		if _, ok := it["edit_of"]; ok {
			it := it
			got = &it
		}
	}
	if got == nil {
		t.Fatal("правки нет в истории или у неё нет edit_of")
	}
	var m struct {
		EditOf    int64  `json:"edit_of"`
		Payload   string `json:"payload"`
		Signature string `json:"signature"`
		Created   int64  `json:"created_at_unix_ms"`
		Level     int16  `json:"level"`
	}
	raw, _ := json.Marshal(*got)
	_ = json.Unmarshal(raw, &m)
	payload, _ := b64.DecodeString(m.Payload)
	sig, _ := b64.DecodeString(m.Signature)
	cb := timacrypto.GroupMessageCanonicalBytesV3(timacrypto.GroupMessageMeta{GroupID: groupID, SenderID: member.userID,
		SenderDevice: member.id, Kind: 1, CreatedAtUnixMs: m.Created, GKVersion: 1}, uint64(m.EditOf), payload, commitment)
	if m.EditOf != original || !timacrypto.VerifyEnvelopeSignature(member.signKey.Public().(ed25519.PublicKey), cb, sig) {
		t.Fatalf("правка в истории: edit_of=%d, подпись версии 3 не сошлась", m.EditOf)
	}
	if m.Level != -1 {
		t.Fatalf("уровень правки %d, а у исходного -1", m.Level)
	}

	// Ключ сменился — исходное больше не правится. Смена по делу: участник ушёл.
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+groupID+"/members/"+leaver.userID, owner.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("исключение: %d", code)
	}
	gk2, code := doRotate(t, ts, owner.token, groupID, 2, "member_leave", []*device{owner, member})
	if code != http.StatusCreated {
		t.Fatalf("вторая ротация: %d", code)
	}
	if status, c, _ := send(member, 2, gk2, original, original); status != http.StatusConflict || c != "edit_closed" {
		t.Fatalf("правка после смены ключа: ожидался 409 edit_closed, получен %d %s", status, c)
	}
}
