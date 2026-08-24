package api

// Интеграционный тест API групповых ключей: ротация с реальной криптографией
// (конвейер GroupKeyManager: эфемерная пара → Box(GK) на устройство), догон
// пропущенных версий, исключение участника, монотонность версий.

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"

	"tima/server/internal/escrow"
	"tima/server/internal/store"
)

// doRotate повторяет клиентский GroupKeyManager.rotate на Go.
func doRotate(t *testing.T, ts *httptest.Server, token, groupID string, version int32, reason string, recipients []*device) ([32]byte, int) {
	t.Helper()
	var gk [32]byte
	if _, err := rand.Read(gk[:]); err != nil {
		t.Fatal(err)
	}
	ephPub, ephPriv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	b64 := base64.RawURLEncoding
	wrapped := make([]map[string]string, 0, len(recipients))
	for _, r := range recipients {
		var nonce [24]byte
		rand.Read(nonce[:])
		w := box.Seal(nonce[:], gk[:], &nonce, &r.encPub, ephPriv)
		wrapped = append(wrapped, map[string]string{"recipient": r.id, "wrapped": b64.EncodeToString(w)})
	}
	body := map[string]any{
		"gk_version":           version,
		"reason":               reason,
		"sender_ephemeral_pub": b64.EncodeToString(ephPub[:]),
		"escrow": map[string]any{
			"mlkem_ct":            b64.EncodeToString(bytes.Repeat([]byte{0xEC}, 1088)),
			"wrapped_message_key": b64.EncodeToString(bytes.Repeat([]byte{0xED}, 72)),
			"escrow_key_version":  1,
		},
		"wrapped_keys": wrapped,
	}
	code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/keys", token, body, nil)
	return gk, code
}

type deviceGroupKey struct {
	version int32
	ephPub  [32]byte
	wrapped []byte
}

func fetchGroupKeys(t *testing.T, ts *httptest.Server, token, groupID string, since int) []deviceGroupKey {
	t.Helper()
	var resp struct {
		Keys []struct {
			GKVersion          int32  `json:"gk_version"`
			SenderEphemeralPub string `json:"sender_ephemeral_pub"`
			Wrapped            string `json:"wrapped"`
		} `json:"keys"`
	}
	code := authedJSON(t, ts, "GET",
		"/api/v1/groups/"+groupID+"/keys?since_version="+strconv.Itoa(since), token, nil, &resp)
	if code != http.StatusOK {
		t.Fatalf("GET group keys: %d", code)
	}
	b64 := base64.RawURLEncoding
	out := make([]deviceGroupKey, 0, len(resp.Keys))
	for _, k := range resp.Keys {
		var dk deviceGroupKey
		dk.version = k.GKVersion
		eph, err1 := b64.DecodeString(k.SenderEphemeralPub)
		wr, err2 := b64.DecodeString(k.Wrapped)
		if err1 != nil || err2 != nil || len(eph) != 32 {
			t.Fatal("битые base64url в ответе")
		}
		copy(dk.ephPub[:], eph)
		dk.wrapped = wr
		out = append(out, dk)
	}
	return out
}

func unwrapGK(t *testing.T, k deviceGroupKey, devicePriv *[32]byte) [32]byte {
	t.Helper()
	var nonce [24]byte
	copy(nonce[:], k.wrapped[:24])
	raw, ok := box.Open(nil, k.wrapped[24:], &nonce, &k.ephPub, devicePriv)
	if !ok {
		t.Fatal("wrapped_GK не развернулся")
	}
	var gk [32]byte
	copy(gk[:], raw)
	return gk
}

