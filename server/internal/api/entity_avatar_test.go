package api

import (
	"context"
	"testing"

	"tima/server/internal/store"
)

// Аватар сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ5): своё завершённое медиа, ставят владелец и админ.
func TestАватарСущности(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	owner := registerDevice(t, ts, "+79990008701")
	member := registerDevice(t, ts, "+79990008702")
	group := makeGroup(t, ts, owner.token, "public")
	community := makeCommunity(t, ts, owner, "Клуб")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/groups/"+group+"/members", map[string]any{"user_id": member.userID}, nil); code != 201 {
		t.Fatalf("участник: %d", code)
	}
	mine, err := srv.Store.CreateMedia(ctx, store.Media{OwnerID: owner.userID, Mime: "image/jpeg", SizeBytes: 10, ChunkCount: 1}, nil)
	if err != nil {
		t.Fatal(err)
	}
	theirs, err := srv.Store.CreateMedia(ctx, store.Media{OwnerID: member.userID, Mime: "image/jpeg", SizeBytes: 10, ChunkCount: 1}, nil)
	if err != nil {
		t.Fatal(err)
	}
	// Незавершённое — не ставится.
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/groups/"+group+"/avatar", map[string]any{"media_id": mine.MediaID}, nil); code != 403 {
		t.Fatalf("незавершённое: ждали 403, пришло %d", code)
	}
	for _, m := range []struct{ id, who string }{{mine.MediaID, owner.userID}, {theirs.MediaID, member.userID}} {
		if err := srv.Store.CompleteMedia(ctx, m.id, m.who, 10); err != nil {
			t.Fatal(err)
		}
	}
	// Участник без роли — не ставит; чужое медиа — не ставится.
	if code := postAuthed(t, ts, member.token, "PUT", "/api/v1/groups/"+group+"/avatar", map[string]any{"media_id": theirs.MediaID}, nil); code != 403 {
		t.Fatalf("участник ставит: ждали 403, пришло %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/groups/"+group+"/avatar", map[string]any{"media_id": theirs.MediaID}, nil); code != 403 {
		t.Fatalf("чужое медиа: ждали 403, пришло %d", code)
	}
	// Своё — и группе, и сообществу.
	for _, path := range []string{"/api/v1/groups/" + group, "/api/v1/communities/" + community} {
		if code := postAuthed(t, ts, owner.token, "PUT", path+"/avatar", map[string]any{"media_id": mine.MediaID}, nil); code != 200 {
			t.Fatalf("%s: %d", path, code)
		}
	}
	var got struct {
		Avatars map[string]string `json:"avatars"`
	}
	if code := postAuthed(t, ts, member.token, "POST", "/api/v1/entities/avatars", map[string]any{"kind": "group", "ids": []string{group}}, &got); code != 200 || got.Avatars[group] != mine.MediaID {
		t.Fatalf("аватары: %d %+v", code, got)
	}
	// Снять.
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/groups/"+group+"/avatar", map[string]any{"media_id": ""}, nil); code != 200 {
		t.Fatalf("снять: %d", code)
	}
	got.Avatars = nil
	if code := postAuthed(t, ts, member.token, "POST", "/api/v1/entities/avatars", map[string]any{"kind": "group", "ids": []string{group}}, &got); code != 200 || got.Avatars[group] != "" {
		t.Fatalf("после снятия: %d %+v", code, got)
	}
}

// Число участников и подписчиков (ПЛАН-(СЦ) §4б п. 9): группа — не вышедшие участники, сообщество — подписчики.
func TestЧислоУчастниковСущности(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008711")
	member := registerDevice(t, ts, "+79990008712")
	group := makeGroup(t, ts, owner.token, "public")
	community := makeCommunity(t, ts, owner, "Счёт")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/groups/"+group+"/members", map[string]any{"user_id": member.userID}, nil); code != 201 {
		t.Fatalf("участник: %d", code)
	}
	var got struct {
		Counts map[string]int `json:"counts"`
	}
	if code := postAuthed(t, ts, member.token, "POST", "/api/v1/entities/counts", map[string]any{"kind": "group", "ids": []string{group}}, &got); code != 200 || got.Counts[group] != 2 {
		t.Fatalf("группа: %d %+v", code, got)
	}
	got.Counts = nil
	if code := postAuthed(t, ts, member.token, "POST", "/api/v1/entities/counts", map[string]any{"kind": "community", "ids": []string{community}}, &got); code != 200 || got.Counts[community] != 1 {
		t.Fatalf("сообщество: %d %+v", code, got)
	}
	if code := postAuthed(t, ts, member.token, "POST", "/api/v1/entities/counts", map[string]any{"kind": "x", "ids": []string{group}}, nil); code != 400 {
		t.Fatalf("вид: ждали 400, пришло %d", code)
	}
}

// Журнал ролей сообщества (ПЛАН-(СЦ) СЦ6): владелец назначает и снимает админа — две записи; не владельцу — 403.
func TestЖурналРолейСообщества(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008721")
	other := registerDevice(t, ts, "+79990008722")
	community := makeCommunity(t, ts, owner, "Журнал")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/admins", map[string]any{"user_id": other.userID}, nil); code != 201 {
		t.Fatalf("назначить админа: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "DELETE", "/api/v1/communities/"+community+"/admins/"+other.userID, nil, nil); code != 204 {
		t.Fatalf("снять админа: %d", code)
	}
	if code := postAuthed(t, ts, other.token, "GET", "/api/v1/communities/"+community+"/role-log", nil, nil); code != 403 {
		t.Fatalf("журнал не владельцу: ждали 403, пришло %d", code)
	}
	var got struct {
		Entries []struct {
			TargetID string `json:"target_id"`
			Action   string `json:"action"`
			Detail   string `json:"detail"`
		} `json:"entries"`
	}
	if code := postAuthed(t, ts, owner.token, "GET", "/api/v1/communities/"+community+"/role-log", nil, &got); code != 200 ||
		len(got.Entries) != 2 || got.Entries[0].Detail != "member" || got.Entries[1].Detail != "admin" || got.Entries[1].TargetID != other.userID {
		t.Fatalf("журнал: %d %+v", code, got)
	}
}
