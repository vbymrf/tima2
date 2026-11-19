package api

import (
	"context"
	"testing"

	"tima/server/internal/store"
)

// Удаление сущности с месяцем на отмену (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ14).

type deletionAnswer struct {
	Scheduled bool   `json:"scheduled"`
	DeleteAt  string `json:"delete_at"`
}

func TestУдалениеГруппыСМесяцемНаОтмену(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990008301")
	member := registerDevice(t, ts, "+79990008302")
	group := makeGroup(t, ts, owner.token, "public")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/groups/"+group+"/members", map[string]any{"user_id": member.userID}, nil); code != 201 {
		t.Fatalf("участник: %d", code)
	}

	// Не владелец — ни удалить, ни отменить.
	if code := postAuthed(t, ts, member.token, "DELETE", "/api/v1/groups/"+group, nil, nil); code != 403 {
		t.Fatalf("удаление участником: ждали 403, пришло %d", code)
	}
	// Прежний ответ — 204, но группа жива и со сроком.
	if code := postAuthed(t, ts, owner.token, "DELETE", "/api/v1/groups/"+group, nil, nil); code != 204 {
		t.Fatalf("удаление: %d", code)
	}
	var g struct {
		DeleteAt string `json:"delete_at"`
	}
	if code := getAuthed(t, ts, member.token, "/api/v1/groups/"+group, &g); code != 200 || g.DeleteAt == "" {
		t.Fatalf("группа после «удалить» — жива и со сроком: %d %+v", code, g)
	}
	if code := postAuthed(t, ts, member.token, "DELETE", "/api/v1/groups/"+group+"/deletion", nil, nil); code != 403 {
		t.Fatalf("отмена участником: ждали 403, пришло %d", code)
	}
	// Отмена возвращает.
	if code := postAuthed(t, ts, owner.token, "DELETE", "/api/v1/groups/"+group+"/deletion", nil, nil); code != 204 {
		t.Fatalf("отмена: %d", code)
	}
	var st deletionAnswer
	if code := getAuthed(t, ts, owner.token, "/api/v1/groups/"+group+"/deletion", &st); code != 200 || st.Scheduled {
		t.Fatalf("после отмены: %d %+v", code, st)
	}

	// По сроку — удаляется; после срока отменить нельзя.
	if code := postAuthed(t, ts, owner.token, "DELETE", "/api/v1/groups/"+group, nil, nil); code != 204 {
		t.Fatalf("удаление снова: %d", code)
	}
	db := testDB(t)
	if _, err := db.Exec(context.Background(), `UPDATE groups SET delete_at = now() - interval '1 second' WHERE group_id = $1`, group); err != nil {
		t.Fatal(err)
	}
	if code := postAuthed(t, ts, owner.token, "DELETE", "/api/v1/groups/"+group+"/deletion", nil, nil); code != 409 {
		t.Fatalf("отмена после срока: ждали 409, пришло %d", code)
	}
	n, err := store.DeletionsOf(srv.Store).Due(context.Background())
	if err != nil || n < 1 {
		t.Fatalf("работник по сроку: %d %v", n, err)
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/groups/"+group, nil); code != 404 {
		t.Fatalf("после срока группа удалена: ждали 404, пришло %d", code)
	}
}

func TestУдалениеКаналаИСообщества(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008311")
	other := registerDevice(t, ts, "+79990008312")
	var ch struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]any{"title": "К"}, &ch); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	community := makeCommunity(t, ts, owner, "С")
	for _, path := range []string{"/api/v1/channels/" + ch.ChannelID, "/api/v1/communities/" + community} {
		if code := postAuthed(t, ts, other.token, "DELETE", path, nil, nil); code != 403 {
			t.Fatalf("%s чужим: ждали 403, пришло %d", path, code)
		}
		if code := postAuthed(t, ts, owner.token, "DELETE", path, nil, nil); code != 204 {
			t.Fatalf("%s: %d", path, code)
		}
		var st deletionAnswer
		if code := getAuthed(t, ts, owner.token, path+"/deletion", &st); code != 200 || !st.Scheduled || st.DeleteAt == "" {
			t.Fatalf("%s срок: %d %+v", path, code, st)
		}
		if code := postAuthed(t, ts, owner.token, "DELETE", path+"/deletion", nil, nil); code != 204 {
			t.Fatalf("%s отмена: %d", path, code)
		}
	}
}
