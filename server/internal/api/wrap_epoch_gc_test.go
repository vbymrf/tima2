package api

import (
	"context"
	"net/http"
	"testing"
)

// Срок обёрток по эпохе (ПЛАН-(ПС) ПС2, Р3, Р5): обёртка личного сообщения живёт до конца
// месяца сообщения плюс запас; обёртки версии ключа группы уходят, когда их сменила следующая
// версия больше запаса назад, а текущая версия не уходит никогда.
func TestWrapsExpireByEpoch(t *testing.T) {
	ts, srv := setup(t)
	db := testDB(t)
	ctx := context.Background()
	const grace = int64(30 * 24 * 60 * 60)
	count := func(q string, args ...any) int {
		var n int
		if err := db.QueryRow(ctx, q, args...).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}

	// Личные: два сообщения — прошлого месяца и позапрошлого квартала.
	sender := registerDevice(t, ts, "+79990068001")
	recipient := registerDevice(t, ts, "+79990068002")
	for i, id := range []uint64{6801, 6802} {
		env := sealEnvelope(t, sender, []*device{recipient}, id, []byte("эпоха"))
		resp := post(t, ts, env, sender.token, []string{"eeeeeeee-0000-0000-0000-000000006801", "eeeeeeee-0000-0000-0000-000000006802"}[i])
		resp.Body.Close()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("отправка %d: %d", id, resp.StatusCode)
		}
	}
	chat := chatIDFor(sender, []*device{recipient})
	// 6801 — в текущем месяце (не трогаем); 6802 — четыре месяца назад: эпоха и запас кончились.
	if _, err := db.Exec(ctx, `UPDATE personal_messages SET received_at = now() - interval '4 months'
		WHERE chat_id = $1 AND message_id = 6802`, chat); err != nil {
		t.Fatal(err)
	}
	if _, err := srv.Store.GCPersonalWrappedKeys(ctx, grace); err != nil {
		t.Fatal(err)
	}
	if n := count(`SELECT count(*) FROM personal_message_keys WHERE chat_id = $1 AND message_id = 6801`, chat); n == 0 {
		t.Fatal("обёртка сообщения текущей эпохи убрана")
	}
	if n := count(`SELECT count(*) FROM personal_message_keys WHERE chat_id = $1 AND message_id = 6802`, chat); n != 0 {
		t.Fatalf("обёртка сообщения прошедшей эпохи осталась: %d", n)
	}

	// Группы: версия 1 сменена версией 2 давно; версия 2 — текущая, тоже давняя.
	owner := registerDevice(t, ts, "+79990068003")
	member := registerDevice(t, ts, "+79990068004")
	group := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, group, member.userID, "member")
	if _, code := doRotate(t, ts, owner.token, group, 1, "periodic", []*device{owner, member}); code != http.StatusCreated {
		t.Fatalf("ротация v1: %d", code)
	}
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+group+"/members/"+member.userID, owner.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("исключение: %d", code)
	}
	if _, code := doRotate(t, ts, owner.token, group, 2, "member_leave", []*device{owner}); code != http.StatusCreated {
		t.Fatalf("ротация v2: %d", code)
	}
	if _, err := db.Exec(ctx, `UPDATE group_key_history SET rotated_at = now() - interval '3 months' WHERE group_id = $1`, group); err != nil {
		t.Fatal(err)
	}
	if _, err := srv.Store.GCGroupWrappedKeys(ctx, grace); err != nil {
		t.Fatal(err)
	}
	if n := count(`SELECT count(*) FROM group_wrapped_keys WHERE group_id = $1 AND gk_version = 1`, group); n != 0 {
		t.Fatalf("обёртки сменённой версии остались: %d", n)
	}
	if n := count(`SELECT count(*) FROM group_wrapped_keys WHERE group_id = $1 AND gk_version = 2`, group); n == 0 {
		t.Fatal("обёртки текущей версии убраны — тихая группа осталась бы без ключа")
	}
}
