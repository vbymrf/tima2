package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"testing"
)

// Лента канала без пузыря и «Описание» (ПЛАН-(СЦ) §1.18, СЦ37–СЦ40, заказчик 2026-11-25).

type reactionsReply struct {
	Reactions []struct {
		Emotion string `json:"emotion"`
		Count   int    `json:"count"`
	} `json:"reactions"`
	Mine string `json:"mine"`
}

type reactedPost struct {
	PostID    uint64 `json:"post_id"`
	Reactions []struct {
		Emotion string `json:"emotion"`
		Count   int    `json:"count"`
	} `json:"reactions"`
	MyEmotion string `json:"my_emotion"`
	MyVote    int    `json:"my_vote"`
	Comments  int    `json:"comments"`
}

// Эмоции — одна на человека, видны всем; палец — только свой; посторонний не оценивает.
func TestЭмоцииИПальцыЗаписиКанала(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199101")
	reader := registerDevice(t, ts, "+79990199102")
	outsider := registerDevice(t, ts, "+79990199103")
	ch := newChannel(t, ts, owner, reader)
	base := "/api/v1/channels/" + ch
	var created struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", base+"/posts", map[string]any{"text": "маршрут"}, &created); code != 201 {
		t.Fatalf("запись: %d", code)
	}
	post := strconv.FormatUint(created.PostID, 10)
	reaction := "/api/v1/reactions/channel/" + ch + "/" + post

	var got reactionsReply
	if code := postAuthed(t, ts, reader.token, "PUT", reaction, map[string]string{"emotion": "interest"}, &got); code != 200 ||
		got.Mine != "interest" || len(got.Reactions) != 1 || got.Reactions[0].Count != 1 {
		t.Fatalf("эмоция подписчика: %d %+v", code, got)
	}
	// Своя — одна: смена заменяет, а не добавляет
	if code := postAuthed(t, ts, reader.token, "PUT", reaction, map[string]string{"emotion": "laughter"}, &got); code != 200 ||
		got.Mine != "laughter" || len(got.Reactions) != 1 {
		t.Fatalf("смена эмоции: %d %+v", code, got)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", reaction, map[string]string{"emotion": "laughter"}, &got); code != 200 ||
		got.Reactions[0].Count != 2 {
		t.Fatalf("эмоция владельца: %d %+v", code, got)
	}
	if code := postAuthed(t, ts, reader.token, "PUT", reaction, map[string]string{"emotion": "surprise"}, nil); code != 400 {
		t.Fatalf("эмоция не из шкалы: %d", code)
	}
	if code := postAuthed(t, ts, outsider.token, "PUT", reaction, map[string]string{"emotion": "calm"}, nil); code != 403 {
		t.Fatalf("посторонний поставил эмоцию: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "PUT", "/api/v1/reactions/channel/"+ch+"/999999", map[string]string{"emotion": "calm"}, nil); code != 404 {
		t.Fatalf("эмоция несуществующей записи: %d", code)
	}

	// Палец — частный: подписчик видит свой, владелец — нет
	if code := postAuthed(t, ts, reader.token, "PUT", "/api/v1/votes/channel/"+ch+"/"+post, map[string]int{"vote": 1}, nil); code != 200 {
		t.Fatalf("палец вверх: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "PUT", "/api/v1/votes/channel/"+ch+"/"+post, map[string]int{"vote": 2}, nil); code != 400 {
		t.Fatalf("палец не из −1..1: %d", code)
	}
	var feed struct {
		Posts []reactedPost `json:"posts"`
	}
	if code := getAuthed(t, ts, reader.token, base+"/posts", &feed); code != 200 || len(feed.Posts) != 1 {
		t.Fatalf("лента: %d", code)
	}
	if p := feed.Posts[0]; p.MyEmotion != "laughter" || p.MyVote != 1 || len(p.Reactions) != 1 || p.Reactions[0].Count != 2 {
		t.Fatalf("лента подписчика: %+v", p)
	}
	if code := getAuthed(t, ts, owner.token, base+"/posts", &feed); code != 200 || feed.Posts[0].MyVote != 0 {
		t.Fatalf("чужой палец виден: %+v", feed.Posts)
	}

	// Снять эмоцию и палец
	if code := postAuthed(t, ts, reader.token, "DELETE", reaction, nil, &got); code != 200 || got.Mine != "" || got.Reactions[0].Count != 1 {
		t.Fatalf("снять эмоцию: %d %+v", code, got)
	}
	if code := postAuthed(t, ts, reader.token, "PUT", "/api/v1/votes/channel/"+ch+"/"+post, map[string]int{"vote": 0}, nil); code != 200 {
		t.Fatalf("снять палец: %d", code)
	}
	if code := getAuthed(t, ts, reader.token, base+"/posts", &feed); code != 200 || feed.Posts[0].MyVote != 0 || feed.Posts[0].MyEmotion != "" {
		t.Fatalf("после снятия: %+v", feed.Posts)
	}
}

// Своих и чужих записей у канала нет: правят автор и команда, подписчик — нет (§1.18, 8, 5б).
func TestЗаписьКаналаПравитКоманда(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199111")
	mod := registerDevice(t, ts, "+79990199112")
	reader := registerDevice(t, ts, "+79990199113")
	ch := newChannel(t, ts, owner, mod, reader)
	base := "/api/v1/channels/" + ch
	if code := postAuthed(t, ts, owner.token, "POST", base+"/moderators", map[string]string{"user_id": mod.userID}, nil); code != 201 {
		t.Fatalf("модератор: %d", code)
	}
	var created struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", base+"/posts", map[string]any{"text": "владельца"}, &created); code != 201 {
		t.Fatalf("запись: %d", code)
	}
	path := base + "/posts/" + strconv.FormatUint(created.PostID, 10)
	if code := postAuthed(t, ts, mod.token, "PATCH", path, map[string]any{"text": "поправил модератор"}, nil); code != 204 {
		t.Fatalf("модератор правит запись владельца: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "PATCH", path, map[string]any{"text": "подписчик"}, nil); code != 403 {
		t.Fatalf("подписчик правит запись: %d", code)
	}
}

// Настройки «Описания» — владелец и админ; видны в «Описании». Комментарии к уровню 0 — по настройке, пишет участник,
// читает каждый, кто видит «Описание»; эмоции и число комментариев — в «Описании» (§1.18, 11).
func TestОписаниеНастройкиИКомментарии(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199121")
	reader := registerDevice(t, ts, "+79990199122")
	outsider := registerDevice(t, ts, "+79990199123")
	community := makeCommunity(t, ts, owner, "Прогулки")
	cbase := "/api/v1/communities/" + community
	if code := postAuthed(t, ts, reader.token, "POST", cbase+"/subscribe", nil, nil); code != 200 {
		t.Fatalf("подписка на сообщество: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", cbase+"/messages", map[string]any{"text": "о нас"}, nil); code != 201 {
		t.Fatalf("описание: %d", code)
	}
	type showcase struct {
		Settings struct {
			Whole    bool `json:"whole"`
			Comments bool `json:"comments"`
		} `json:"settings"`
		Messages []struct {
			MessageID int64  `json:"message_id"`
			Comments  int    `json:"comments"`
			MyEmotion string `json:"my_emotion"`
		} `json:"messages"`
	}
	var page showcase
	if code := getAuthed(t, ts, reader.token, cbase+"/showcase", &page); code != 200 || len(page.Messages) != 1 ||
		page.Settings.Whole || page.Settings.Comments {
		t.Fatalf("описание по умолчанию: %d %+v", code, page)
	}
	msg := strconv.FormatInt(page.Messages[0].MessageID, 10)
	comments := "/api/v1/level0/community/" + community + "/" + msg + "/comments"

	if code := postAuthed(t, ts, reader.token, "POST", comments, map[string]any{"text": "здорово"}, nil); code != 403 {
		t.Fatalf("комментарий при выключенной настройке: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "PUT", cbase+"/showcase-settings", map[string]any{"comments": true}, nil); code != 403 {
		t.Fatalf("подписчик поменял настройку: %d", code)
	}
	var settings struct {
		Whole    bool `json:"whole"`
		Comments bool `json:"comments"`
	}
	if code := postAuthed(t, ts, owner.token, "PUT", cbase+"/showcase-settings", map[string]any{"comments": true}, &settings); code != 200 ||
		!settings.Comments || settings.Whole {
		t.Fatalf("включить комментарии: %d %+v", code, settings)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", cbase+"/showcase-settings", map[string]any{"whole": true}, &settings); code != 200 ||
		!settings.Comments || !settings.Whole {
		t.Fatalf("целиком, комментарии остались: %d %+v", code, settings)
	}
	if code := postAuthed(t, ts, reader.token, "POST", comments, map[string]any{"text": "здорово"}, nil); code != 201 {
		t.Fatalf("комментарий подписчика: %d", code)
	}
	if code := postAuthed(t, ts, outsider.token, "POST", comments, map[string]any{"text": "а я?"}, nil); code != 403 {
		t.Fatalf("посторонний прокомментировал: %d", code)
	}
	var list struct {
		Comments []struct {
			Text     string `json:"text"`
			AuthorID string `json:"author_id"`
		} `json:"comments"`
		Allowed bool `json:"allowed"`
	}
	if code := getAuthed(t, ts, outsider.token, comments, &list); code != 200 || len(list.Comments) != 1 || list.Allowed ||
		list.Comments[0].AuthorID != reader.userID {
		t.Fatalf("посторонний читает комментарии: %d %+v", code, list)
	}
	if code := getAuthed(t, ts, reader.token, comments, &list); code != 200 || !list.Allowed {
		t.Fatalf("подписчику можно писать: %d %+v", code, list)
	}
	if code := postAuthed(t, ts, reader.token, "PUT", "/api/v1/reactions/community/"+community+"/"+msg,
		map[string]string{"emotion": "approval"}, nil); code != 200 {
		t.Fatalf("эмоция описания: %d", code)
	}
	if code := getAuthed(t, ts, reader.token, cbase+"/showcase", &page); code != 200 || !page.Settings.Whole ||
		!page.Settings.Comments || page.Messages[0].Comments != 1 || page.Messages[0].MyEmotion != "approval" {
		t.Fatalf("описание с настройками, комментариями, эмоцией: %d %+v", code, page)
	}
	if code := postAuthed(t, ts, reader.token, "GET", "/api/v1/level0/channel/"+community+"/"+msg+"/comments", nil, nil); code != 400 {
		t.Fatalf("комментарии уровня 0 у канала — через запись: %d", code)
	}
}

// Сообщение уровня 0 группы — эмоция участника; сообщение не уровня 0 — нет.
func TestЭмоцияОписанияГруппы(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199131")
	groupID := createPublicGroupAPI(t, ts, owner.token)
	code, resp := sendLeveled(t, ts, owner, groupID, "c0000000-0000-0000-0000-000000009131", levelPublicShowcase,
		openBody(t, "описание группы"))
	if code != http.StatusCreated {
		t.Fatalf("уровень 0: %d", code)
	}
	var id int64
	if err := json.Unmarshal(resp["message_id"], &id); err != nil || id == 0 {
		t.Fatalf("номер сообщения: %v %s", err, resp["message_id"])
	}
	path := "/api/v1/reactions/group/" + groupID + "/" + strconv.FormatInt(id, 10)
	var got reactionsReply
	if code := postAuthed(t, ts, owner.token, "PUT", path, map[string]string{"emotion": "calm"}, &got); code != 200 || got.Mine != "calm" {
		t.Fatalf("эмоция описания группы: %d %+v", code, got)
	}
	code, resp = sendLeveled(t, ts, owner, groupID, "c0000000-0000-0000-0000-000000009132", 1, openBody(t, "обычное"))
	if code != http.StatusCreated {
		t.Fatalf("уровень 1: %d", code)
	}
	_ = json.Unmarshal(resp["message_id"], &id)
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/reactions/group/"+groupID+"/"+strconv.FormatInt(id, 10),
		map[string]string{"emotion": "calm"}, nil); code != 404 {
		t.Fatalf("эмоция у сообщения не уровня 0: %d", code)
	}
}
