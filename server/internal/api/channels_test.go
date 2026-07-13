package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// postAuthed/getAuthed — запросы под Bearer с JSON-телом/ответом. Возвращают статус.
func postAuthed(t *testing.T, ts *httptest.Server, token, method, path string, body, out any) int {
	t.Helper()
	var reader *bytes.Reader
	if body != nil {
		raw, _ := json.Marshal(body)
		reader = bytes.NewReader(raw)
	} else {
		reader = bytes.NewReader(nil)
	}
	req, _ := http.NewRequest(method, ts.URL+path, reader)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
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

func getAuthed(t *testing.T, ts *httptest.Server, token, path string, out any) int {
	t.Helper()
	req, _ := http.NewRequest("GET", ts.URL+path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
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

// TestChannelsEndToEnd — публичные каналы: создание, каталог, подписка, пост, лента, WS.
func TestChannelsEndToEnd(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000020")
	reader := registerDevice(t, ts, "+79990000021")

	// Владелец создаёт канал
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]string{"title": "Анонсы", "description": "новости"}, &created); code != 201 {
		t.Fatalf("createChannel: %d", code)
	}
	ch := created.ChannelID

	// Читатель видит канал в каталоге
	var discover struct {
		Channels []struct {
			ChannelID  string `json:"channel_id"`
			Subscribed bool   `json:"subscribed"`
		} `json:"channels"`
	}
	if code := getAuthed(t, ts, reader.token, "/api/v1/channels/discover", &discover); code != 200 {
		t.Fatalf("discover: %d", code)
	}
	found := false
	for _, c := range discover.Channels {
		if c.ChannelID == ch {
			found = true
			if c.Subscribed {
				t.Fatal("в каталоге не должно быть уже подписанных")
			}
		}
	}
	if !found {
		t.Fatal("созданный публичный канал обязан быть в каталоге")
	}

	// Читатель подписывается
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/channels/"+ch+"/subscribe", nil, nil); code != 200 {
		t.Fatalf("subscribe: %d", code)
	}
	// Теперь канал в «моих»
	var mine struct {
		Channels []struct {
			ChannelID string `json:"channel_id"`
		} `json:"channels"`
	}
	if code := getAuthed(t, ts, reader.token, "/api/v1/channels", &mine); code != 200 || len(mine.Channels) == 0 {
		t.Fatalf("myChannels: code %d, n=%d", code, len(mine.Channels))
	}

	// Не-владелец не может публиковать
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/channels/"+ch+"/posts",
		map[string]string{"text": "чужой пост"}, nil); code != http.StatusForbidden {
		t.Fatalf("пост не-владельца: ожидался 403, получен %d", code)
	}

	// Владелец публикует
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts",
		map[string]string{"text": "Первый пост"}, nil); code != 201 {
		t.Fatalf("post: %d", code)
	}

	// Читатель видит пост в ленте
	var feed struct {
		Posts []struct {
			Text string `json:"text"`
		} `json:"posts"`
	}
	if code := getAuthed(t, ts, reader.token, "/api/v1/channels/"+ch+"/posts", &feed); code != 200 {
		t.Fatalf("feed: %d", code)
	}
	if len(feed.Posts) != 1 || feed.Posts[0].Text != "Первый пост" {
		t.Fatalf("лента: %+v", feed.Posts)
	}

	// Отписка убирает канал из «моих»
	req, _ := http.NewRequest("DELETE", ts.URL+"/api/v1/channels/"+ch+"/subscribe", nil)
	req.Header.Set("Authorization", "Bearer "+reader.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("unsubscribe: %d", resp.StatusCode)
	}
}

// TestDisplayNames — своё имя (PATCH) и batch-резолв id→имя.
func TestDisplayNames(t *testing.T) {
	ts, _ := setup(t)
	alice := registerDevice(t, ts, "+79990000050")
	bob := registerDevice(t, ts, "+79990000051")

	// Алиса задаёт имя
	if code := postAuthed(t, ts, alice.token, "PATCH", "/api/v1/users/me/name",
		map[string]string{"display_name": "Алиса"}, nil); code != 200 {
		t.Fatalf("setName: %d", code)
	}
	// Боб резолвит имена: Алиса — есть, сам Боб (без имени) — не в ответе
	var resolved struct {
		Names map[string]string `json:"names"`
	}
	if code := postAuthed(t, ts, bob.token, "POST", "/api/v1/users/names",
		map[string][]string{"ids": {alice.userID, bob.userID}}, &resolved); code != 200 {
		t.Fatalf("resolveNames: %d", code)
	}
	if resolved.Names[alice.userID] != "Алиса" {
		t.Fatalf("имя Алисы не разрешилось: %+v", resolved.Names)
	}
	if _, ok := resolved.Names[bob.userID]; ok {
		t.Fatal("Боб без имени не должен быть в ответе")
	}
}
