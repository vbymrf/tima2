package api

// «Только продолжение» (Решения §11.3): открытая пометка. Сообщение группы возвращает её в выдаче
// — клиент прячет его из ленты переписки; запись канала с пометкой не идёт в ленту канала, в
// поиск и на страницу, а по номеру отдаётся как обычно.

import (
	"crypto/ed25519"
	"encoding/base64"
	"net/http"
	"net/url"
	"strconv"
	"testing"

	timacrypto "tima/server/internal/crypto"
)

func TestContinuationGroupMessage(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79991110501")
	groupID := makeGroup(t, ts, owner.token, "public")
	const createdAt = 1_750_000_000_000
	payload := openBody(t, "Закрытая часть: адрес сбора")
	cb := timacrypto.GroupMessageCanonicalBytes(timacrypto.GroupMessageMeta{
		GroupID: groupID, SenderID: owner.userID, SenderDevice: owner.id, CreatedAtUnixMs: createdAt,
	}, payload)
	b64 := base64.RawURLEncoding
	body := map[string]any{
		"client_msg_id":      "eeeeeeee-7777-0000-0000-000000000001",
		"level":              2,
		"payload":            b64.EncodeToString(payload),
		"created_at_unix_ms": createdAt,
		"signature":          b64.EncodeToString(ed25519.Sign(owner.signKey, cb)),
		"only_continuation":  true,
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/messages", owner.token, body, nil); code != http.StatusCreated {
		t.Fatalf("продолжение: %d", code)
	}
	if code, _ := sendLeveled(t, ts, owner, groupID, "eeeeeeee-7777-0000-0000-000000000002", 1, openBody(t, "Открытое")); code != http.StatusCreated {
		t.Fatalf("обычное: %d", code)
	}
	var list struct {
		Messages []struct {
			OnlyContinuation bool `json:"only_continuation"`
			Level            int16 `json:"level"`
		} `json:"messages"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/messages", owner.token, nil, &list); code != http.StatusOK || len(list.Messages) != 2 {
		t.Fatalf("история: %d, %d сообщений", code, len(list.Messages))
	}
	// Новые первыми: обычное, затем продолжение.
	if list.Messages[0].OnlyContinuation || !list.Messages[1].OnlyContinuation {
		t.Fatalf("пометка в выдаче: %+v", list.Messages)
	}
	var got searchReply
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/search?q="+url.QueryEscape("адрес"), owner.token, nil, &got); code != http.StatusOK || len(got.Hits) != 0 {
		t.Fatalf("продолжение нашлось поиском: %d, %+v", code, got.Hits)
	}
}

func TestContinuationChannelPost(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79991110502")
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]string{"title": "Продолжения"}, &created); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	var cont struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+created.ChannelID+"/posts",
		map[string]any{"text": "Продолжение для своих", "level": 2, "only_continuation": true}, &cont); code != 201 {
		t.Fatalf("продолжение: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+created.ChannelID+"/posts",
		map[string]any{"text": "Открытая запись", "level": 1}, nil); code != 201 {
		t.Fatalf("запись: %d", code)
	}
	var posts struct {
		Posts []struct {
			Text string `json:"text"`
		} `json:"posts"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/channels/"+created.ChannelID+"/posts", &posts); code != 200 {
		t.Fatalf("лента: %d", code)
	}
	if len(posts.Posts) != 1 || posts.Posts[0].Text != "Открытая запись" {
		t.Fatalf("в ленте: %+v", posts.Posts)
	}
	var got searchReply
	if code := authedJSON(t, ts, "GET", "/api/v1/channels/"+created.ChannelID+"/search?q="+url.QueryEscape("своих"), owner.token, nil, &got); code != http.StatusOK || len(got.Hits) != 0 {
		t.Fatalf("продолжение нашлось поиском: %d, %+v", code, got.Hits)
	}
	// По номеру — как обычно: подкладка и прямой переход до него добираются.
	if code := getAuthed(t, ts, owner.token, "/api/v1/channels/"+created.ChannelID+"/posts/"+strconv.FormatUint(cont.PostID, 10), nil); code != 200 {
		t.Fatalf("по номеру: %d", code)
	}
}
