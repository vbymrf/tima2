package api

// Ручки управления своим аккаунтом и архивом чата (Р4).

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"tima/server/internal/store"
)

// doAuthed — запрос под device JWT. Тела у этих ручек нет: всё в пути и методе.
func doAuthed(t *testing.T, ts *httptest.Server, method, path string, body []byte, bearer string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(method, ts.URL+path, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+bearer)
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func decodeBody(t *testing.T, resp *http.Response, out any) {
	t.Helper()
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		t.Fatalf("разбор ответа: %v", err)
	}
}

func decodeAuthed(t *testing.T, ts *httptest.Server, path, bearer string, out any) {
	t.Helper()
	resp := doAuthed(t, ts, http.MethodGet, path, nil, bearer)
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("GET %s: %d", path, resp.StatusCode)
	}
	decodeBody(t, resp, out)
}

// Человек может убрать свой аккаунт сам, не дожидаясь воркера.
func TestDeleteOwnAccount(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	d := registerDevice(t, ts, "+79990080001")

	resp := doAuthed(t, ts, http.MethodDelete, "/api/v1/users/me", nil, d.token)
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("удаление аккаунта: %d", resp.StatusCode)
	}

	person, err := srv.Store.PersonOfUser(ctx, d.userID)
	if err != nil {
		t.Fatal(err)
	}
	// Данные ещё на месте (ADR-0015: пометка, потом стирание по сроку), но
	// аккаунт уже архивный — а значит номер он больше не держит, и тот же номер
	// достаётся новому владельцу отдельным аккаунтом.
	fresh := registerDevice(t, ts, "+79990080001")
	newPerson, err := srv.Store.PersonOfUser(ctx, fresh.userID)
	if err != nil {
		t.Fatal(err)
	}
	if newPerson == person {
		t.Fatal("после удаления номер остался за прежним аккаунтом")
	}
}

// Архив чата ЛИЧНЫЙ: уборка одного не убирает чат у другого.
func TestArchiveChatIsPersonalOverHTTP(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	a := registerDevice(t, ts, "+79990081001")
	b := registerDevice(t, ts, "+79990081002")
	env := sealEnvelope(t, a, []*device{a, b}, 920001, []byte("привет"))
	post(t, ts, env, a.token, "eeeeeeee-0000-0000-0000-000000000001").Body.Close()

	resp := doAuthed(t, ts, http.MethodPut, "/api/v1/chats/"+chatID+"/archive", nil, a.token)
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("убрать в архив: %d", resp.StatusCode)
	}

	mine, err := srv.Store.ArchivedChatsFor(ctx, a.userID)
	if err != nil {
		t.Fatal(err)
	}
	if len(mine) != 1 || mine[0] != chatID {
		t.Fatalf("у убравшего чат не в архиве: %v", mine)
	}
	his, err := srv.Store.ArchivedChatsFor(ctx, b.userID)
	if err != nil {
		t.Fatal(err)
	}
	if len(his) != 0 {
		t.Fatalf("чат оказался в архиве у ВТОРОГО участника, который его не убирал: %v", his)
	}

	// Вернули обратно — архив пуст.
	resp = doAuthed(t, ts, http.MethodDelete, "/api/v1/chats/"+chatID+"/archive", nil, a.token)
	resp.Body.Close()
	back, err := srv.Store.ArchivedChatsFor(ctx, a.userID)
	if err != nil {
		t.Fatal(err)
	}
	if len(back) != 0 {
		t.Fatalf("чат вернули из архива, а он там остался: %v", back)
	}
}

// Список архива отдаётся владельцу и только ему.
func TestListArchivedChats(t *testing.T) {
	ts, _ := setup(t)
	a := registerDevice(t, ts, "+79990082001")
	b := registerDevice(t, ts, "+79990082002")
	env := sealEnvelope(t, a, []*device{a, b}, 920002, []byte("привет"))
	post(t, ts, env, a.token, "eeeeeeee-0000-0000-0000-000000000002").Body.Close()
	doAuthed(t, ts, http.MethodPut, "/api/v1/chats/"+chatID+"/archive", nil, a.token).Body.Close()

	var mine struct {
		Chats []string `json:"chats"`
	}
	decodeAuthed(t, ts, "/api/v1/chats/archived", a.token, &mine)
	if len(mine.Chats) != 1 {
		t.Fatalf("свой архив: %v", mine.Chats)
	}
	var his struct {
		Chats []string `json:"chats"`
	}
	decodeAuthed(t, ts, "/api/v1/chats/archived", b.token, &his)
	if len(his.Chats) != 0 {
		t.Fatalf("чужой архив виден: %v", his.Chats)
	}
}

// Срок стирания берётся из настроек, а не из константы в коде.
func TestPurgeDaysComesFromPolicy(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	if err := srv.Store.SetRetentionDays(ctx, "account_purge_days", 77); err != nil {
		t.Fatal(err)
	}
	d := registerDevice(t, ts, "+79990083001")
	var got struct {
		PurgeAfterDays int `json:"purge_after_days"`
	}
	resp := doAuthed(t, ts, http.MethodDelete, "/api/v1/users/me", nil, d.token)
	defer resp.Body.Close()
	decodeBody(t, resp, &got)
	if got.PurgeAfterDays != 77 {
		t.Fatalf("срок стирания %d, а в настройках 77 — значит взят из кода", got.PurgeAfterDays)
	}
	_ = store.StateArchived
}
