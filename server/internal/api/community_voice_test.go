package api

import (
	"context"
	"errors"
	"testing"

	"tima/server/internal/store"
)

// Звуковой чат в сообществе (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ7): проверки сервера из плана. Токен LiveKit в тестах
// не выдаётся (комнаты не настроены) — проверяется всё, что до него: вход 503 означает «пустили бы».

func TestЗвуковойЧатСообществаПраваВходаИПредел(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990008501")
	admin := registerDevice(t, ts, "+79990008502")
	a := registerDevice(t, ts, "+79990008503")
	b := registerDevice(t, ts, "+79990008504")
	outsider := registerDevice(t, ts, "+79990008505")
	community := makeCommunity(t, ts, owner, "Клуб")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/communities/"+community+"/admins", map[string]any{"user_id": admin.userID}, nil); code != 200 && code != 201 && code != 204 {
		t.Fatalf("админ: %d", code)
	}
	// Создаёт владелец и админ, не участник.
	if code := postAuthed(t, ts, a.token, "POST", "/api/v1/communities/"+community+"/voice-rooms", map[string]any{"title": "Разговор"}, nil); code != 403 {
		t.Fatalf("создаёт участник: ждали 403, пришло %d", code)
	}
	var room struct {
		RoomID string `json:"room_id"`
	}
	if code := postAuthed(t, ts, admin.token, "POST", "/api/v1/communities/"+community+"/voice-rooms", map[string]any{"title": "Разговор"}, &room); code != 201 {
		t.Fatalf("создаёт админ: %d", code)
	}
	// Секретное сообщество — посторонний не видит и не входит.
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/communities/"+community+"/hidden", map[string]any{"hidden": true}, nil); code != 200 {
		t.Fatalf("секретное: %d", code)
	}
	if code := postAuthed(t, ts, outsider.token, "POST", "/api/v1/voice-rooms/"+room.RoomID+"/enter", nil, nil); code != 404 {
		t.Fatalf("вход не видящего сообщество: ждали 404, пришло %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/communities/"+community+"/hidden", map[string]any{"hidden": false}, nil); code != 200 {
		t.Fatalf("открыть: %d", code)
	}
	// Настройки — только владелец и админ; предел — двое.
	if code := postAuthed(t, ts, a.token, "PUT", "/api/v1/voice-rooms/"+room.RoomID+"/settings", map[string]any{"max_participants": 2}, nil); code != 403 {
		t.Fatalf("настройки участником: ждали 403, пришло %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/voice-rooms/"+room.RoomID+"/settings", map[string]any{"max_participants": 2}, nil); code != 200 {
		t.Fatalf("настройки: %d", code)
	}
	// Без LiveKit вход отвечает 503 до выдачи токена — «пустили бы» (видимость проверена раньше).
	if code := postAuthed(t, ts, a.token, "POST", "/api/v1/voice-rooms/"+room.RoomID+"/enter", nil, nil); code != 503 && code != 200 {
		t.Fatalf("вход видящего: %d", code)
	}
	// Предел и выгон — в хранилище, тем же путём, что вход до токена.
	cv := store.CommunityVoiceOf(srv.Store)
	ctx := context.Background()
	if err := cv.Enter(ctx, room.RoomID, a.userID); err != nil {
		t.Fatalf("вошёл первый: %v", err)
	}
	if err := cv.Enter(ctx, room.RoomID, b.userID); err != nil {
		t.Fatalf("вошёл второй: %v", err)
	}
	if err := cv.Enter(ctx, room.RoomID, a.userID); err != nil {
		t.Fatalf("повторный вход того же — не второй: %v", err)
	}
	if err := cv.Enter(ctx, room.RoomID, admin.userID); !errors.Is(err, store.ErrRoomFull) {
		t.Fatalf("третий при пределе 2: ждали ErrRoomFull, пришло %v", err)
	}
	// Ведущий: участник занимает свободное место, второй — нет; владелец снимает.
	if code := postAuthed(t, ts, a.token, "POST", "/api/v1/voice-rooms/"+room.RoomID+"/host", map[string]any{"user_id": a.userID}, nil); code != 204 {
		t.Fatalf("занять место ведущего: %d", code)
	}
	if code := postAuthed(t, ts, b.token, "POST", "/api/v1/voice-rooms/"+room.RoomID+"/host", map[string]any{"user_id": b.userID}, nil); code != 403 {
		t.Fatalf("занятое место: ждали 403, пришло %d", code)
	}
	// Ведущий без разрешения не выгоняет; владелец — выгоняет.
	if code := postAuthed(t, ts, a.token, "POST", "/api/v1/voice-rooms/"+room.RoomID+"/kick", map[string]any{"user_id": b.userID}, nil); code != 403 {
		t.Fatalf("ведущий выгоняет без разрешения: ждали 403, пришло %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/voice-rooms/"+room.RoomID+"/kick", map[string]any{"user_id": b.userID}, nil); code != 204 {
		t.Fatalf("владелец выгоняет: %d", code)
	}
	if err := cv.Enter(ctx, room.RoomID, b.userID); !errors.Is(err, store.ErrKicked) {
		t.Fatalf("выгнанный входит: ждали ErrKicked, пришло %v", err)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/voice-rooms/"+room.RoomID+"/host", map[string]any{"user_id": ""}, nil); code != 204 {
		t.Fatalf("владелец снимает ведущего: %d", code)
	}
	// Разговор кончился — выгнанный прощён.
	if err := cv.Leave(ctx, room.RoomID, a.userID); err != nil {
		t.Fatal(err)
	}
	if err := cv.Enter(ctx, room.RoomID, b.userID); err != nil {
		t.Fatalf("после опустевшей комнаты: %v", err)
	}
}
