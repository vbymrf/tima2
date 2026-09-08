package api

import (
	"testing"
)

// Выключатели обсуждения, удаление и модераторы канала (ПЛАН-КАНАЛОВ К3).

func TestВыключенноеОбсуждениеНеПринимаетНовыхНоОтдаётСтарые(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000140")
	reader := registerDevice(t, ts, "+79990000141")
	ch, root := каналСЗаписью(t, ts, owner, levelEveryone)
	путь := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	if code := postAuthed(t, ts, reader.token, "POST", путь, комментарийТело("до закрытия"), nil); code != 201 {
		t.Fatalf("комментарий до закрытия: %d", code)
	}
	// Закрываем обсуждение одной записи.
	if code := postAuthed(t, ts, owner.token, "PUT", путь, map[string]any{"closed": true}, nil); code != 200 {
		t.Fatalf("закрытие обсуждения: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", путь, комментарийТело("после"), nil); code != 403 {
		t.Fatalf("после закрытия комментарий принят: %d", code)
	}
	// Старые остались видны — иначе одно нажатие молча стирает чужие слова.
	var разговор ответРазговора
	if code := getAuthed(t, ts, reader.token, путь, &разговор); code != 200 || len(разговор.Comments) != 1 {
		t.Fatalf("после закрытия разговор: %d, %+v", code, разговор.Comments)
	}

	// Тот же опыт с выключателем канала целиком.
	if code := postAuthed(t, ts, owner.token, "PUT", путь, map[string]any{"closed": false}, nil); code != 200 {
		t.Fatalf("открытие обсуждения: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/channels/"+ch+"/comments",
		map[string]any{"enabled": false}, nil); code != 200 {
		t.Fatalf("выключение комментариев канала: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", путь, комментарийТело("в закрытом канале"), nil); code != 403 {
		t.Fatalf("в канале без обсуждений комментарий принят: %d", code)
	}
}

func TestВыключатьОбсуждениеМожетТолькоСвой(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000142")
	чужой := registerDevice(t, ts, "+79990000143")
	ch, root := каналСЗаписью(t, ts, owner, levelEveryone)

	if code := postAuthed(t, ts, чужой.token, "PUT", "/api/v1/channels/"+ch+"/comments",
		map[string]any{"enabled": false}, nil); code != 403 {
		t.Fatalf("посторонний выключил комментарии канала: %d", code)
	}
	if code := postAuthed(t, ts, чужой.token, "PUT",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments",
		map[string]any{"closed": true}, nil); code != 403 {
		t.Fatalf("посторонний закрыл обсуждение записи: %d", code)
	}
}

func TestУдалениеКомментарияАвторВладелецМодератор(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000144")
	автор := registerDevice(t, ts, "+79990000145")
	чужой := registerDevice(t, ts, "+79990000146")
	модератор := registerDevice(t, ts, "+79990000147")

	ch, root := каналСЗаписью(t, ts, owner, levelEveryone)
	путь := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	комментарий := func(d *device, текст string) uint64 {
		t.Helper()
		var c struct {
			PostID uint64 `json:"post_id"`
		}
		if code := postAuthed(t, ts, d.token, "POST", путь, комментарийТело(текст), &c); code != 201 {
			t.Fatalf("комментарий %q: %d", текст, code)
		}
		return c.PostID
	}
	удалить := func(d *device, id uint64) int {
		t.Helper()
		return postAuthed(t, ts, d.token, "DELETE", "/api/v1/channels/"+ch+"/posts/"+uint64s(id), nil, nil)
	}

	свой := комментарий(автор, "мой")
	// Чужой не трогает чужое.
	if code := удалить(чужой, свой); code != 403 {
		t.Fatalf("посторонний удалил чужой комментарий: %d", code)
	}
	// Автор убирает своё.
	if code := удалить(автор, свой); code != 204 {
		t.Fatalf("автор не смог убрать своё: %d", code)
	}

	// Владелец убирает любое у себя.
	второй := комментарий(автор, "второй")
	if code := удалить(owner, второй); code != 204 {
		t.Fatalf("владелец не смог убрать комментарий: %d", code)
	}

	// Модератор — тоже, и это ровно то, ради чего заведена роль.
	третий := комментарий(автор, "третий")
	if code := удалить(модератор, третий); code != 403 {
		t.Fatalf("не назначенный модератором удалил: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/moderators",
		map[string]any{"user_id": модератор.userID}, nil); code != 201 {
		t.Fatalf("назначение модератора: %d", code)
	}
	if code := удалить(модератор, третий); code != 204 {
		t.Fatalf("модератор не смог убрать комментарий: %d", code)
	}

	// Снятый модератор снова никто.
	четвёртый := комментарий(автор, "четвёртый")
	if code := postAuthed(t, ts, owner.token, "DELETE",
		"/api/v1/channels/"+ch+"/moderators/"+модератор.userID, nil, nil); code != 204 {
		t.Fatalf("снятие модератора: %d", code)
	}
	if code := удалить(модератор, четвёртый); code != 403 {
		t.Fatalf("снятый модератор удалил: %d", code)
	}

	// В разговоре остались только живые.
	var разговор ответРазговора
	if code := getAuthed(t, ts, чужой.token, путь, &разговор); code != 200 {
		t.Fatalf("разговор: %d", code)
	}
	if len(разговор.Comments) != 1 || разговор.Comments[0].Text != "четвёртый" {
		t.Fatalf("после удалений остались: %+v", разговор.Comments)
	}
}

func TestУдалённыйКореньУноситРазговор(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000148")
	reader := registerDevice(t, ts, "+79990000149")
	ch, root := каналСЗаписью(t, ts, owner, levelEveryone)
	путь := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	if code := postAuthed(t, ts, reader.token, "POST", путь, комментарийТело("под корнем"), nil); code != 201 {
		t.Fatalf("комментарий: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "DELETE",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root), nil, nil); code != 204 {
		t.Fatalf("удаление корня: %d", code)
	}
	// Комментарий не отдаётся без корня, а корня нет — сироты выглядят как поломка.
	if code := getAuthed(t, ts, reader.token, путь, nil); code != 404 {
		t.Fatalf("разговор под удалённым корнем: %d, ожидалось 404", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", путь, комментарийТело("ещё"), nil); code != 404 {
		t.Fatalf("комментарий под удалённым корнем: %d", code)
	}
}

func TestМодератораНазначаетТолькоВладелец(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000150")
	чужой := registerDevice(t, ts, "+79990000151")
	ch, _ := каналСЗаписью(t, ts, owner, levelEveryone)

	if code := postAuthed(t, ts, чужой.token, "POST", "/api/v1/channels/"+ch+"/moderators",
		map[string]any{"user_id": чужой.userID}, nil); code != 403 {
		t.Fatalf("посторонний назначил себя модератором: %d", code)
	}
	var список struct {
		Moderators []string `json:"moderators"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/channels/"+ch+"/moderators", &список); code != 200 {
		t.Fatalf("список модераторов: %d", code)
	}
	if len(список.Moderators) != 0 {
		t.Fatalf("модераторы завелись сами: %+v", список.Moderators)
	}
}
