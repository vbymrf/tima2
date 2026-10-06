package api

import (
	"context"
	"net/http"
	"testing"
)

// Требование аттестации у телефона (ПЛАН-(ЗБ)-ЗАЩИТЫ-ОТ-БОТОВ ЗБ1): под требованием устройству
// отказано во всём, кроме вызова и самой аттестации; свежая годная аттестация снимает
// требование, новое требование снова ставит устройство под отказ. Соседей не задевает.
func TestAttestationDemandBlocksUntilAttested(t *testing.T) {
	ts, srv := setup(t)
	srv.Attestation = "off" // ручка работает и при выключенном общем режиме
	acc := newTrustAccount()
	dev, _, _ := registerProof(t, ts, "+79990000211", acc.identityPub(), acc.withAsk)
	other := registerDevice(t, ts, "+79990000212")
	ctx := context.Background()

	if code := getAuthed(t, ts, dev.token, "/api/v1/devices", nil); code != http.StatusOK {
		t.Fatalf("до требования: ожидался 200, получен %d", code)
	}
	if err := srv.Store.DemandAttestation(ctx, dev.id, "проверка"); err != nil {
		t.Fatal(err)
	}

	// Отказ: код и заголовок, по которому клиент узнаёт требование.
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/devices", nil)
	req.Header.Set("Authorization", "Bearer "+dev.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden || resp.Header.Get(attestationHeader) != "required" {
		t.Fatalf("под требованием: ожидался 403 с заголовком, получен %d %q", resp.StatusCode, resp.Header.Get(attestationHeader))
	}
	var refusal struct {
		Code string `json:"code"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/messages", dev.token, map[string]any{}, &refusal); code != http.StatusForbidden || refusal.Code != attestationRequired {
		t.Fatalf("действие под требованием: ожидался 403 %s, получен %d %q", attestationRequired, code, refusal.Code)
	}
	// Соседа требование не касается.
	if code := getAuthed(t, ts, other.token, "/api/v1/devices", nil); code != http.StatusOK {
		t.Fatalf("сосед: ожидался 200, получен %d", code)
	}

	// Вызов и аттестация пропускаются; негодная аттестация требование не снимает, а при
	// выключенном общем режиме устройство под требованием всё равно проверяется (не 204).
	ch := challengeFor(t, ts, dev.token)
	body := map[string]any{"challenge_token": ch, "kind": "android-key", "chain": []string{"AAAA", "BBBB"}, "signature": "AAAA"}
	var res struct {
		State string `json:"state"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/devices/me/attestation", dev.token, body, &res); code != http.StatusOK || res.State != "failed" {
		t.Fatalf("аттестация под требованием: ожидался 200 failed, получен %d %+v", code, res)
	}
	if code := getAuthed(t, ts, dev.token, "/api/v1/devices", nil); code != http.StatusForbidden {
		t.Fatalf("после негодной аттестации: ожидался 403, получен %d", code)
	}

	// Годная аттестация (цепочку Android в тесте не собрать — итог пишется хранилищем).
	if err := srv.Store.SetDeviceAttestation(ctx, dev.userID, dev.id, "verified", "{}"); err != nil {
		t.Fatal(err)
	}
	if code := getAuthed(t, ts, dev.token, "/api/v1/devices", nil); code != http.StatusOK {
		t.Fatalf("после годной аттестации: ожидался 200, получен %d", code)
	}

	// Новое требование — прежняя аттестация его уже не снимает.
	if err := srv.Store.DemandAttestation(ctx, dev.id, "ещё раз"); err != nil {
		t.Fatal(err)
	}
	if code := getAuthed(t, ts, dev.token, "/api/v1/devices", nil); code != http.StatusForbidden {
		t.Fatalf("новое требование: ожидался 403, получен %d", code)
	}
}