func TestGroupKeysRotationAndCatchUp(t *testing.T) {
	ts, _ := setup(t)
	admin := registerDevice(t, ts, "+79992220001")
	member := registerDevice(t, ts, "+79992220002")
	outcast := registerDevice(t, ts, "+79992220003")

	groupID := createGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")
	addMemberAPI(t, ts, admin.token, groupID, outcast.userID, "member")

	// Права участника проверяются отдельно (TestGroupKeyRotationRightsAndReasons):
	// с ADR-0017 ротировать может любой действующий участник, и здесь такая проверка
	// заняла бы версию, ломая счёт версий этого сценария.
	outsider := registerDevice(t, ts, "+79992220009")
	// Не-участник получает 404, а не 403: приватная группа для постороннего неотличима
	// от несуществующей — так отвечают getGroup и postGroupMessage, и ротация была
	// единственным местом, выбивавшимся из этого соглашения.
	if _, code := doRotate(t, ts, outsider.token, groupID, 1, "periodic", []*device{admin}); code != http.StatusNotFound {
		t.Fatalf("ротация не-участником: ожидался 404, получен %d", code)
	}
	// Обёртка на устройство не-участника → 400
	if _, code := doRotate(t, ts, admin.token, groupID, 1, "periodic", []*device{admin, outsider}); code != http.StatusBadRequest {
		t.Fatalf("обёртка чужому устройству: ожидался 400, получен %d", code)
	}

	// v1: все трое
	gk1, code := doRotate(t, ts, admin.token, groupID, 1, "periodic", []*device{admin, member, outcast})
	if code != http.StatusCreated {
		t.Fatalf("ротация v1: %d", code)
	}
	// Исключение outcast из группы, затем v2 без него
	if code := authedJSON(t, ts, "DELETE",
		"/api/v1/groups/"+groupID+"/members/"+outcast.userID, admin.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("исключение outcast: %d", code)
	}
	gk2, code := doRotate(t, ts, admin.token, groupID, 2, "member_leave", []*device{admin, member})
	if code != http.StatusCreated {
		t.Fatalf("ротация v2: %d", code)
	}

	// Монотонность: повтор v2 и скачок v4 → 409
	if _, code := doRotate(t, ts, admin.token, groupID, 2, "periodic", []*device{admin}); code != http.StatusConflict {
		t.Fatalf("повтор v2: ожидался 409, получен %d", code)
	}
	if _, code := doRotate(t, ts, admin.token, groupID, 4, "periodic", []*device{admin}); code != http.StatusConflict {
		t.Fatalf("скачок v4: ожидался 409, получен %d", code)
	}

	// member догоняет с нуля: обе версии, GK разворачиваются и совпадают
	keys := fetchGroupKeys(t, ts, member.token, groupID, 0)
	if len(keys) != 2 {
		t.Fatalf("member: ожидалось 2 версии, получено %d", len(keys))
	}
	for i, want := range [][32]byte{gk1, gk2} {
		got := unwrapGK(t, keys[i], &member.encPriv)
		if !bytes.Equal(got[:], want[:]) {
			t.Fatalf("GK v%d не совпал после разворачивания", i+1)
		}
	}

	// Сообщение группы, зашифрованное v2, читается member-ом
	payload := []byte("групповое сообщение после ротации")
	var nonce [24]byte
	rand.Read(nonce[:])
	sealed := secretbox.Seal(nonce[:], payload, &nonce, &gk2)
	gkAtMember := unwrapGK(t, keys[1], &member.encPriv)
	var pn [24]byte
	copy(pn[:], sealed[:24])
	opened, ok := secretbox.Open(nil, sealed[24:], &pn, &gkAtMember)
	if !ok || !bytes.Equal(opened, payload) {
		t.Fatal("сообщение группы не расшифровалось GK, полученным через API")
	}

	// outcast: видит только v1 (v2 ему не выдавалась) — post-compromise security;
	// доступ к старым версиям после исключения сохраняется (окно апелляции)
	outKeys := fetchGroupKeys(t, ts, outcast.token, groupID, 0)
	if len(outKeys) != 1 || outKeys[0].version != 1 {
		t.Fatalf("outcast: ожидалась только v1, получено %d версий", len(outKeys))
	}
	// Догон с since_version=1 → пусто
	if rest := fetchGroupKeys(t, ts, outcast.token, groupID, 1); len(rest) != 0 {
		t.Fatalf("outcast since=1: ожидалось 0, получено %d", len(rest))
	}
}

