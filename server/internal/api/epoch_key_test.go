package api

import (
	"crypto/ed25519"
	"encoding/base64"
	"net/http"
	"testing"
	"time"

	timacrypto "tima/server/internal/crypto"
)

// Ключ шифрования устройства на эпоху (ПЛАН-(ПС) ПС3): устройство публикует ключ, подписанный
// своим ключом подписи; эпоха — текущая или следующая; в эпоху ключ один; отправитель видит
// последний ключ эпохи рядом с ключами устройства.
func TestDeviceEpochKey(t *testing.T) {
	ts, _ := setup(t)
	dev := registerDevice(t, ts, "+79990069101")
	peer := registerDevice(t, ts, "+79990069102")
	b64 := base64.RawURLEncoding
	epoch := time.Now().UTC().Format("2006-01")
	pub := make([]byte, 32)
	pub[0] = 1
	put := func(e string, key []byte, signer ed25519.PrivateKey) int {
		sig := ed25519.Sign(signer, timacrypto.DeviceEpochKeyBytes(dev.id, e, key))
		return authedJSON(t, ts, "PUT", "/api/v1/devices/me/epoch-key", dev.token,
			map[string]string{"epoch": e, "encryption_pub": b64.EncodeToString(key), "signature": b64.EncodeToString(sig)}, nil)
	}
	if code := put(epoch, pub, peer.signKey); code != http.StatusForbidden {
		t.Fatalf("чужая подпись: %d, ждали 403", code)
	}
	if code := put("2020-01", pub, dev.signKey); code != http.StatusBadRequest {
		t.Fatalf("давняя эпоха: %d, ждали 400", code)
	}
	if code := put(epoch, pub, dev.signKey); code != http.StatusNoContent {
		t.Fatalf("публикация: %d", code)
	}
	if code := put(epoch, pub, dev.signKey); code != http.StatusNoContent {
		t.Fatalf("повтор того же ключа: %d", code)
	}
	other := make([]byte, 32)
	other[0] = 2
	if code := put(epoch, other, dev.signKey); code != http.StatusConflict {
		t.Fatalf("второй ключ в ту же эпоху: %d, ждали 409", code)
	}

	var keys struct {
		Devices []struct {
			DeviceID string `json:"device_id"`
			EpochKey *struct {
				Epoch         string `json:"epoch"`
				EncryptionPub string `json:"encryption_pub"`
				Signature     string `json:"signature"`
			} `json:"epoch_key"`
		} `json:"devices"`
	}
	getAuthed(t, ts, peer.token, "/api/v1/keys/devices?user_id="+dev.userID, &keys)
	if len(keys.Devices) != 1 || keys.Devices[0].EpochKey == nil || keys.Devices[0].EpochKey.Epoch != epoch ||
		keys.Devices[0].EpochKey.EncryptionPub != b64.EncodeToString(pub) {
		t.Fatalf("ключ эпохи не отдан отправителю: %+v", keys.Devices)
	}
	sig, _ := b64.DecodeString(keys.Devices[0].EpochKey.Signature)
	if !ed25519.Verify(dev.signKey.Public().(ed25519.PublicKey), timacrypto.DeviceEpochKeyBytes(dev.id, epoch, pub), sig) {
		t.Fatal("подпись ключа эпохи не сходится с ключом подписи устройства")
	}
}
