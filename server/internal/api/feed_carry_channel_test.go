package api

import (
	"testing"
)

// Перенос из канала (ПЛАН-КАНАЛОВ К2, ADR-0019 §7).
//
// До этого среза принести к себе можно было только из группы: у поста-ссылки было два
// поля адреса, и оба про группу. Теперь адрес несёт вид контейнера, и проверяется, что
// перенос из канала работает так же — ссылкой, а не копией.

type свояЛента struct {
	Items []struct {
		PostID         uint64   `json:"post_id"`
		Level          int16    `json:"level"`
		AuthorID       string   `json:"author_id"`
		CarriedBy      string   `json:"carried_by"`
		RefKind        string   `json:"ref_kind"`
		RefContainerID string   `json:"ref_container_id"`
		RefGroupID     string   `json:"ref_group_id"`
		RefMessageID   int64    `json:"ref_message_id"`
		SourceTitle    string   `json:"source_title"`
		Nodes          []string `json:"nodes"`
		Comments       int      `json:"comments"`
	} `json:"items"`
}

func TestПереносИзКаналаСсылкойАНеКопией(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000130")
	reader := registerDevice(t, ts, "+79990000131")

	ch, post := каналСЗаписью(t, ts, owner, levelEveryone)

	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/users/me/feed/items",
		map[string]any{"kind": "channel", "container_id": ch, "message_id": post}, nil); code != 201 {
		t.Fatalf("перенос из канала: %d", code)
	}

	var лента свояЛента
	if code := getAuthed(t, ts, reader.token, "/api/v1/users/me/feed", &лента); code != 200 {
		t.Fatalf("своя лента: %d", code)
	}
	if len(лента.Items) != 1 {
		t.Fatalf("на странице обязана быть одна запись: %+v", лента.Items)
	}
	it := лента.Items[0]
	// Ссылка, а не копия: автор остаётся автором, принёсший назван отдельно.
	if it.AuthorID != owner.userID {
		t.Fatalf("автором принесённой записи обязан остаться автор оригинала: %+v", it)
	}
	if it.CarriedBy != reader.userID {
		t.Fatalf("принёсший не назван: %+v", it)
	}
	if it.RefKind != "channel" || it.RefContainerID != ch || it.RefMessageID != int64(post) {
		t.Fatalf("адрес оригинала: %+v", it)
	}
	// Прежнее поле у ссылки на канал пусто — оно про группу.
	if it.RefGroupID != "" {
		t.Fatalf("ref_group_id обязан быть пуст у ссылки на канал: %+v", it)
	}
	if it.SourceTitle != "Ядро" {
		t.Fatalf("не видно, откуда принесено: %+v", it)
	}
	// Содержимое берётся у оригинала, а не хранится в ссылке.
	if len(it.Nodes) != 1 || it.Nodes[0] != "запись" {
		t.Fatalf("содержимое оригинала не раскрылось: %+v", it.Nodes)
	}
}

func TestПовторныйПереносНеПлодитСтрок(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000132")
	reader := registerDevice(t, ts, "+79990000133")
	ch, post := каналСЗаписью(t, ts, owner, levelEveryone)

	тело := map[string]any{"kind": "channel", "container_id": ch, "message_id": post}
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/users/me/feed/items", тело, nil); code != 201 {
		t.Fatalf("первый перенос: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/users/me/feed/items", тело, nil); code != 409 {
		t.Fatalf("повторный перенос: %d, ожидалось 409", code)
	}
	var лента свояЛента
	if code := getAuthed(t, ts, reader.token, "/api/v1/users/me/feed", &лента); code != 200 || len(лента.Items) != 1 {
		t.Fatalf("после повтора на странице %d записей", len(лента.Items))
	}
}

func TestУровень3ИзКаналаНеВыносится(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000134")
	ch, post := каналСЗаписью(t, ts, owner, levelByGrant)

	// Владелец видит свою запись уровня 3 — и всё равно не может её вынести: уровень 3
	// не переносится вовсе (ADR-0019 §7), потому что список названных поимённо ссылкой
	// не передаётся.
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/users/me/feed/items",
		map[string]any{"kind": "channel", "container_id": ch, "message_id": post}, nil); code != 403 {
		t.Fatalf("уровень 3 вынесен: %d, ожидалось 403", code)
	}
}

func TestНеПоказаннуюЗаписьКаналаУнестиНельзя(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990000135")
	чужой := registerDevice(t, ts, "+79990000136")
	ch, post := каналСЗаписью(t, ts, owner, levelMembers)

	// Не подписан — записи уровня «своим» для него не существует. Отказ обязан быть
	// неотличим от отсутствия: иначе перенос становится способом узнать, что запись есть.
	if code := postAuthed(t, ts, чужой.token, "POST", "/api/v1/users/me/feed/items",
		map[string]any{"kind": "channel", "container_id": ch, "message_id": post}, nil); code != 404 {
		t.Fatalf("унесена непоказанная запись: %d, ожидалось 404", code)
	}
}

func TestПереносИзГруппыПрежнимТеломРаботает(t *testing.T) {
	ts, _ := setup(t)
	чужой := registerDevice(t, ts, "+79990000137")

	// Тело без `kind` — прежний вид запроса. Он обязан остаться рабочим: API
	// расширяется, а не меняется (ПРАВИЛА-РАБОТЫ §3). Группы здесь нет, поэтому ответ —
	// «записи нет», а не «плохой запрос»: важно, что запрос разобран как перенос из
	// группы, а не отвергнут на разборе.
	if code := postAuthed(t, ts, чужой.token, "POST", "/api/v1/users/me/feed/items",
		map[string]any{"group_id": "aaaaaaaa-0000-0000-0000-0000000000ff", "message_id": 1}, nil); code != 404 {
		t.Fatalf("прежнее тело переноса: %d, ожидалось 404", code)
	}
}
