package api

// Цепочка идентификаторов (миграция 0019, ПЛАН-РЕФАКТОРИНГА.md Р1б).
// Проверяем то, ради чего разделяли аккаунт и личность: номер и имя принадлежат
// аккаунту, писать можно только под текущей личностью, а прежние идентификаторы
// остаются жить вместе со своими сообщениями.

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"tima/server/internal/store"
)

// jsonAuth — запрос с телом и Bearer-токеном (в api_test.go такого помощника нет:
// там postJSON без авторизации).
func jsonAuth(t *testing.T, ts *httptest.Server, method, path, bearer string, body, out any) int {
	t.Helper()
	raw, _ := json.Marshal(body)
	req, _ := http.NewRequest(method, ts.URL+path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+bearer)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

// identitiesOf дёргает POST /users/identities и возвращает разобранный ответ.
func identitiesOf(t *testing.T, ts *httptest.Server, bearer string, ids []string) map[string]store.Identity {
	t.Helper()
	var resp struct {
		Identities map[string]store.Identity `json:"identities"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/identities", bearer,
		map[string]any{"ids": ids}, &resp); code != 200 {
		t.Fatalf("users/identities: %d", code)
	}
	return resp.Identities
}

func TestIdentityChain(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()

	alice := registerDevice(t, ts, "+79990010001")
	bob := registerDevice(t, ts, "+79990010002")

	// 1. Первая личность аккаунта — корень цепочки, связывать не с чем.
	ids := identitiesOf(t, ts, alice.token, []string{alice.userID, bob.userID})
	if got := ids[alice.userID].Link; got != store.LinkRoot {
		t.Fatalf("первая личность: link = %q, ожидали %q", got, store.LinkRoot)
	}
	if !ids[alice.userID].Current {
		t.Fatal("первая личность должна быть текущей")
	}
	if ids[alice.userID].PersonID == ids[bob.userID].PersonID {
		t.Fatal("разные номера дали один аккаунт")
	}

	// 2. Новая личность с доказательством: прежняя закрывается, аккаунт тот же.
	person, err := srv.Store.PersonOfUser(ctx, alice.userID)
	if err != nil {
		t.Fatal(err)
	}
	proof := []byte("подпись прежним ключом")
	next, err := srv.Store.StartNewIdentity(ctx, person, alice.userID, proof)
	if err != nil {
		t.Fatalf("StartNewIdentity: %v", err)
	}

	ids = identitiesOf(t, ts, alice.token, []string{alice.userID, next})
	if ids[next].PersonID != person {
		t.Fatal("новая личность оказалась в другом аккаунте")
	}
	if ids[next].Link != store.LinkProven {
		t.Fatalf("связка с подписью: link = %q, ожидали %q", ids[next].Link, store.LinkProven)
	}
	if !ids[next].Current {
		t.Fatal("новая личность должна стать текущей")
	}
	if ids[alice.userID].Current {
		t.Fatal("прежняя личность обязана перестать быть текущей")
	}
	// Прежний идентификатор никуда не делся: под ним лежат его сообщения.
	if ids[alice.userID].PersonID != person {
		t.Fatal("прежняя личность отвалилась от аккаунта")
	}

	// 3. Вход по тому же номеру ведёт на ТЕКУЩУЮ личность, а не на прежнюю.
	head, err := srv.Store.FindUserByPhone(ctx, "+79990010001")
	if err != nil {
		t.Fatal(err)
	}
	if head != next {
		t.Fatalf("вход по номеру вернул %s, ожидали текущую личность %s", head, next)
	}

	// 4. Административная связка (без подписи) отличима — на ней клиент показывает
	//    предупреждение о смене личности.
	third, err := srv.Store.StartNewIdentity(ctx, person, next, nil)
	if err != nil {
		t.Fatal(err)
	}
	ids = identitiesOf(t, ts, alice.token, []string{third})
	if ids[third].Link != store.LinkAdministrative {
		t.Fatalf("связка без подписи: link = %q, ожидали %q", ids[third].Link, store.LinkAdministrative)
	}
}

// Имя и номер принадлежат аккаунту, а не отдельной личности: сменил идентификатор —
// профиль остался тем же.
func TestProfileBelongsToAccount(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()

	d := registerDevice(t, ts, "+79990020001")
	if code := jsonAuth(t, ts, "PATCH", "/api/v1/users/me/name", d.token,
		map[string]string{"display_name": "Евгений"}, nil); code != 200 {
		t.Fatalf("установка имени: %d", code)
	}

	person, err := srv.Store.PersonOfUser(ctx, d.userID)
	if err != nil {
		t.Fatal(err)
	}
	next, err := srv.Store.StartNewIdentity(ctx, person, d.userID, []byte("proof"))
	if err != nil {
		t.Fatal(err)
	}

	names, err := srv.Store.DisplayNames(ctx, []string{d.userID, next})
	if err != nil {
		t.Fatal(err)
	}
	if names[next] != "Евгений" {
		t.Fatalf("новая личность видит имя %q, ожидали «Евгений» — имя принадлежит аккаунту", names[next])
	}
	if names[d.userID] != "Евгений" {
		t.Fatalf("прежняя личность потеряла имя аккаунта: %q", names[d.userID])
	}

	// Аккаунт по-прежнему находится по номеру, и это текущая личность.
	if _, err := srv.Store.FindUserByPhone(ctx, "+79990020001"); err != nil {
		t.Fatalf("аккаунт перестал находиться по номеру: %v", err)
	}
}

// Повторный вход по тому же номеру не плодит аккаунты.
func TestSamePhoneKeepsOneAccount(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()

	first := registerDevice(t, ts, "+79990030001")
	second := registerDevice(t, ts, "+79990030001") // второе устройство того же человека

	if first.userID != second.userID {
		t.Fatal("повторный вход по тому же номеру дал другую личность")
	}
	p1, err := srv.Store.PersonOfUser(ctx, first.userID)
	if err != nil {
		t.Fatal(err)
	}
	p2, err := srv.Store.PersonOfUser(ctx, second.userID)
	if err != nil {
		t.Fatal(err)
	}
	if p1 != p2 {
		t.Fatal("один номер породил два аккаунта")
	}
	if first.id == second.id {
		t.Fatal("второе устройство должно получить свой device_id")
	}
}
