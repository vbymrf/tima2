package api

import (
	"testing"
)

// Страна и язык (ПЛАН-ЯЗЫКА Я5, Я6).
//
// Проверяется ровно то, ради чего они заведены: запись получает штамп из профиля автора, а
// каталог отбирается по паре «страна и язык». Ни перевода, ни определения языка по тексту
// здесь нет и не проверяется — их не делают вовсе.

func TestШтампСтраныСтавитсяПриПубликацииИзПрофиля(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000220")
	reader := registerDevice(t, ts, "+79990000221")

	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/users/me/locale",
		map[string]any{"lang": "ru", "country": "RU"}, nil); code != 200 {
		t.Fatalf("настройка страны: %d", code)
	}
	ch, _ := channelWithPost(t, ts, owner, levelEveryone)

	var feed feedAnswer
	if code := getAuthed(t, ts, reader.token, "/api/v1/channels/"+ch+"/posts", &feed); code != 200 {
		t.Fatalf("лента канала: %d", code)
	}
	if len(feed.Posts) != 1 {
		t.Fatalf("записей: %d", len(feed.Posts))
	}
	if feed.Posts[0].Lang != "ru" || feed.Posts[0].Country != "RU" {
		t.Fatalf("штамп не поставлен: %+v", feed.Posts[0])
	}
}

func TestПереездАвтораНеПереписываетПрошлое(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000222")

	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/users/me/locale",
		map[string]any{"lang": "ru", "country": "RU"}, nil); code != 200 {
		t.Fatalf("настройка: %d", code)
	}
	ch, _ := channelWithPost(t, ts, owner, levelEveryone)

	// Автор переехал: новая страна действует на новые записи и не трогает старые.
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/users/me/locale",
		map[string]any{"lang": "es", "country": "ES"}, nil); code != 200 {
		t.Fatalf("переезд: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+ch+"/posts",
		map[string]any{"text": "уже оттуда", "level": levelEveryone}, nil); code != 201 {
		t.Fatalf("вторая запись: %d", code)
	}

	var feed feedAnswer
	if code := getAuthed(t, ts, owner.token, "/api/v1/channels/"+ch+"/posts", &feed); code != 200 {
		t.Fatalf("лента: %d", code)
	}
	if len(feed.Posts) != 2 {
		t.Fatalf("записей: %d", len(feed.Posts))
	}
	// Лента идёт новыми сверху: первой — та, что написана после переезда.
	if feed.Posts[0].Country != "ES" || feed.Posts[1].Country != "RU" {
		t.Fatalf("переезд переписал прошлое: %+v", feed.Posts)
	}
}

func TestКаталогОтбираетсяПоСтранеИЯзыку(t *testing.T) {
	ts, _ := setup(t)
	russian := registerDevice(t, ts, "+79990000223")
	spanish := registerDevice(t, ts, "+79990000224")
	reader := registerDevice(t, ts, "+79990000225")

	if code := postAuthed(t, ts, russian.token, "PUT", "/api/v1/users/me/locale",
		map[string]any{"lang": "ru", "country": "RU"}, nil); code != 200 {
		t.Fatalf("настройка первого: %d", code)
	}
	if code := postAuthed(t, ts, spanish.token, "PUT", "/api/v1/users/me/locale",
		map[string]any{"lang": "es", "country": "ES"}, nil); code != 200 {
		t.Fatalf("настройка второго: %d", code)
	}
	ruChannel, _ := channelWithPost(t, ts, russian, levelEveryone)
	esChannel, _ := channelWithPost(t, ts, spanish, levelEveryone)

	catalogue := func(query string) []string {
		t.Helper()
		var answer struct {
			Channels []struct {
				ChannelID string `json:"channel_id"`
			} `json:"channels"`
		}
		if code := getAuthed(t, ts, reader.token, "/api/v1/channels/discover"+query, &answer); code != 200 {
			t.Fatalf("каталог%s: %d", query, code)
		}
		out := make([]string, 0, len(answer.Channels))
		for _, c := range answer.Channels {
			out = append(out, c.ChannelID)
		}
		return out
	}

	// Без отбора видны оба: незаполненная настройка — это молчание, а молчание не должно
	// закрывать человеку всё сразу.
	all := catalogue("")
	if !contains(all, ruChannel) || !contains(all, esChannel) {
		t.Fatalf("без отбора видны не все: %v", all)
	}

	only := catalogue("?country=RU")
	if !contains(only, ruChannel) || contains(only, esChannel) {
		t.Fatalf("отбор по стране: %v", only)
	}

	byLang := catalogue("?langs=es")
	if contains(byLang, ruChannel) || !contains(byLang, esChannel) {
		t.Fatalf("отбор по языку: %v", byLang)
	}
}

func contains(list []string, what string) bool {
	for _, item := range list {
		if item == what {
			return true
		}
	}
	return false
}
