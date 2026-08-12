package api

// Ключи эпох от бэкенда к анклаву и обратно (Р2). Анклав поднимается в процессе:
// проверяем реальный путь «клиент → tima → анклав → реестр», а не заглушку.

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"tima/server/internal/escrow"
)

const testChatID = "bbbbbbbb-0000-0000-0000-0000000000e1"

// withEnclave поднимает stub-анклав со связкой ключей и подключает его к серверу.
// Возвращает и Enclave — тестам подписи (Р2) нужен его публичный ключ подписи.
func withEnclave(t *testing.T, srv *Server) (*escrow.Enclave, *escrow.Keyring) {
	t.Helper()
	dir := t.TempDir()
	enc, _, err := escrow.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	ring, err := escrow.OpenKeyring(dir, 180*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	enc.Register(mux)
	enc.RegisterKeyring(mux, ring)
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	srv.EscrowURL = ts.URL
	return enc, ring
}

type escrowKeyResp struct {
	Region  string `json:"region"`
	Current struct {
		ID        uint32    `json:"id"`
		Epoch     string    `json:"epoch"`
		PublicKey string    `json:"public_key"`
		Signature string    `json:"signature"`
		ValidFrom time.Time `json:"valid_from"`
		ValidTo   time.Time `json:"valid_to"`
		DestroyAt time.Time `json:"destroy_at"`
	} `json:"current"`
	Next *struct {
		ID    uint32 `json:"id"`
		Epoch string `json:"epoch"`
	} `json:"next"`
}

func getEscrowKey(t *testing.T, ts *httptest.Server, bearer, chatID string) (escrowKeyResp, int) {
	t.Helper()
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/escrow/key?chat_id="+chatID, nil)
	req.Header.Set("Authorization", "Bearer "+bearer)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out escrowKeyResp
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return out, resp.StatusCode
}

func TestEscrowKeyPerChatAndEpoch(t *testing.T) {
	ts, srv := setup(t)
	withEnclave(t, srv)
	d := registerDevice(t, ts, "+79990040001")

	got, code := getEscrowKey(t, ts, d.token, testChatID)
	if code != 200 {
		t.Fatalf("/escrow/key: %d", code)
	}
	if got.Current.ID == 0 || got.Current.PublicKey == "" {
		t.Fatalf("пустой ключ: %+v", got)
	}
	if got.Region != "ru" {
		t.Fatalf("регион %q, ожидали ru", got.Region)
	}
	if got.Current.Epoch != escrow.EpochOf(time.Now()) {
		t.Fatalf("эпоха %q, ожидали текущую %q", got.Current.Epoch, escrow.EpochOf(time.Now()))
	}
	pub, err := base64.RawURLEncoding.DecodeString(got.Current.PublicKey)
	if err != nil || len(pub) != escrow.PubKeySize {
		t.Fatalf("публичный ключ %d байт (%v), ожидали %d", len(pub), err, escrow.PubKeySize)
	}

	// Повторный запрос идёт из реестра и обязан дать тот же идентификатор: на него
	// уже могли сослаться отправленные блобы.
	again, _ := getEscrowKey(t, ts, d.token, testChatID)
	if again.Current.ID != got.Current.ID || again.Current.PublicKey != got.Current.PublicKey {
		t.Fatal("повторный запрос выдал другой ключ")
	}

	// Другой чат — другой ключ: в этом весь смысл дробления.
	other, _ := getEscrowKey(t, ts, d.token, "bbbbbbbb-0000-0000-0000-0000000000e2")
	if other.Current.ID == got.Current.ID || other.Current.PublicKey == got.Current.PublicKey {
		t.Fatal("разные чаты получили один ключ")
	}
}

// Реестр знает срок уничтожения: по нему этап Р4 решает, можно ли стирать содержимое.
func TestEscrowKeyRegistryKnowsDestroyAt(t *testing.T) {
	ts, srv := setup(t)
	withEnclave(t, srv)
	d := registerDevice(t, ts, "+79990040002")

	got, _ := getEscrowKey(t, ts, d.token, testChatID)

	k, err := srv.Store.FindEscrowKey(t.Context(), "ru", got.Current.Epoch, testChatID)
	if err != nil {
		t.Fatalf("ключ не попал в реестр: %v", err)
	}
	if k.ID != got.Current.ID {
		t.Fatal("в реестре другой идентификатор")
	}
	if !k.DestroyAt.After(k.ValidTo) {
		t.Fatal("срок уничтожения не позже конца эпохи — сообщения конца эпохи не доживут до срока")
	}
	// Свежий ключ в список «пора стирать» попадать не должен.
	expired, err := srv.Store.ExpiredEscrowKeys(t.Context(), time.Now(), 10)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range expired {
		if id == k.ID {
			t.Fatal("свежий ключ попал в просроченные")
		}
	}
	// А после его destroy_at — обязан.
	expired, err = srv.Store.ExpiredEscrowKeys(t.Context(), k.DestroyAt.Add(time.Hour), 10)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, id := range expired {
		if id == k.ID {
			found = true
		}
	}
	if !found {
		t.Fatal("просроченный ключ не найден — гейт стирания в Р4 его не увидит")
	}
}

func TestEscrowKeyRejectsBadChat(t *testing.T) {
	ts, srv := setup(t)
	withEnclave(t, srv)
	d := registerDevice(t, ts, "+79990040003")

	for _, bad := range []string{"", "not-a-uuid", "../etc/passwd"} {
		if _, code := getEscrowKey(t, ts, d.token, bad); code != http.StatusBadRequest {
			t.Fatalf("chat_id %q принят с кодом %d", bad, code)
		}
	}
}

// Подпись анклава (Р2) обязана проходить через tima неповреждённой — и на пути
// «анклав → tima → клиент» напрямую, и позже, когда tima отдаёт из своего кэша
// (escrow_keys), не спрашивая анклав заново.
func TestEscrowKeySignaturePassesThroughAndCaches(t *testing.T) {
	ts, srv := setup(t)
	enc, _ := withEnclave(t, srv)
	d := registerDevice(t, ts, "+79990040005")

	verify := func(t *testing.T, region string, cur struct {
		ID        uint32    `json:"id"`
		Epoch     string    `json:"epoch"`
		PublicKey string    `json:"public_key"`
		Signature string    `json:"signature"`
		ValidFrom time.Time `json:"valid_from"`
		ValidTo   time.Time `json:"valid_to"`
		DestroyAt time.Time `json:"destroy_at"`
	}) {
		t.Helper()
		sig, err := base64.RawURLEncoding.DecodeString(cur.Signature)
		if err != nil || len(sig) == 0 {
			t.Fatalf("signature: %v", err)
		}
		meta := escrow.KeyMeta{
			ID: cur.ID, Region: region, Epoch: cur.Epoch, ChatID: testChatID,
			PublicKey: cur.PublicKey, ValidFrom: cur.ValidFrom, ValidTo: cur.ValidTo, DestroyAt: cur.DestroyAt,
		}
		msg, err := escrow.KeyMetaSigningBytes(meta)
		if err != nil {
			t.Fatal(err)
		}
		if !ed25519.Verify(enc.SigningPublicKey(), msg, sig) {
			t.Fatal("подпись не проходит проверку зашитым публичным ключом анклава")
		}
	}

	// Первый запрос идёт к анклаву напрямую.
	first, code := getEscrowKey(t, ts, d.token, testChatID)
	if code != 200 {
		t.Fatalf("/escrow/key: %d", code)
	}
	verify(t, first.Region, first.Current)

	// Второй — из кэша tima (escrow_keys); подпись должна остаться той же.
	second, _ := getEscrowKey(t, ts, d.token, testChatID)
	if second.Current.Signature != first.Current.Signature {
		t.Fatal("подпись изменилась при отдаче из кэша")
	}
	verify(t, second.Region, second.Current)
}

// Без анклава эндпоинт честно отвечает 503, а не отдаёт что-нибудь.
func TestEscrowKeyWithoutEnclave(t *testing.T) {
	ts, srv := setup(t)
	srv.EscrowURL = ""
	d := registerDevice(t, ts, "+79990040004")
	if _, code := getEscrowKey(t, ts, d.token, testChatID); code != http.StatusServiceUnavailable {
		t.Fatalf("без анклава: %d, ожидали 503", code)
	}
}
