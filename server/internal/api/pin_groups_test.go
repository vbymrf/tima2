package api

import (
	"encoding/json"
	"fmt"
	"testing"
)

// Закреплённые группой (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ12): предел 20, дубликат, порядок, права, прежние запросы
// поверх списка, название, снятие части и всех.

type pinsAnswer struct {
	Title string `json:"title"`
	Items []struct {
		MessageID int64 `json:"message_id"`
	} `json:"items"`
	Max     int     `json:"max"`
	Already []int64 `json:"already"`
	Removed int64   `json:"removed"`
}

func TestЗакреплённыеГруппой(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990008601")
	member := registerDevice(t, ts, "+79990008602")
	group := makeGroup(t, ts, owner.token, "public")
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/groups/"+group+"/members", map[string]any{"user_id": member.userID}, nil); code != 201 {
		t.Fatalf("участник: %d", code)
	}
	ids := make([]int64, 0, 22)
	for i := 0; i < 22; i++ {
		code, body := sendGroupMessage(t, ts, owner, group, groupMsg{
			ClientMsgID: fmt.Sprintf("22222222-0000-0000-0000-%012d", i),
			Payload:     []byte(fmt.Sprintf("сообщение %d", i)),
		}, false)
		if code != 201 {
			t.Fatalf("сообщение %d: %d", i, code)
		}
		var id int64
		if err := json.Unmarshal(body["message_id"], &id); err != nil || id == 0 {
			t.Fatalf("номер сообщения %d: %v", i, err)
		}
		ids = append(ids, id)
	}
	base := "/api/v1/groups/" + group + "/pins"

	// Участник без роли — не закрепляет.
	if code := postAuthed(t, ts, member.token, "POST", base, map[string]any{"message_ids": []int64{ids[0]}}, nil); code != 403 {
		t.Fatalf("закрепляет участник: ждали 403, пришло %d", code)
	}
	// Прежний «…/pin» — первым пунктом, второй — добавляет (одно становится группой).
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/groups/"+group+"/pin", map[string]any{"message_id": ids[0]}, nil); code != 200 {
		t.Fatalf("прежний pin: %d", code)
	}
	var ans pinsAnswer
	if code := postAuthed(t, ts, owner.token, "POST", base, map[string]any{"message_ids": []int64{ids[1], ids[0]}}, &ans); code != 200 {
		t.Fatalf("добавить: %d", code)
	}
	if len(ans.Items) != 2 || ans.Items[0].MessageID != ids[1] || len(ans.Already) != 1 || ans.Already[0] != ids[0] || ans.Max != 20 {
		t.Fatalf("порядок или дубликат: %+v", ans)
	}
	// Прежний GET отдаёт новейшее.
	var old struct {
		MessageID int64 `json:"message_id"`
	}
	if code := getAuthed(t, ts, member.token, "/api/v1/groups/"+group+"/pin", &old); code != 200 || old.MessageID != ids[1] {
		t.Fatalf("прежний GET поверх списка: %d %+v", code, old)
	}
	// До 20; 21-е — 409 pins_full, и ничего не меняется.
	if code := postAuthed(t, ts, owner.token, "POST", base, map[string]any{"message_ids": ids[2:20]}, &ans); code != 200 || len(ans.Items) != 20 {
		t.Fatalf("до двадцати: %d, %d шт.", code, len(ans.Items))
	}
	if code := postAuthed(t, ts, owner.token, "POST", base, map[string]any{"message_ids": []int64{ids[20]}}, nil); code != 409 {
		t.Fatalf("21-е: ждали 409, пришло %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", "/api/v1/groups/"+group+"/pin", map[string]any{"message_id": ids[21]}, nil); code != 409 {
		t.Fatalf("прежний pin при 20: ждали 409, пришло %d", code)
	}
	// Название — до 64; задаёт тот, у кого право.
	if code := postAuthed(t, ts, member.token, "PUT", base+"/title", map[string]any{"title": "Важное"}, nil); code != 403 {
		t.Fatalf("название участником: ждали 403, пришло %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", base+"/title", map[string]any{"title": "Важное"}, &ans); code != 200 || ans.Title != "Важное" {
		t.Fatalf("название: %d %+v", code, ans.Title)
	}
	// Снять часть; заменить; снять все.
	if code := postAuthed(t, ts, owner.token, "DELETE", base, map[string]any{"message_ids": []int64{ids[0], ids[1]}}, &ans); code != 200 || ans.Removed != 2 || len(ans.Items) != 18 {
		t.Fatalf("снять часть: %d %+v", code, ans.Removed)
	}
	if code := postAuthed(t, ts, owner.token, "POST", base, map[string]any{"message_ids": []int64{ids[21]}, "mode": "replace"}, &ans); code != 200 || len(ans.Items) != 1 || ans.Items[0].MessageID != ids[21] {
		t.Fatalf("заменить: %d %+v", code, ans.Items)
	}
	if code := postAuthed(t, ts, owner.token, "DELETE", base, nil, &ans); code != 200 || len(ans.Items) != 0 {
		t.Fatalf("снять все: %d %+v", code, ans.Items)
	}
}
