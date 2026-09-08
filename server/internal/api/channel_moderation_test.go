package api

import (
	"testing"
)

// Выключатели обсуждения, удаление и moderatorы канала (ПЛАН-КАНАЛОВ К3).

func TestВыключенноеОбсуждениеНеПринимаетНовыхНоОтдаётСтарые(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000140")
	reader := registerDevice(t, ts, "+79990000141")
	ch, root := channelWithPost(t, ts, owner, levelEveryone)
	path := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	if code := postAuthed(t, ts, reader.token, "POST", path, commentBody("до закрытия"), nil); code != 201 {
		t.Fatalf("addComment2 до закрытия: %d", code)
	}
	// Закрываем обсуждение одной записи.
	if code := postAuthed(t, ts, owner.token, "PUT", path, map[string]any{"closed": true}, nil); code != 200 {
		t.Fatalf("закрытие обсуждения: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", path, commentBody("после"), nil); code != 403 {
		t.Fatalf("после закрытия addComment2 принят: %d", code)
	}
	// Старые остались видны — иначе одно нажатие молча стирает чужие слова.
	var talk conversationAnswer
	if code := getAuthed(t, ts, reader.token, path, &talk); code != 200 || len(talk.Comments) != 1 {
		t.Fatalf("после закрытия talk: %d, %+v", code, talk.Comments)
	}

	// Тот же опыт с выключателем канала целиком.
	if code := postAuthed(t, ts, owner.token, "PUT", path, map[string]any{"closed": false}, nil); code != 200 {
		t.Fatalf("открытие обсуждения: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/channels/"+ch+"/comments",
		map[string]any{"enabled": false}, nil); code != 200 {
		t.Fatalf("выключение комментариев канала: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", path, commentBody("в закрытом канале"), nil); code != 403 {
		t.Fatalf("в канале без обсуждений addComment2 принят: %d", code)
	}
}

func TestВыключатьОбсуждениеМожетТолькоСвой(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000142")
	outsider := registerDevice(t, ts, "+79990000143")
	ch, root := channelWithPost(t, ts, owner, levelEveryone)

	if code := postAuthed(t, ts, outsider.token, "PUT", "/api/v1/channels/"+ch+"/comments",
		map[string]any{"enabled": false}, nil); code != 403 {
		t.Fatalf("посторонний выключил комментарии канала: %d", code)
	}
	if code := postAuthed(t, ts, outsider.token, "PUT",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments",
		map[string]any{"closed": true}, nil); code != 403 {
		t.Fatalf("посторонний закрыл обсуждение записи: %d", code)
	}
}

func TestУдалениеКомментарияАвторВладелецМодератор(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000144")
	author := registerDevice(t, ts, "+79990000145")
	outsider := registerDevice(t, ts, "+79990000146")
	moderator := registerDevice(t, ts, "+79990000147")

	ch, root := channelWithPost(t, ts, owner, levelEveryone)
	path := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	addComment2 := func(d *device, текст string) uint64 {
		t.Helper()
		var c struct {
			PostID uint64 `json:"post_id"`
		}
		if code := postAuthed(t, ts, d.token, "POST", path, commentBody(текст), &c); code != 201 {
			t.Fatalf("addComment2 %q: %d", текст, code)
		}
		return c.PostID
	}
	removePost := func(d *device, id uint64) int {
		t.Helper()
		return postAuthed(t, ts, d.token, "DELETE", "/api/v1/channels/"+ch+"/posts/"+uint64s(id), nil, nil)
	}

	mine := addComment2(author, "мой")
	// Чужой не трогает чужое.
	if code := removePost(outsider, mine); code != 403 {
		t.Fatalf("посторонний удалил outsider addComment2: %d", code)
	}
	// Автор убирает своё.
	if code := removePost(author, mine); code != 204 {
		t.Fatalf("author не смог убрать своё: %d", code)
	}

	// Владелец убирает любое у себя.
	second := addComment2(author, "second")
	if code := removePost(owner, second); code != 204 {
		t.Fatalf("владелец не смог убрать addComment2: %d", code)
	}

	// Модератор — тоже, и это ровно то, ради чего заведена роль.
	third := addComment2(author, "third")
	if code := removePost(moderator, third); code != 403 {
		t.Fatalf("не назначенный moderatorом удалил: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/moderators",
		map[string]any{"user_id": moderator.userID}, nil); code != 201 {
		t.Fatalf("назначение moderatorа: %d", code)
	}
	if code := removePost(moderator, third); code != 204 {
		t.Fatalf("moderator не смог убрать addComment2: %d", code)
	}

	// Снятый moderator снова никто.
	fourth := addComment2(author, "fourth")
	if code := postAuthed(t, ts, owner.token, "DELETE",
		"/api/v1/channels/"+ch+"/moderators/"+moderator.userID, nil, nil); code != 204 {
		t.Fatalf("снятие moderatorа: %d", code)
	}
	if code := removePost(moderator, fourth); code != 403 {
		t.Fatalf("снятый moderator удалил: %d", code)
	}

	// В talkе остались только живые.
	var talk conversationAnswer
	if code := getAuthed(t, ts, outsider.token, path, &talk); code != 200 {
		t.Fatalf("talk: %d", code)
	}
	if len(talk.Comments) != 1 || talk.Comments[0].Text != "fourth" {
		t.Fatalf("после удалений остались: %+v", talk.Comments)
	}
}

func TestУдалённыйКореньУноситРазговор(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000148")
	reader := registerDevice(t, ts, "+79990000149")
	ch, root := channelWithPost(t, ts, owner, levelEveryone)
	path := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	if code := postAuthed(t, ts, reader.token, "POST", path, commentBody("под корнем"), nil); code != 201 {
		t.Fatalf("addComment2: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "DELETE",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root), nil, nil); code != 204 {
		t.Fatalf("удаление корня: %d", code)
	}
	// Комментарий не отдаётся без корня, а корня нет — сироты выглядят как поломка.
	if code := getAuthed(t, ts, reader.token, path, nil); code != 404 {
		t.Fatalf("talk под удалённым корнем: %d, ожидалось 404", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", path, commentBody("ещё"), nil); code != 404 {
		t.Fatalf("addComment2 под удалённым корнем: %d", code)
	}
}

func TestМодератораНазначаетТолькоВладелец(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000150")
	outsider := registerDevice(t, ts, "+79990000151")
	ch, _ := channelWithPost(t, ts, owner, levelEveryone)

	if code := postAuthed(t, ts, outsider.token, "POST", "/api/v1/channels/"+ch+"/moderators",
		map[string]any{"user_id": outsider.userID}, nil); code != 403 {
		t.Fatalf("посторонний назначил себя moderatorом: %d", code)
	}
	var list struct {
		Moderators []string `json:"moderators"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/channels/"+ch+"/moderators", &list); code != 200 {
		t.Fatalf("list moderatorов: %d", code)
	}
	if len(list.Moderators) != 0 {
		t.Fatalf("moderatorы завелись сами: %+v", list.Moderators)
	}
}
