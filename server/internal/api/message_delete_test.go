package api

// Удаление у всех (Решения §8): личное — только автор, в группе — ещё модератор и админ; правки
// скрываются вместе с исходным; получившие узнают событием `message.deleted`.

import (
	"fmt"
	"net/http"
	"testing"
)

func TestDeletePersonalMessage(t *testing.T) {
	ts, srv := setup(t)
	a := registerDevice(t, ts, "+79995580101")
	b := registerDevice(t, ts, "+79995580102")
	n := 0
	cid := func() string { n++; return fmt.Sprintf("dddddddd-1111-0000-0000-%012d", n) }
	if code, _ := editReply(t, post(t, ts, sealEnvelope(t, a, []*device{b}, 1, []byte("исходное")), a.token, cid())); code != http.StatusCreated {
		t.Fatalf("исходное: %d", code)
	}
	if code, _ := editReply(t, post(t, ts, asEdit(t, sealEnvelope(t, a, []*device{b}, 2, []byte("правка")), a, 1, 1), a.token, cid())); code != http.StatusCreated {
		t.Fatalf("правка: %d", code)
	}
	chat := chatIDFor(a, []*device{b})
	path := "/api/v1/chats/" + chat + "/messages/1"

	if code := authedJSON(t, ts, "DELETE", path, b.token, nil, nil); code != http.StatusForbidden {
		t.Fatalf("чужое в личной: ожидался 403, получен %d", code)
	}
	if code := authedJSON(t, ts, "DELETE", path, a.token, nil, nil); code != http.StatusOK {
		t.Fatalf("удаление автором: %d", code)
	}
	if code := authedJSON(t, ts, "DELETE", path, a.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("повторное удаление: ожидался 404, получен %d", code)
	}
	var hist struct {
		Messages []struct {
			MessageID uint64 `json:"message_id"`
		} `json:"messages"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/chats/"+chat+"/messages", b.token, nil, &hist); code != http.StatusOK {
		t.Fatalf("история: %d", code)
	}
	if len(hist.Messages) != 0 {
		t.Fatalf("после удаления в истории %d сообщений — правка обязана уйти с исходным", len(hist.Messages))
	}
	if got := eventsOf(t, srv, b.id, "message.deleted"); got != 1 {
		t.Fatalf("событий «удалено» у получателя %d, ожидалось 1", got)
	}
}

func TestDeleteGroupMessage(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79995580201")
	member := registerDevice(t, ts, "+79995580202")
	other := registerDevice(t, ts, "+79995580203")
	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	addMemberAPI(t, ts, owner.token, groupID, other.userID, "member")
	gk, code := doRotate(t, ts, owner.token, groupID, 1, "periodic", []*device{owner, member, other})
	if code != http.StatusCreated {
		t.Fatalf("ротация: %d", code)
	}
	send := func(id string) int64 {
		_, resp := sendGroupMessage(t, ts, member, groupID, groupMsg{ClientMsgID: id, Kind: 1, GKVersion: 1, Key: &gk, Payload: sealGK(t, gk, []byte("x"))}, false)
		var n int64
		if raw, ok := resp["message_id"]; ok {
			_, _ = fmt.Sscan(string(raw), &n)
		}
		return n
	}
	first, second := send("dddddddd-2222-0000-0000-000000000001"), send("dddddddd-2222-0000-0000-000000000002")
	del := func(dev *device, n int64) int {
		return authedJSON(t, ts, "DELETE", fmt.Sprintf("/api/v1/groups/%s/messages/%d", groupID, n), dev.token, nil, nil)
	}
	if code := del(other, first); code != http.StatusForbidden {
		t.Fatalf("чужое участником: ожидался 403, получен %d", code)
	}
	if code := del(member, first); code != http.StatusOK {
		t.Fatalf("своё: %d", code)
	}
	if code := del(owner, second); code != http.StatusOK {
		t.Fatalf("чужое владельцем: %d", code)
	}
	if left := fetchGroupMessages(t, ts, owner.token, groupID, ""); len(left) != 0 {
		t.Fatalf("после удаления в истории %d сообщений", len(left))
	}
}
