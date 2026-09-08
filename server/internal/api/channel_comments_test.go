package api

import (
	"net/http/httptest"
	"strconv"
	"testing"
)

// uint64s — номер записи в пути. Отдельной функцией, чтобы strconv не мелькал в каждой строке.
func uint64s(v uint64) string { return strconv.FormatUint(v, 10) }

// Комментарии в каналах и feedх (ADR-0024, ПЛАН-КАНАЛОВ К1).
//
// Проверяется ровно то, что план назвал проверяемым: комментарий не приходит в ленте,
// приходит в talkе, комментарий к комментарию отклонён, круг берётся у корня, счётчик
// считается вместе с записями.

// commentBody — тело запроса на комментарий.
func commentBody(text string) map[string]any { return map[string]any{"text": text} }

type feedAnswer struct {
	Posts []struct {
		PostID   uint64 `json:"post_id"`
		Level    int16  `json:"level"`
		Comments int    `json:"comments"`
		Text     string `json:"text"`
	} `json:"posts"`
}

type conversationAnswer struct {
	PostID   uint64 `json:"post_id"`
	Level    int16  `json:"level"`
	Comments []struct {
		PostID       uint64 `json:"post_id"`
		AuthorID     string `json:"author_id"`
		Text         string `json:"text"`
		ParentPostID uint64 `json:"parent_post_id"`
	} `json:"comments"`
}

// channelWithPost — канал владельца и одна запись нужного круга.
func channelWithPost(t *testing.T, ts *httptest.Server, owner *device, level int16) (string, uint64) {
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

	ch, root := channelWithPost(t, ts, owner, levelEveryone)

	// Посторонний комментирует запись уровня «всем»: подписка для этого не нужна —
	// комментирует тот, кто видит (ADR-0024 §8).
	var created struct {
		PostID       uint64 `json:"post_id"`
		ParentPostID uint64 `json:"parent_post_id"`
	}
	if code := postAuthed(t, ts, reader.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments",
		commentBody("а что это"), &created); code != 201 {
		t.Fatalf("комментарий не принят: %d", code)
	}
	if created.ParentPostID != root {
		t.Fatalf("комментарий привязан не к тому корню: %+v", created)
	}

	// В ленте канала его нет — иначе ветка стала бы записью.
	var feed feedAnswer
	if code := getAuthed(t, ts, reader.token, "/api/v1/channels/"+ch+"/posts", &feed); code != 200 {
		t.Fatalf("feed: %d", code)
	}
	if len(feed.Posts) != 1 {
		t.Fatalf("в ленте обязана быть одна запись, а не %d: %+v", len(feed.Posts), feed.Posts)
	}
	if feed.Posts[0].PostID != root {
		t.Fatalf("в ленте не та запись: %+v", feed.Posts[0])
	}
	// Счётчик приходит вместе с записью, а не отдельным запросом.
	if feed.Posts[0].Comments != 1 {
		t.Fatalf("счётчик комментариев: %d, ожидался 1", feed.Posts[0].Comments)
	}

	// А в talkе — есть.
	var talk conversationAnswer
	if code := getAuthed(t, ts, reader.token,
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments", &talk); code != 200 {
		t.Fatalf("talk: %d", code)
	}
	if len(talk.Comments) != 1 || talk.Comments[0].Text != "а что это" {
		t.Fatalf("talk: %+v", talk.Comments)
	}
	if talk.Comments[0].AuthorID != reader.userID {
		t.Fatal("автором комментария обязан быть комментатор, а не владелец канала")
	}
	// Круг talkа — круг корня, и он назван прямо.
	if talk.Level != levelEveryone {
		t.Fatalf("круг talkа: %d, ожидался круг корня %d", talk.Level, levelEveryone)
	}
}

func TestКомментарийКомментарияОтклонён(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000122")
	reader := registerDevice(t, ts, "+79990000123")

	ch, root := channelWithPost(t, ts, owner, levelEveryone)
	var first struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, reader.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root)+"/comments",
		commentBody("first"), &first); code != 201 {
		t.Fatalf("first комментарий: %d", code)
	}
	// Глубина два уровня: ответ на комментарий цепляется к корню и несёт «@имя»
	// в тексте (ADR-0024 §7). Третьего уровня вложения не бывает.
	if code := postAuthed(t, ts, reader.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(first.PostID)+"/comments",
		commentBody("ответ на ответ"), nil); code != 404 {
		t.Fatalf("комментарий к комментарию: %d, ожидалось 404", code)
	}
}

func TestКругКомментарияБерётсяУКорня(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000124")
	outsider := registerDevice(t, ts, "+79990000125")
	mine := registerDevice(t, ts, "+79990000126")

	ch, root := channelWithPost(t, ts, owner, levelMembers)
	if code := postAuthed(t, ts, mine.token, "POST", "/api/v1/channels/"+ch+"/subscribe", nil, nil); code != 200 {
		t.Fatalf("подписка: %d", code)
	}

	path := "/api/v1/channels/" + ch + "/posts/" + uint64s(root) + "/comments"

	// Записи уровня «своим» для постороннего не существует — ни talkа, ни права
	// в него написать. Отказ обязан быть неотличим от отсутствия: иначе он сам
	// сообщает, что запись есть.
	if code := getAuthed(t, ts, outsider.token, path, nil); code != 404 {
		t.Fatalf("outsider видит talk под записью уровня 2: %d", code)
	}
	if code := postAuthed(t, ts, outsider.token, "POST", path, commentBody("влезаю"), nil); code != 404 {
		t.Fatalf("outsider пишет под записью уровня 2: %d", code)
	}

	// Подписчику она видна — значит и talk тоже.
	if code := postAuthed(t, ts, mine.token, "POST", path, commentBody("я подписан"), nil); code != 201 {
		t.Fatalf("подписчик не смог прокомментировать: %d", code)
	}
	var talk conversationAnswer
	if code := getAuthed(t, ts, mine.token, path, &talk); code != 200 || len(talk.Comments) != 1 {
		t.Fatalf("talk подписчика: %d, %+v", code, talk.Comments)
	}

	// И сама запись уровня 2 в ленте у чужого не появляется.
	var feed feedAnswer
	if code := getAuthed(t, ts, outsider.token, "/api/v1/channels/"+ch+"/posts", &feed); code != 200 {
		t.Fatalf("feed чужого: %d", code)
	}
	if len(feed.Posts) != 0 {
		t.Fatalf("outsider видит записи уровня 2: %+v", feed.Posts)
	}
}

func TestКомментарийКНесуществующейЗаписи(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000127")
	ch, root := channelWithPost(t, ts, owner, levelEveryone)

	if code := postAuthed(t, ts, owner.token, "POST",
		"/api/v1/channels/"+ch+"/posts/"+uint64s(root+1000)+"/comments",
		commentBody("в пустоту"), nil); code != 404 {
		t.Fatalf("комментарий к несуществующей записи: %d, ожидалось 404", code)
	}
}
