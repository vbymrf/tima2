package api

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"testing"
)

// TestNewDeviceAnnouncedToOwnDevices — Р48: вход на новом устройстве той же личности даёт
// всем её устройствам событие `device.added` с адресом нового — человек видит его в подокне
// событий и, если это не он, отключает устройство.
func TestNewDeviceAnnouncedToOwnDevices(t *testing.T) {
	ts, _ := setupWithEvents(t)
	phone := "+79996660088"
	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	first, code, _ := registerRaw(t, ts, phone, pub, false)
	if code != 201 {
		t.Fatalf("первое устройство: %d", code)
	}
	conn := dialWS(t, ts, first.token)

	second, code, _ := registerRaw(t, ts, phone, pub, false)
	if code != 201 {
		t.Fatalf("второе устройство: %d", code)
	}
	if second.userID != first.userID {
		t.Fatalf("второе устройство попало в другую личность: %s ≠ %s", second.userID, first.userID)
	}

	frame := readByPoke(t, conn, "device.added")
	var got string
	_ = json.Unmarshal(frame["device_id"], &got)
	if got != second.id {
		t.Fatalf("в событии устройство %q, ждали новое %q", got, second.id)
	}
}
