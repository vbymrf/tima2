package api

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"testing"

	pb "tima/server/internal/proto"

	"google.golang.org/protobuf/proto"
)

// TestKeyCopyMatrixModel — копия ключей по модели Matrix (§3а, М1–М3, М7).
//
// Открытый ключ копии публикуется только с подписью ключом личности и только растущей эпохой;
// обёртки копии принимаются только под действующую эпоху; страница копии отдаётся вместе с
// конвертом, где адресат — `key-copy`, и эфемералом рядом, — новое устройство открывает её
// тем же разбором, что живые сообщения.
func TestKeyCopyMatrixModel(t *testing.T) {
	ts, _ := setup(t)
	idPub, idPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	owner, code, _ := registerRaw(t, ts, "+79990062001", idPub, false)
	if code != 201 {
		t.Fatalf("владелец: %d", code)
	}
	peer := registerDevice(t, ts, "+79990062002")
	b64 := base64.RawURLEncoding

	copyPub := make([]byte, 32)
	_, _ = rand.Read(copyPub)
	publish := func(epoch int, signer ed25519.PrivateKey) int {
		return jsonAuth(t, ts, "PUT", "/api/v1/users/me/key-copy", owner.token, map[string]any{
			"epoch": epoch, "pub": b64.EncodeToString(copyPub),
			"sig": b64.EncodeToString(ed25519.Sign(signer, keyCopySigned(epoch, copyPub))),
		}, nil)
	}
	_, foreign, _ := ed25519.GenerateKey(rand.Reader)
	if code := publish(1, foreign); code != 403 {
		t.Fatalf("чужая подпись ключа копии: %d, ждали 403", code)
	}
	if code := publish(1, idPriv); code != 204 {
		t.Fatalf("публикация эпохи 1: %d", code)
	}
	if code := publish(1, idPriv); code != 204 {
		t.Fatalf("повтор той же эпохи тем же ключом — не ошибка: %d", code)
	}
	var got struct {
		Epoch int    `json:"epoch"`
		Pub   string `json:"pub"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/users/me/key-copy", &got); code != 200 || got.Epoch != 1 || got.Pub != b64.EncodeToString(copyPub) {
		t.Fatalf("ключ копии: %d %+v", code, got)
	}

	// Сообщение в личной переписке — копию его ключа кладёт владелец.
	chatID := personalChatID(owner.userID, peer.userID)
	env := sealEnvelope(t, owner, []*device{owner, peer}, 910001, []byte("в копию"))
	if resp := post(t, ts, env, owner.token, "dddddddd-0000-0000-0000-000000000001"); resp.StatusCode != 201 {
		defer resp.Body.Close()
		t.Fatalf("отправка: %d", resp.StatusCode)
	}
	eph := bytes.Repeat([]byte{7}, 32)
	wrapped := bytes.Repeat([]byte{9}, 72)
	backup := func(epoch int) int {
		return jsonAuth(t, ts, "POST", "/api/v1/chats/"+chatID+"/backup", owner.token, map[string]any{
			"epoch": epoch,
			"items": []map[string]any{{"message_id": 910001, "wrapped": b64.EncodeToString(append(append([]byte{}, eph...), wrapped...))}},
		}, nil)
	}
	if code := backup(2); code != 409 {
		t.Fatalf("обёртка под чужую эпоху: %d, ждали 409", code)
	}
	if code := backup(1); code != 201 {
		t.Fatalf("обёртка копии: %d", code)
	}
	var page struct {
		Items []struct {
			MessageID uint64 `json:"message_id"`
			Envelope  string `json:"envelope"`
			WrapEph   string `json:"wrap_ephemeral"`
		} `json:"items"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/chats/"+chatID+"/backup", &page); code != 200 || len(page.Items) != 1 {
		t.Fatalf("страница копии: %d, %d строк", code, len(page.Items))
	}
	raw, _ := b64.DecodeString(page.Items[0].Envelope)
	var restored pb.Envelope
	if err := proto.Unmarshal(raw, &restored); err != nil {
		t.Fatal(err)
	}
	if len(restored.GetWrappedKeys()) != 1 || restored.GetWrappedKeys()[0].GetRecipient() != keyCopyRecipient ||
		!bytes.Equal(restored.GetWrappedKeys()[0].GetWrapped(), wrapped) || page.Items[0].WrapEph != b64.EncodeToString(eph) {
		t.Fatal("в конверте копии обязана быть обёртка для key-copy, эфемерал — рядом")
	}
	if !bytes.Equal(restored.GetSignature(), env.GetSignature()) {
		t.Fatal("подпись исходного конверта обязана сохраниться — по ней новое устройство проверяет историю")
	}

	// Ключи групп в копии.
	group := createGroupWith(t, ts, owner, peer)
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/key-copy/groups", owner.token, map[string]any{
		"epoch": 1, "items": []map[string]any{{"group_id": group, "gk_version": 1, "wrapped": b64.EncodeToString(append(append([]byte{}, eph...), wrapped...))}},
	}, nil); code != 201 {
		t.Fatalf("ключ группы в копию: %d", code)
	}
	var groups struct {
		Items []struct {
			GroupID   string `json:"group_id"`
			GKVersion int32  `json:"gk_version"`
		} `json:"items"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/users/me/key-copy/groups", &groups); code != 200 || len(groups.Items) != 1 || groups.Items[0].GroupID != group {
		t.Fatalf("ключи групп в копии: %d %+v", code, groups.Items)
	}

	// Эпоха только растёт: вторая — можно, назад к первой — нет.
	if code := publish(2, idPriv); code != 204 {
		t.Fatalf("эпоха 2: %d", code)
	}
	if code := publish(1, idPriv); code != 409 {
		t.Fatalf("возврат к эпохе 1: %d, ждали 409", code)
	}
}
