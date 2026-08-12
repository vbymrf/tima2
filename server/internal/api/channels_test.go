package api

import (
	"bytes"
	"context"
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

// Публичный контур узлов и разметки (ADR-0011 §4/§5, Р5б): пост хранит nodes и
// markup открытым текстом, и оба доходят до читателя лентой. Старый клиент, не
// приславший nodes, получает один узел — тот же переходный путь, что у личных
// сообщений.
func TestChannelPostNodesAndMarkup(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	owner := registerDevice(t, ts, "+79990000070")

	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]string{"title": "С разметкой"}, &created); code != 201 {
		t.Fatalf("createChannel: %d", code)
	}
	ch := created.ChannelID

	markup := `{"version":1,"n":[1],"blocks":[{"type":"heading","nodes":[1],"level":1}]}`
	var posted struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts", map[string]any{
		"text": "Заголовок", "nodes": []string{"Заголовок"}, "markup": markup,
	}, &posted); code != 201 {
		t.Fatalf("post с разметкой: %d", code)
	}

	var feed struct {
		Posts []struct {
			Text   string          `json:"text"`
			Nodes  []string        `json:"nodes"`
			Markup json.RawMessage `json:"markup"`
		} `json:"posts"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/channels/"+ch+"/posts", &feed); code != 200 {
		t.Fatalf("feed: %d", code)
	}
	if len(feed.Posts) != 1 {
		t.Fatalf("лента: %+v", feed.Posts)
	}
	got := feed.Posts[0]
	if len(got.Nodes) != 1 || got.Nodes[0] != "Заголовок" {
		t.Fatalf("nodes не дошли до ленты: %+v", got.Nodes)
	}
	if string(got.Markup) == "" || string(got.Markup) == "null" {
		t.Fatal("markup не дошла до ленты")
	}

	// Хранилище тоже видит открытым текстом — это и есть смысл публичного контура.
	posts, err := srv.Store.ListPosts(ctx, ch, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(posts) != 1 || len(posts[0].Nodes) != 1 || posts[0].Nodes[0] != "Заголовок" {
		t.Fatalf("реестр: %+v", posts)
	}
	if len(posts[0].Markup) == 0 {
		t.Fatal("markup не попала в реестр")
	}

	// Пост без nodes (старый клиент) — один узел из text, не пустая лента.
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts",
		map[string]string{"text": "Простой пост"}, nil); code != 201 {
		t.Fatalf("post без nodes: %d", code)
	}
	posts, err = srv.Store.ListPosts(ctx, ch, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	plain := posts[0] // новые первые
	if len(plain.Nodes) != 1 || plain.Nodes[0] != "Простой пост" {
		t.Fatalf("пост без nodes не завернулся в один узел: %+v", plain.Nodes)
	}

	// Битый markup — отказ, а не порча ленты.
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts", map[string]any{
		"text": "плохая разметка", "markup": "{это не json",
	}, nil); code != http.StatusBadRequest {
		t.Fatalf("битый markup: код %d, ожидали 400", code)
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

// TestResolvePhones — номер отдаётся только собеседнику по личному чату: UI должен
// показать «Имя +7999…» тому, кому написали, но не выдать номер чужому по user_id.
func TestResolvePhones(t *testing.T) {
	ts, _ := setup(t)
	alice := registerDevice(t, ts, "+79990000060")
	bob := registerDevice(t, ts, "+79990000061")
	carol := registerDevice(t, ts, "+79990000062") // ни с кем не переписывалась

	// Алиса пишет Бобу
	env := sealEnvelope(t, alice, []*device{bob}, 2001, []byte("привет"))
	resp := post(t, ts, env, alice.token, "eeeeeeee-0000-0000-0000-000000002001")
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("POST /messages: %d", resp.StatusCode)
	}

	var resolved struct {
		Phones map[string]string `json:"phones"`
	}
	if code := postAuthed(t, ts, bob.token, "POST", "/api/v1/users/names",
		map[string][]string{"ids": {alice.userID, carol.userID}}, &resolved); code != 200 {
		t.Fatalf("resolveNames: %d", code)
	}
	if resolved.Phones[alice.userID] != "+79990000060" {
		t.Fatalf("Боб должен видеть номер написавшей ему Алисы: %+v", resolved.Phones)
	}
	if _, ok := resolved.Phones[carol.userID]; ok {
		t.Fatal("номер Кэрол не должен утекать — переписки с ней нет")
	}
}
