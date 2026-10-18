package api

// Поиск по открытым уровням (Решения §12): открытое сообщение группы находится по первому узлу
// текста, зашифрованное — нет; запись канала — по тексту.

import (
	"net/http"
	"net/url"
	"testing"

	"github.com/klauspost/compress/zstd"
	"google.golang.org/protobuf/proto"

	pb "tima/server/internal/proto"
)

// openBody — тело открытого сообщения так, как его кладёт клиент: zstd(protobuf(MessageBody)).
func openBody(t *testing.T, nodes ...string) []byte {
	t.Helper()
	raw, err := proto.Marshal(&pb.MessageBody{Nodes: nodes})
	if err != nil {
		t.Fatal(err)
	}
	enc, _ := zstd.NewWriter(nil)
	return enc.EncodeAll(raw, nil)
}

type searchReply struct {
	Hits []struct {
		MessageID int64  `json:"message_id"`
		Text      string `json:"text"`
	} `json:"hits"`
}

func TestSearchOpenGroup(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79991110401")
	groupID := makeGroup(t, ts, owner.token, "public")
	if code, _ := sendLeveled(t, ts, owner, groupID, "eeeeeeee-5555-0000-0000-000000000001", 1, openBody(t, "", "Сбор в 9:00 у входа")); code != http.StatusCreated {
		t.Fatalf("открытое: %d", code)
	}
	if code, _ := sendLeveled(t, ts, owner, groupID, "eeeeeeee-5555-0000-0000-000000000002", 1, openBody(t, "другое")); code != http.StatusCreated {
		t.Fatalf("второе: %d", code)
	}
	var got searchReply
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/search?q="+url.QueryEscape("сбор"), owner.token, nil, &got); code != http.StatusOK {
		t.Fatalf("поиск: %d", code)
	}
	if len(got.Hits) != 1 || got.Hits[0].Text != "Сбор в 9:00 у входа" {
		t.Fatalf("найдено: %+v", got.Hits)
	}
	// Знак шаблона в запросе — буква, а не «всё подряд».
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/search?q="+url.QueryEscape("%%"), owner.token, nil, &got); code != http.StatusOK || len(got.Hits) != 0 {
		t.Fatalf("«%%%%» нашло %d", len(got.Hits))
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/search?q=a", owner.token, nil, nil); code != http.StatusBadRequest {
		t.Fatalf("короткий запрос: %d", code)
	}
}

func TestSearchChannel(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79991110402")
	var created struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]string{"title": "Поиск"}, &created); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels/"+created.ChannelID+"/posts", map[string]any{"text": "Расписание на неделю", "level": 1}, nil); code != 201 {
		t.Fatalf("запись: %d", code)
	}
	var got searchReply
	if code := authedJSON(t, ts, "GET", "/api/v1/channels/"+created.ChannelID+"/search?q="+url.QueryEscape("неделю"), owner.token, nil, &got); code != http.StatusOK || len(got.Hits) != 1 {
		t.Fatalf("поиск в канале: %d, %+v", code, got.Hits)
	}
}
