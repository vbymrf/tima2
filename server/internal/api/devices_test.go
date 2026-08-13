package api

// Список устройств и отзыв (key-lifecycle.md §5).

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

type deviceItem struct {
	DeviceID  string `json:"device_id"`
	Name      string `json:"name"`
	CreatedAt string `json:"created_at"`
	Current   bool   `json:"current"`
}

func myDevices(t *testing.T, ts *httptest.Server, bearer string) []deviceItem {
	t.Helper()
	var resp struct {
		Devices []deviceItem `json:"devices"`
	}
	if code := jsonAuth(t, ts, "GET", "/api/v1/devices", bearer, nil, &resp); code != 200 {
		t.Fatalf("GET /devices: %d", code)
	}
	return resp.Devices
}

// Устройство, подключённое по QR, приходит в список со своим именем и помечено
// не текущим — а телефон, с которого смотрим, текущим.
func TestListDevicesAfterLink(t *testing.T) {
	ts, _ := setup(t)
	phone := registerDevice(t, ts, "+79990080001")

	before := myDevices(t, ts, phone.token)
	if len(before) != 1 || !before[0].Current {
		t.Fatalf("до привязки ожидали одно текущее устройство, получили %+v", before)
	}

	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")
	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != 200 {
		t.Fatalf("link/confirm: %d", code)
	}

	after := myDevices(t, ts, phone.token)
	if len(after) != 2 {
		t.Fatalf("после привязки ожидали два устройства, получили %d", len(after))
	}
	var linked *deviceItem
	for i := range after {
		if !after[i].Current {
			linked = &after[i]
		}
	}
	if linked == nil {
		t.Fatal("подключённое устройство не найдено (все помечены текущими)")
	}
	if linked.Name != "Ноутбук" {
		t.Fatalf("имя устройства = %q, ожидали «Ноутбук»", linked.Name)
	}
}

// Отзыв: устройство исчезает из списка и его токен перестаёт работать.
func TestRevokeDevice(t *testing.T) {
	ts, _ := setup(t)
	phone := registerDevice(t, ts, "+79990080002")

	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")
	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != 200 {
		t.Fatalf("link/confirm: %d", code)
	}
	var claim struct {
		DeviceID    string `json:"device_id"`
		AccessToken string `json:"access_token"`
	}
	if code := postJSON(t, ts, "/api/v1/link/claim", map[string]string{
		"session_id": start.SessionID, "claim_token": start.ClaimToken,
	}, &claim); code != 200 {
		t.Fatalf("link/claim: %d", code)
	}

	if code := jsonAuth(t, ts, "DELETE", "/api/v1/devices/"+claim.DeviceID, phone.token, nil, nil); code != 200 {
		t.Fatalf("DELETE /devices: %d", code)
	}
	after := myDevices(t, ts, phone.token)
	if len(after) != 1 {
		t.Fatalf("после отзыва ожидали одно устройство, получили %d", len(after))
	}
	// Отозванное устройство больше не участник чата и не помощник — проверяем на
	// ручке, которая обращается к devices: свои ключи оно получить не должно.
	if code := jsonAuth(t, ts, "GET", "/api/v1/devices", claim.AccessToken, nil, nil); code == 200 {
		t.Fatal("отозванное устройство продолжает работать по своему токену")
	}
}

// Последнее устройство отозвать нельзя — иначе аккаунт остался бы без входа.
func TestRevokeLastDeviceRejected(t *testing.T) {
	ts, _ := setup(t)
	phone := registerDevice(t, ts, "+79990080003")

	var raw json.RawMessage
	code := jsonAuth(t, ts, "DELETE", "/api/v1/devices/"+phone.id, phone.token, nil, &raw)
	var errBody struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(raw, &errBody)
	if code != http.StatusConflict || errBody.Code != "last_device" {
		t.Fatalf("отзыв последнего устройства: код %d/%q, ожидали 409/last_device", code, errBody.Code)
	}
}

// Чужое устройство отозвать нельзя даже зная его device_id.
func TestRevokeForeignDeviceRejected(t *testing.T) {
	ts, _ := setup(t)
	a := registerDevice(t, ts, "+79990080004")
	b := registerDevice(t, ts, "+79990080005")

	// У b появляется второе устройство, чтобы отказ был именно про чужое, а не
	// про «последнее» (иначе проверка на last_device сработала бы раньше).
	start, encPub, signPub := startLink(t, ts, "Ноутбук b")
	secret := qrParam(t, start.QRPayload, "secret")
	if code := confirmLink(t, ts, b.token, start.SessionID, secret, encPub, signPub, b.signKey); code != 200 {
		t.Fatalf("link/confirm: %d", code)
	}

	if code := jsonAuth(t, ts, "DELETE", "/api/v1/devices/"+b.id, a.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("отзыв чужого устройства: %d, ожидали 404", code)
	}
	if len(myDevices(t, ts, b.token)) != 2 {
		t.Fatal("чужой отзыв всё-таки прошёл")
	}
}