// TestGroupKeyRotationRightsAndReasons — права и проверка причины (ADR-0017 §5, §7).
//
// Ротировать может любой действующий участник: эпохальный триггер привязан к
// календарю, и право, привязанное к присутствию админа, сделало бы гарантию
// зависимой от того, в сети ли конкретный человек.
func TestGroupKeyRotationRightsAndReasons(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79993330001")
	member := registerDevice(t, ts, "+79993330002")
	banned := registerDevice(t, ts, "+79993330003")

	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	addMemberAPI(t, ts, owner.token, groupID, banned.userID, "member")

	все := []*device{owner, member, banned}

	// Участник ротирует — это и есть изменение ADR-0017 §5.
	if _, code := doRotate(t, ts, member.token, groupID, 1, "member_join", все); code != http.StatusCreated {
		t.Fatalf("ротация участником: ожидался 201, получен %d", code)
	}

	// Заблокированный не ротирует: бан отнимает право писать, а ротация — запись,
	// которую получат все устройства группы.
	if code := jsonAuth(t, ts, "POST", "/api/v1/groups/"+groupID+"/members/"+banned.userID+"/ban",
		owner.token, map[string]any{"seconds": 3600}, nil); code != http.StatusOK && code != http.StatusNoContent {
		t.Fatalf("бан участника: %d", code)
	}
	if _, code := doRotate(t, ts, banned.token, groupID, 2, "member_join", все); code != http.StatusForbidden {
		t.Fatalf("ротация заблокированным: ожидался 403, получен %d", code)
	}

	// Неизвестная причина отвергается: причина — не пояснение, по ней сервер решает,
	// действует ли порог и нужна ли ротация вообще.
	if _, code := doRotate(t, ts, member.token, groupID, 2, "потому что", все); code != http.StatusBadRequest {
		t.Fatalf("неизвестная причина: ожидался 400, получен %d", code)
	}

	// Причина проверяется по состоянию группы: «много сообщений» в группе, где после
	// прошлой ротации не было ни одного, — заведомая неправда, и это отвергается раньше
	// порога. Иначе злоупотребление стоило бы всего лишь ожидания.
	if _, code := doRotate(t, ts, member.token, groupID, 2, "periodic", все); code != http.StatusConflict {
		t.Fatalf("periodic без сообщений: ожидался 409 rotation_not_needed, получен %d", code)
	}

	// А вот при живой переписке причина подтверждается — и тогда работает порог: вторая
	// ротация подряд отвергается, потому что право ротации есть у каждого участника, и
	// без порога группа из ста человек породила бы сто фан-аутов.
	if code, _ := sendGroupMessage(t, ts, member, groupID, groupMsg{
		ClientMsgID: "11111111-1111-1111-1111-111111111111",
		Kind:        1,
		GKVersion:   1,
		Payload:     bytes.Repeat([]byte{0x11}, 96),
	}, false); code != http.StatusCreated {
		t.Fatalf("отправка в группу для подтверждения причины: %d", code)
	}
	if _, code := doRotate(t, ts, member.token, groupID, 2, "periodic", все); code != http.StatusTooManyRequests {
		t.Fatalf("periodic при живой переписке: ожидался 429, получен %d", code)
	}

	// Срочная причина из-под порога выведена: задержка исключения означает, что
	// исключённый читает переписку ещё пятнадцать минут. Исключаем по-настоящему —
	// иначе причина не подтвердится, и проверка порога ничего не покажет.
	if code := jsonAuth(t, ts, "DELETE", "/api/v1/groups/"+groupID+"/members/"+banned.userID,
		owner.token, nil, nil); code != http.StatusOK && code != http.StatusNoContent {
		t.Fatalf("исключение участника: %d", code)
	}
	// Получатели — без исключённого: обёртка его устройству была бы выдачей доступа
	// тому, кого только что убрали, и сервер такую ротацию отвергает.
	оставшиеся := []*device{owner, member}
	if _, code := doRotate(t, ts, member.token, groupID, 2, "member_leave", оставшиеся); code != http.StatusCreated {
		t.Fatalf("member_leave под порогом: ожидался 201, получен %d", code)
	}

	// Эпоха: ротация не нужна, если ключ уже привязан к текущей. Для этого в кэше
	// ключей должна быть запись, на которую ссылается escrow_key_version ротации.
	if err := srv.Store.SaveEscrowKey(context.Background(), store.EscrowKey{
		ID: 1, Region: "ru", Epoch: escrow.EpochOf(time.Now()), ChatID: groupID,
		PublicKey: bytes.Repeat([]byte{0xAB}, 1184), Signature: bytes.Repeat([]byte{0xCD}, 64),
		ValidFrom: time.Now().Add(-time.Hour), ValidTo: time.Now().Add(time.Hour),
		DestroyAt: time.Now().Add(240 * time.Hour),
	}); err != nil {
		t.Fatalf("кэш ключа эпохи: %v", err)
	}
	// Причина должна быть правдой и здесь: зовём нового участника, и только тогда
	// member_join подтверждается состоянием группы.
	новичок := registerDevice(t, ts, "+79993330004")
	addMemberAPI(t, ts, owner.token, groupID, новичок.userID, "member")
	сНовичком := []*device{owner, member, новичок}
	if _, code := doRotate(t, ts, member.token, groupID, 3, "member_join", сНовичком); code != http.StatusCreated {
		t.Fatalf("ротация с известной эпохой: %d", code)
	}
	if _, code := doRotate(t, ts, member.token, groupID, 4, "epoch", сНовичком); code != http.StatusConflict {
		t.Fatalf("epoch при совпадающей эпохе: ожидался 409 rotation_not_needed, получен %d", code)
	}
}
