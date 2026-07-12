package api

// Интеграционный тест API групповых ключей: ротация с реальной криптографией
// (конвейер GroupKeyManager: эфемерная пара → Box(GK) на устройство), догон
// пропущенных версий, исключение участника, монотонность версий.

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
)

const groupID = "abababab-0000-0000-0000-0000000060c1"

// doRotate повторяет клиентский GroupKeyManager.rotate на Go.
func doRotate(t *testing.T, ts *httptest.Server, token string, version int32, reason string, recipients []*device) ([32]byte, int) {
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

func fetchGroupKeys(t *testing.T, ts *httptest.Server, token string, since int) []deviceGroupKey {
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

	// v1: все трое
	gk1, code := doRotate(t, ts, admin.token, 1, "periodic", []*device{admin, member, outcast})
	if code != http.StatusCreated {
		t.Fatalf("ротация v1: %d", code)
	}
	// v2: исключение outcast
	gk2, code := doRotate(t, ts, admin.token, 2, "member_leave", []*device{admin, member})
	if code != http.StatusCreated {
		t.Fatalf("ротация v2: %d", code)
	}

	// Монотонность: повтор v2 и скачок v4 → 409
	if _, code := doRotate(t, ts, admin.token, 2, "periodic", []*device{admin}); code != http.StatusConflict {
		t.Fatalf("повтор v2: ожидался 409, получен %d", code)
	}
	if _, code := doRotate(t, ts, admin.token, 4, "periodic", []*device{admin}); code != http.StatusConflict {
		t.Fatalf("скачок v4: ожидался 409, получен %d", code)
	}

	// member догоняет с нуля: обе версии, GK разворачиваются и совпадают
	keys := fetchGroupKeys(t, ts, member.token, 0)
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

	// outcast: видит только v1 (v2 ему не выдавалась) — post-compromise security
	outKeys := fetchGroupKeys(t, ts, outcast.token, 0)
	if len(outKeys) != 1 || outKeys[0].version != 1 {
		t.Fatalf("outcast: ожидалась только v1, получено %d версий", len(outKeys))
	}
	// Догон с since_version=1 → пусто
	if rest := fetchGroupKeys(t, ts, outcast.token, 1); len(rest) != 0 {
		t.Fatalf("outcast since=1: ожидалось 0, получено %d", len(rest))
	}
}
