package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

// Отчёты о проблеме — ПЛАН-ОТЛАДКИ.md, Б4.
//
// Главное здесь не «сохраняется ли», а два обещания: отчёт принимается без входа, и
// идентификаторы берутся из токена, а не из тела. Первое — потому что «не могу войти»
// самая частая жалоба; второе — потому что присланному идентификатору верить нельзя.

func отправитьОтчёт(t *testing.T, url, token, body string) (int, map[string]string) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, url+"/api/v1/problem-reports", bytes.NewBufferString(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	answer := map[string]string{}
	_ = json.NewDecoder(resp.Body).Decode(&answer)
	return resp.StatusCode, answer
}

// TestОтчётПринимаетсяБезВхода — самая частая жалоба это «не могу войти».
func TestОтчётПринимаетсяБезВхода(t *testing.T) {
	ts, _ := setup(t)

	status, answer := отправитьОтчёт(t, ts.URL, "", `{
        "kind":"messages","text":"не приходят сообщения","origin":"Телефон · Чаты",
        "platform":"android","model":"realme RMX3269","os":"Android 11",
        "build":"2.0.4-dev (4)","stream":"v2","nickname":"","log":"11:00 [сеть] GET /api/v1/chats → 200"
    }`)

	if status != http.StatusCreated {
		t.Fatalf("отчёт без входа обязан приниматься: %d", status)
	}
	if answer["number"] == "" {
		t.Fatal("номер обращения не вернулся — человеку нечего назвать в разговоре")
	}
	if len(answer["number"]) > 8 {
		t.Fatalf("номер обязан быть коротким, его диктуют вслух: %q", answer["number"])
	}
}

// TestОтчётБезОписанияНеПринимается — журнал без слов человека это загадка.
func TestОтчётБезОписанияНеПринимается(t *testing.T) {
	ts, _ := setup(t)

	status, answer := отправитьОтчёт(t, ts.URL, "", `{"kind":"other","text":"   ","log":"есть журнал"}`)

	if status != http.StatusBadRequest {
		t.Fatalf("ожидался отказ, получен %d", status)
	}
	if answer["code"] != "empty_report" {
		t.Fatalf("код отказа обязан объяснять причину: %q", answer["code"])
	}
}

// TestОтчётСТокеномСвязанСАккаунтом — идентификаторы берутся ИЗ ТОКЕНА, а не из тела.
func TestОтчётСТокеномСвязанСАккаунтом(t *testing.T) {
	ts, srv := setup(t)
	d := registerDevice(t, ts, "+70000000901")

	// В теле нарочно нет никаких идентификаторов — клиент их и не шлёт.
	status, _ := отправитьОтчёт(t, ts.URL, d.token, `{
        "kind":"looks","text":"экран поехал","platform":"android","model":"Xiaomi","os":"Android 16","log":"—"
    }`)
	if status != http.StatusCreated {
		t.Fatalf("отчёт с токеном: %d", status)
	}

	count, err := srv.Store.CountProblemReportsFrom(context.Background(), "", time.Now().Add(-time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	// Отчёт с токеном НЕ считается неопознанным: предел частоты его не касается, и
	// именно это отличает связанный отчёт от анонимного.
	if count != 0 {
		t.Fatalf("отчёт с токеном попал в неопознанные: %d", count)
	}
}

// TestДлинныйЖурналОбрезается — отчёт не место для загрузки файлов.
func TestДлинныйЖурналОбрезается(t *testing.T) {
	ts, _ := setup(t)

	huge := strings.Repeat("я", maxProblemLog+5000)
	body, err := json.Marshal(map[string]string{"kind": "other", "text": "много букв", "log": huge})
	if err != nil {
		t.Fatal(err)
	}
	status, answer := отправитьОтчёт(t, ts.URL, "", string(body))
	if status != http.StatusCreated {
		t.Fatalf("ожидалось принятие с обрезкой, получен %d", status)
	}

	if answer["number"] == "" {
		t.Fatal("номер не вернулся")
	}
}
