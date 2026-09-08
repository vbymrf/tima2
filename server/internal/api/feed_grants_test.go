package api

import (
	"context"
	"testing"
	"time"

	"tima/server/internal/store"
)

// Поимённое разрешение у записи (ПЛАН-КАНАЛОВ К4, ADR-0019 §8).

// своя3Уровня — лента человека и его собственная запись уровня «по разрешению».
//
// Запись кладётся прямо в хранилище: своей страницы «напиши сюда» пока нет вовсе —
// на страницу попадает принесённое (К2), а собственные записи ленты появятся вместе с
// экраном. Для проверки разрешения важна сама запись, а не то, как она туда попала.
func своя3Уровня(t *testing.T, srv *Server, owner *device) (string, uint64) {
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
	названный := registerDevice(t, ts, "+79990000161")
	чужой := registerDevice(t, ts, "+79990000162")

	channelID, postID := своя3Уровня(t, srv, owner)

	// До разрешения запись не видит никто, кроме владельца.
	var лента свояЛента
	if code := getAuthed(t, ts, чужой.token, "/api/v1/users/"+owner.userID+"/feed", &лента); code != 200 {
		t.Fatalf("чужая лента: %d", code)
	}
	if len(лента.Items) != 0 {
		t.Fatalf("запись уровня 3 видна без разрешения: %+v", лента.Items)
	}

	// Владелец называет человека — бессрочно.
	if code := postAuthed(t, ts, owner.token, "POST",
		"/api/v1/users/me/feed/items/"+uint64s(postID)+"/grants",
		map[string]any{"user_id": названный.userID}, nil); code != 200 {
		t.Fatalf("выдача разрешения: %d", code)
	}
	лента = свояЛента{}
	if code := getAuthed(t, ts, названный.token, "/api/v1/users/"+owner.userID+"/feed", &лента); code != 200 {
		t.Fatalf("лента названного: %d", code)
	}
	if len(лента.Items) != 1 || лента.Items[0].PostID != postID {
		t.Fatalf("названный не видит открытую ему запись: %+v", лента.Items)
	}

	// И разговор под ней — потому что круг разговора берётся у корня.
	путь := "/api/v1/channels/" + channelID + "/posts/" + uint64s(postID) + "/comments"
	if code := postAuthed(t, ts, названный.token, "POST", путь, комментарийТело("вижу"), nil); code != 201 {
		t.Fatalf("названный не смог прокомментировать: %d", code)
	}
	if code := getAuthed(t, ts, чужой.token, путь, nil); code != 404 {
		t.Fatalf("посторонний добрался до разговора под записью уровня 3: %d", code)
	}
}

func TestСрокЗакрываетБудущееАНеПрошлое(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990000163")
	названный := registerDevice(t, ts, "+79990000164")
	_, postID := своя3Уровня(t, srv, owner)

	// Разрешение с уже прошедшим сроком: новые выдачи прекращаются сразу.
	прошлое := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339)
	if code := postAuthed(t, ts, owner.token, "POST",
		"/api/v1/users/me/feed/items/"+uint64s(postID)+"/grants",
		map[string]any{"user_id": названный.userID, "until": прошлое}, nil); code != 200 {
		t.Fatalf("выдача с прошедшим сроком: %d", code)
	}
	var лента свояЛента
	if code := getAuthed(t, ts, названный.token, "/api/v1/users/"+owner.userID+"/feed", &лента); code != 200 {
		t.Fatalf("лента: %d", code)
	}
	if len(лента.Items) != 0 {
		t.Fatalf("истёкшее разрешение всё ещё открывает запись: %+v", лента.Items)
	}

	// Но в списке владельца строка осталась: «я же ему открывал» не должно спорить
	// с приложением.
	var список struct {
		Grants []struct {
			UserID string `json:"user_id"`
			Until  string `json:"until"`
		} `json:"grants"`
	}
	if code := getAuthed(t, ts, owner.token,
		"/api/v1/users/me/feed/items/"+uint64s(postID)+"/grants", &список); code != 200 {
		t.Fatalf("список разрешений: %d", code)
	}
	if len(список.Grants) != 1 || список.Grants[0].UserID != названный.userID || список.Grants[0].Until == "" {
		t.Fatalf("список разрешений: %+v", список.Grants)
	}
}

func TestРазрешениеСнимаетсяИОткрываетТолькоВладелец(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990000165")
	названный := registerDevice(t, ts, "+79990000166")
	чужой := registerDevice(t, ts, "+79990000167")
	_, postID := своя3Уровня(t, srv, owner)

	путь := "/api/v1/users/me/feed/items/" + uint64s(postID) + "/grants"

	// Посторонний не открывает чужую запись: у него своя лента, и записи с таким
	// номером в ней нет.
	if code := postAuthed(t, ts, чужой.token, "POST", путь,
		map[string]any{"user_id": чужой.userID}, nil); code != 404 {
		t.Fatalf("посторонний выдал разрешение на чужую запись: %d", code)
	}

	if code := postAuthed(t, ts, owner.token, "POST", путь,
		map[string]any{"user_id": названный.userID}, nil); code != 200 {
		t.Fatalf("выдача: %d", code)
	}
	// Снятие — тем же маршрутом с grant:false.
	if code := postAuthed(t, ts, owner.token, "POST", путь,
		map[string]any{"user_id": названный.userID, "grant": false}, nil); code != 200 {
		t.Fatalf("снятие: %d", code)
	}
	var лента свояЛента
	if code := getAuthed(t, ts, названный.token, "/api/v1/users/"+owner.userID+"/feed", &лента); code != 200 {
		t.Fatalf("лента: %d", code)
	}
	if len(лента.Items) != 0 {
		t.Fatalf("после снятия запись всё ещё видна: %+v", лента.Items)
	}
}
