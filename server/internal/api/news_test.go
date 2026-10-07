package api

import (
	"net/http"
	"testing"

	"tima/server/internal/store"
)

// Новое не считает своё: копия собственного сообщения с другого устройства — не новость.
func TestНовоеБезСвоих(t *testing.T) {
	events := []store.DeviceEvent{
		{EventType: "message.new", Payload: []byte(`{"chat_id":"c","sender_id":"anna"}`)},
		{EventType: "message.new", Payload: []byte(`{"chat_id":"c","sender_id":"me"}`)},
		{EventType: "message.group", Payload: []byte(`{"group_id":"g","sender_id":"oleg"}`)},
		{EventType: "message.group", Payload: []byte(`{"group_id":"g","sender_id":"me"}`)},
		// Записанное до 2026-10-07, без отправителя, — считается: потерять новое хуже.
		{EventType: "message.new", Payload: []byte(`{"chat_id":"c"}`)},
		// Не сообщения — не считаются.
		{EventType: "key.rotated", Payload: []byte(`{}`)},
		{EventType: "store.changed", Payload: []byte(`{}`)},
	}
	if got := countNews(events, "me"); got != 3 {
		t.Fatalf("новых %d, ждали 3", got)
	}
}

// Свежий аккаунт: ручка есть, нового нет.
func TestНовоеУСвежегоАккаунтаНоль(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000061")
	var ответ struct {
		Messages int  `json:"messages"`
		More     bool `json:"more"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me/news", пётр.token, nil, &ответ); code != http.StatusOK {
		t.Fatalf("новое: %d", code)
	}
	if ответ.Messages != 0 || ответ.More {
		t.Fatalf("у свежего аккаунта новое: %+v", ответ)
	}
}
