package api

import (
	"net/http/httptest"
	"strconv"
	"testing"
)

// uint64s — номер записи в пути. Отдельной функцией, чтобы strconv не мелькал в каждой строке.
func uint64s(v uint64) string { return strconv.FormatUint(v, 10) }

// Комментарии в каналах и лентах (ADR-0024, ПЛАН-КАНАЛОВ К1).
//
// Проверяется ровно то, что план назвал проверяемым: комментарий не приходит в ленте,
// приходит в разговоре, комментарий к комментарию отклонён, круг берётся у корня, счётчик
// считается вместе с записями.

// комментарийТело — тело запроса на комментарий.
func комментарийТело(text string) map[string]any { return map[string]any{"text": text} }

type ответЛенты struct {
	Posts []struct {
		PostID   uint64 `json:"post_id"`
		Level    int16  `json:"level"`
		Comments int    `json:"comments"`
		Text     string `json:"text"`
	} `json:"posts"`
}

type ответРазговора struct {
	PostID   uint64 `json:"post_id"`
	Level    int16  `json:"level"`
	Comments []struct {
		PostID       uint64 `json:"post_id"`
		AuthorID     string `json:"author_id"`
		Text         string `json:"text"`
		ParentPostID uint64 `json:"parent_post_id"`
	} `json:"comments"`
}

// каналСЗаписью — канал владельца и одна запись нужного круга.
func каналСЗаписью(t *testing.T, ts *httptest.Server, owner *device, level int16) (string, uint64) {
	t.Helper()
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels",
		map[string]string{"title": "Ядро"}, &created); code != 201 {
		t.Fatalf("создание канала: %d", code)
	}
	var post struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+created.ChannelID+"/posts",
		map[string]any{"text": "запись", "level": level}, &post); code != 201 {
		t.Fatalf("публикация уровня %d: %d", level, code)
	}
	return created.ChannelID, post.PostID
}

func TestКомментарийНеПриходитВЛентеНоПриходитВРазговоре(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000120")
	reader := registerDevice(t, ts, "+79990000121")

	ch, root := каналСЗаписью(t, ts, owner, levelEveryone)

	// Посторонний комментирует запись уровня «всем»: подписка для этого не нужна —
	// комментирует тот, кто видит (ADR-0024 §8).
	var созданный struct {
		PostID       uint64 `json:"post_id"`
		ParentPostID uint64 `json:"parent_post_id"`
	}
	if code := postAuthed(t, ts, reader.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments",
		комментарийТело("а что это"), &созданный); code != 201 {
		t.Fatalf("комментарий не принят: %d", code)
	}
	if созданный.ParentPostID != root {
		t.Fatalf("комментарий привязан не к тому корню: %+v", созданный)
	}

	// В ленте канала его нет — иначе ветка стала бы записью.
	var лента ответЛенты
	if code := getAuthed(t, ts, reader.token, "/api/v1/channels/"+ch+"/posts", &лента); code != 200 {
		t.Fatalf("лента: %d", code)
	}
	if len(лента.Posts) != 1 {
		t.Fatalf("в ленте обязана быть одна запись, а не %d: %+v", len(лента.Posts), лента.Posts)
	}
	if лента.Posts[0].PostID != root {
		t.Fatalf("в ленте не та запись: %+v", лента.Posts[0])
	}
	// Счётчик приходит вместе с записью, а не отдельным запросом.
	if лента.Posts[0].Comments != 1 {
		t.Fatalf("счётчик комментариев: %d, ожидался 1", лента.Posts[0].Comments)
	}

	// А в разговоре — есть.
	var разговор ответРазговора
	if code := getAuthed(t, ts, reader.token,
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments", &разговор); code != 200 {
		t.Fatalf("разговор: %d", code)
	}
	if len(разговор.Comments) != 1 || разговор.Comments[0].Text != "а что это" {
		t.Fatalf("разговор: %+v", разговор.Comments)
	}
	if разговор.Comments[0].AuthorID != reader.userID {
		t.Fatal("автором комментария обязан быть комментатор, а не владелец канала")
	}
	// Круг разговора — круг корня, и он назван прямо.
	if разговор.Level != levelEveryone {
		t.Fatalf("круг разговора: %d, ожидался круг корня %d", разговор.Level, levelEveryone)
	}
}

func TestКомментарийКомментарияОтклонён(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000122")
	reader := registerDevice(t, ts, "+79990000123")

	ch, root := каналСЗаписью(t, ts, owner, levelEveryone)
	var первый struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, reader.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments",
		комментарийТело("первый"), &первый); code != 201 {
		t.Fatalf("первый комментарий: %d", code)
	}
	// Глубина два уровня: ответ на комментарий цепляется к корню и несёт «@имя»
	// в тексте (ADR-0024 §7). Третьего уровня вложения не бывает.
	if code := postAuthed(t, ts, reader.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(первый.PostID)+"/comments",
		комментарийТело("ответ на ответ"), nil); code != 404 {
		t.Fatalf("комментарий к комментарию: %d, ожидалось 404", code)
	}
}

func TestКругКомментарияБерётсяУКорня(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000124")
	чужой := registerDevice(t, ts, "+79990000125")
	свой := registerDevice(t, ts, "+79990000126")

	ch, root := каналСЗаписью(t, ts, owner, levelMembers)
	if code := postAuthed(t, ts, свой.token, "POST", "/api/v1/channels/"+ch+"/subscribe", nil, nil); code != 200 {
		t.Fatalf("подписка: %d", code)
	}

	путь := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	// Записи уровня «своим» для постороннего не существует — ни разговора, ни права
	// в него написать. Отказ обязан быть неотличим от отсутствия: иначе он сам
	// сообщает, что запись есть.
	if code := getAuthed(t, ts, чужой.token, путь, nil); code != 404 {
		t.Fatalf("чужой видит разговор под записью уровня 2: %d", code)
	}
	if code := postAuthed(t, ts, чужой.token, "POST", путь, комментарийТело("влезаю"), nil); code != 404 {
		t.Fatalf("чужой пишет под записью уровня 2: %d", code)
	}

	// Подписчику она видна — значит и разговор тоже.
	if code := postAuthed(t, ts, свой.token, "POST", путь, комментарийТело("я подписан"), nil); code != 201 {
		t.Fatalf("подписчик не смог прокомментировать: %d", code)
	}
	var разговор ответРазговора
	if code := getAuthed(t, ts, свой.token, путь, &разговор); code != 200 || len(разговор.Comments) != 1 {
		t.Fatalf("разговор подписчика: %d, %+v", code, разговор.Comments)
	}

	// И сама запись уровня 2 в ленте у чужого не появляется.
	var лента ответЛенты
	if code := getAuthed(t, ts, чужой.token, "/api/v1/channels/"+ch+"/posts", &лента); code != 200 {
		t.Fatalf("лента чужого: %d", code)
	}
	if len(лента.Posts) != 0 {
		t.Fatalf("чужой видит записи уровня 2: %+v", лента.Posts)
	}
}

func TestКомментарийКНесуществующейЗаписи(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000127")
	ch, root := каналСЗаписью(t, ts, owner, levelEveryone)

	if code := postAuthed(t, ts, owner.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root+1000)+"/comments",
		комментарийТело("в пустоту"), nil); code != 404 {
		t.Fatalf("комментарий к несуществующей записи: %d, ожидалось 404", code)
	}
}
