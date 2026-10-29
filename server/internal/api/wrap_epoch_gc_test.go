package api

import (
	"context"
	"net/http"
	"testing"
)

// Срок обёрток (план КК, КК10; модель Р5 — обёртки личных до доставки): забранная обёртка личного
// сообщения уходит через запас после того, как устройство её забрало; не забранная живёт, сколько бы ни
// прошло; обёртка отключённого устройства уходит сразу. У групп (Р8) — как было: сменённая версия уходит
// через запас, текущая не уходит никогда.
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

	// Личные: три сообщения, все давние — четыре месяца назад.
	sender := registerDevice(t, ts, "+79990068001")
	recipient := registerDevice(t, ts, "+79990068002")
	ids := []uint64{6801, 6802, 6803}
	for i, id := range ids {
		env := sealEnvelope(t, sender, []*device{recipient}, id, []byte("эпоха"))
		resp := post(t, ts, env, sender.token, []string{"eeeeeeee-0000-0000-0000-000000006801", "eeeeeeee-0000-0000-0000-000000006802", "eeeeeeee-0000-0000-0000-000000006803"}[i])
		resp.Body.Close()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("отправка %d: %d", id, resp.StatusCode)
		}
	}
	chat := chatIDFor(sender, []*device{recipient})
	if _, err := db.Exec(ctx, `UPDATE personal_messages SET received_at = now() - interval '4 months' WHERE chat_id = $1`, chat); err != nil {
		t.Fatal(err)
	}
	// Получатель забрал 6801 давно и 6802 только что; 6803 не забирал.
	var taken struct {
		Taken int64 `json:"taken"`
	}
	if code := postAuthed(t, ts, recipient.token, "POST", "/api/v1/chats/"+chat+"/keys/taken", map[string]any{"message_ids": []int64{6801, 6802}}, &taken); code != 200 || taken.Taken != 2 {
		t.Fatalf("отметка «забрано»: %d %+v", code, taken)
	}
	if _, err := db.Exec(ctx, `UPDATE personal_message_keys SET taken_at = now() - interval '40 days'
		WHERE chat_id = $1 AND message_id = 6801`, chat); err != nil {
		t.Fatal(err)
	}
	var untaken struct {
		Count int64 `json:"count"`
	}
	if code := getAuthed(t, ts, recipient.token, "/api/v1/keys/untaken", &untaken); code != 200 || untaken.Count != 1 {
		t.Fatalf("незабранных у получателя: %d %+v, ждали 1", code, untaken)
	}
	if _, err := srv.Store.GCPersonalWrappedKeys(ctx, grace); err != nil {
		t.Fatal(err)
	}
	wrapOf := func(id int) int {
		return count(`SELECT count(*) FROM personal_message_keys WHERE chat_id = $1 AND message_id = $2 AND recipient = $3`, chat, id, recipient.id)
	}
	if wrapOf(6801) != 0 {
		t.Fatal("забранная давно обёртка осталась")
	}
	if wrapOf(6802) != 1 {
		t.Fatal("забранная только что обёртка убрана — запас не выдержан")
	}
	if wrapOf(6803) != 1 {
		t.Fatal("не забранная обёртка убрана — молчавшее устройство не откроет пропущенное")
	}
	// Устройство отключено — его обёртки уходят сразу: забрать их больше некому.
	if _, err := db.Exec(ctx, `UPDATE devices SET revoked_at = now() WHERE device_id = $1`, recipient.id); err != nil {
		t.Fatal(err)
	}
	if _, err := srv.Store.GCPersonalWrappedKeys(ctx, grace); err != nil {
		t.Fatal(err)
	}
	if n := count(`SELECT count(*) FROM personal_message_keys WHERE chat_id = $1 AND recipient = $2`, chat, recipient.id); n != 0 {
		t.Fatalf("обёртки отключённого устройства остались: %d", n)
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
