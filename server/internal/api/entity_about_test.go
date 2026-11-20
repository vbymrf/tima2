package api

import (
	"strings"
	"testing"
)

// Название и описание сущности (ПЛАН-(ВС) 10–11, заказчик 2026-11-20): правят владелец и админ (у канала — владелец),
// описание открыто всем, может отсутствовать, до 4096 знаков.
func TestEntityAbout(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990088001")
	stranger := registerDevice(t, ts, "+79990088002")

	var group struct {
		GroupID string `json:"group_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/groups", map[string]any{"title": "Дача", "kind": "public"}, &group); code != 201 {
		t.Fatalf("группа: %d", code)
	}
	var ch struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]any{"title": "Новости"}, &ch); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	var cm struct {
		CommunityID string `json:"community_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities", map[string]any{"title": "Район"}, &cm); code != 201 {
		t.Fatalf("сообщество: %d", code)
	}

	for _, e := range []struct{ kind, plural, id string }{{"group", "groups", group.GroupID}, {"channel", "channels", ch.ChannelID}, {"community", "communities", cm.CommunityID}} {
		path := "/api/v1/" + e.plural + "/" + e.id + "/about"
		if code := postAuthed(t, ts, stranger.token, "PUT", path, map[string]any{"about": "чужое"}, nil); code != 403 {
			t.Fatalf("%s: чужой правит описание: %d", e.kind, code)
		}
		if code := postAuthed(t, ts, owner.token, "PUT", path, map[string]any{"title": " Новое ", "about": "  про нас  "}, nil); code != 200 {
			t.Fatalf("%s: владелец не правит: %d", e.kind, code)
		}
		if code := postAuthed(t, ts, owner.token, "PUT", path, map[string]any{"about": strings.Repeat("я", 4097)}, nil); code != 400 {
			t.Fatalf("%s: длиннее 4096 прошло: %d", e.kind, code)
		}
		var got struct {
			About map[string]string `json:"about"`
		}
		// Описание открыто, как название: читает и не участник.
		if code := postAuthed(t, ts, stranger.token, "POST", "/api/v1/entities/about", map[string]any{"kind": e.kind, "ids": []string{e.id}}, &got); code != 200 {
			t.Fatalf("%s: чтение: %d", e.kind, code)
		}
		if got.About[e.id] != "про нас" {
			t.Fatalf("%s: описание %q", e.kind, got.About[e.id])
		}
		// Пустое — описания нет, и в ответе его нет.
		if code := postAuthed(t, ts, owner.token, "PUT", path, map[string]any{"about": ""}, nil); code != 200 {
			t.Fatalf("%s: снять описание: %d", e.kind, code)
		}
		got.About = nil
		postAuthed(t, ts, stranger.token, "POST", "/api/v1/entities/about", map[string]any{"kind": e.kind, "ids": []string{e.id}}, &got)
		if _, has := got.About[e.id]; has {
			t.Fatalf("%s: пустое описание осталось в ответе", e.kind)
		}
	}
}
