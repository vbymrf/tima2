package api

import (
	"context"
	"testing"
	"time"

	"tima/server/internal/store"
)

// Поимённое разрешение у записи (ПЛАН-КАНАЛОВ К4, ADR-0019 §8).

// myByGrantPost — feed человека и его собственная запись уровня «по разрешению».
//
// Запись кладётся прямо в хранилище: своей страницы «напиши сюда» пока нет вовсе —
// на страницу попадает принесённое (К2), а собственные записи ленты появятся вместе с
// экраном. Для проверки разрешения важна сама запись, а не то, как она туда попала.
func myByGrantPost(t *testing.T, srv *Server, owner *device) (string, uint64) {
	t.Helper()
	ctx := context.Background()
	channelID, err := srv.Store.EnsureFeed(ctx, owner.userID, "Лента")
	if err != nil {
		t.Fatal(err)
	}
	postID, err := srv.Store.CreatePost(ctx, store.ChannelPost{
		ChannelID: channelID, AuthorID: owner.userID, Text: "только названным",
		Nodes: []string{"только названным"}, MarkupVersion: 1,
		CreatedAtUnixMs: time.Now().UnixMilli(), Level: levelByGrant,
	})
	if err != nil {
		t.Fatal(err)
	}
	return channelID, postID
}

func TestНазванныйПоимённоВидитЗаписьИРазговор(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990000160")
	named := registerDevice(t, ts, "+79990000161")
	outsider := registerDevice(t, ts, "+79990000162")

	channelID, postID := myByGrantPost(t, srv, owner)

	// До разрешения запись не видит никто, кроме владельца.
	var feed ownFeedAnswer
	if code := getAuthed(t, ts, outsider.token, "/api/v1/users/"+owner.userID+"/feed", &feed); code != 200 {
		t.Fatalf("чужая feed: %d", code)
	}
	if len(feed.Items) != 0 {
		t.Fatalf("запись уровня 3 видна без разрешения: %+v", feed.Items)
	}

	// Владелец называет человека — бессрочно.
	if code := postAuthed(t, ts, owner.token, "POST",
		"/api/v1/users/me/feed/items/"+uint64s(postID)+"/grants",
		map[string]any{"user_id": named.userID}, nil); code != 200 {
		t.Fatalf("выдача разрешения: %d", code)
	}
	feed = ownFeedAnswer{}
	if code := getAuthed(t, ts, named.token, "/api/v1/users/"+owner.userID+"/feed", &feed); code != 200 {
		t.Fatalf("feed названного: %d", code)
	}
	if len(feed.Items) != 1 || feed.Items[0].PostID != postID {
		t.Fatalf("named не видит открытую ему запись: %+v", feed.Items)
	}

	// И разговор под ней — потому что круг разговора берётся у корня.
	path := "/api/v1/channels/" + channelID + "/posts/" + uint64s(postID) + "/comments"
	if code := postAuthed(t, ts, named.token, "POST", path, commentBody("вижу"), nil); code != 201 {
		t.Fatalf("named не смог прокомментировать: %d", code)
	}
	if code := getAuthed(t, ts, outsider.token, path, nil); code != 404 {
		t.Fatalf("посторонний добрался до разговора под записью уровня 3: %d", code)
	}
}

func TestСрокЗакрываетБудущееАНеПрошлое(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990000163")
	named := registerDevice(t, ts, "+79990000164")
	_, postID := myByGrantPost(t, srv, owner)

	// Разрешение с уже прошедшим сроком: новые выдачи прекращаются сразу.
	past := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339)
	if code := postAuthed(t, ts, owner.token, "POST",
		"/api/v1/users/me/feed/items/"+uint64s(postID)+"/grants",
		map[string]any{"user_id": named.userID, "until": past}, nil); code != 200 {
		t.Fatalf("выдача с прошедшим сроком: %d", code)
	}
	var feed ownFeedAnswer
	if code := getAuthed(t, ts, named.token, "/api/v1/users/"+owner.userID+"/feed", &feed); code != 200 {
		t.Fatalf("feed: %d", code)
	}
	if len(feed.Items) != 0 {
		t.Fatalf("истёкшее разрешение всё ещё открывает запись: %+v", feed.Items)
	}

	// Но в списке владельца строка осталась: «я же ему открывал» не должно спорить
	// с приложением.
	var list struct {
		Grants []struct {
			UserID string `json:"user_id"`
			Until  string `json:"until"`
		} `json:"grants"`
	}
	if code := getAuthed(t, ts, owner.token,
		"/api/v1/users/me/feed/items/"+uint64s(postID)+"/grants", &list); code != 200 {
		t.Fatalf("list разрешений: %d", code)
	}
	if len(list.Grants) != 1 || list.Grants[0].UserID != named.userID || list.Grants[0].Until == "" {
		t.Fatalf("list разрешений: %+v", list.Grants)
	}
}

func TestРазрешениеСнимаетсяИОткрываетТолькоВладелец(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990000165")
	named := registerDevice(t, ts, "+79990000166")
	outsider := registerDevice(t, ts, "+79990000167")
	_, postID := myByGrantPost(t, srv, owner)

	path := "/api/v1/users/me/feed/items/" + uint64s(postID) + "/grants"

	// Посторонний не открывает чужую запись: у него своя feed, и записи с таким
	// номером в ней нет.
	if code := postAuthed(t, ts, outsider.token, "POST", path,
		map[string]any{"user_id": outsider.userID}, nil); code != 404 {
		t.Fatalf("посторонний выдал разрешение на чужую запись: %d", code)
	}

	if code := postAuthed(t, ts, owner.token, "POST", path,
		map[string]any{"user_id": named.userID}, nil); code != 200 {
		t.Fatalf("выдача: %d", code)
	}
	// Снятие — тем же маршрутом с grant:false.
	if code := postAuthed(t, ts, owner.token, "POST", path,
		map[string]any{"user_id": named.userID, "grant": false}, nil); code != 200 {
		t.Fatalf("снятие: %d", code)
	}
	var feed ownFeedAnswer
	if code := getAuthed(t, ts, named.token, "/api/v1/users/"+owner.userID+"/feed", &feed); code != 200 {
		t.Fatalf("feed: %d", code)
	}
	if len(feed.Items) != 0 {
		t.Fatalf("после снятия запись всё ещё видна: %+v", feed.Items)
	}
}
