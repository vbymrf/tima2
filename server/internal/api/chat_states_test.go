package api

// Архив чата у каждого свой, срок жизни без номера и выбор главной личности (Р4).

import (
	"context"
	"testing"
	"time"

	"tima/server/internal/store"
)

// Уборка одного участника не назначает удаление переписки другому.
func TestChatArchiveIsPersonal(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	a := registerDevice(t, ts, "+79990070001")
	b := registerDevice(t, ts, "+79990070002")

	// Переписка, чтобы у чата появились участники.
	env := sealEnvelope(t, a, []*device{a, b}, 910001, []byte("привет"))
	if resp := post(t, ts, env, a.token, "dddddddd-0000-0000-0000-000000000001"); resp.StatusCode != 201 {
		defer resp.Body.Close()
		t.Fatalf("отправка: %d", resp.StatusCode)
	}

	if err := srv.Store.SetChatArchived(ctx, chatID, a.userID, true); err != nil {
		t.Fatal(err)
	}
	got, err := srv.Store.IsChatArchivedFor(ctx, chatID, a.userID)
	if err != nil || !got {
		t.Fatalf("у убравшего чат не в архиве: %v %v", got, err)
	}
	got, err = srv.Store.IsChatArchivedFor(ctx, chatID, b.userID)
	if err != nil || got {
		t.Fatalf("чат оказался в архиве у ВТОРОГО участника, который его не убирал: %v %v", got, err)
	}

	// Убрал только один — чат к удалению не готов.
	ready, err := srv.Store.ChatsArchivedByEveryone(ctx, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range ready {
		if id == chatID {
			t.Fatal("чат помечен к удалению, хотя второй участник его не убирал")
		}
	}

	// Убрали оба — теперь готов.
	if err := srv.Store.SetChatArchived(ctx, chatID, b.userID, true); err != nil {
		t.Fatal(err)
	}
	ready, err = srv.Store.ChatsArchivedByEveryone(ctx, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, id := range ready {
		if id == chatID {
			found = true
		}
	}
	if !found {
		t.Fatal("чат убрали все, а к удалению он не готов")
	}
}

// Возврат из архива снимает готовность к удалению.
func TestChatUnarchive(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	a := registerDevice(t, ts, "+79990071001")
	b := registerDevice(t, ts, "+79990071002")
	env := sealEnvelope(t, a, []*device{a, b}, 910002, []byte("привет"))
	post(t, ts, env, a.token, "dddddddd-0000-0000-0000-000000000002").Body.Close()

	for _, u := range []string{a.userID, b.userID} {
		if err := srv.Store.SetChatArchived(ctx, chatID, u, true); err != nil {
			t.Fatal(err)
		}
	}
	if err := srv.Store.SetChatArchived(ctx, chatID, a.userID, false); err != nil {
		t.Fatal(err)
	}
	ready, _ := srv.Store.ChatsArchivedByEveryone(ctx, 0, 10)
	for _, id := range ready {
		if id == chatID {
			t.Fatal("чат вернули из архива, а он остался помечен к удалению")
		}
	}
}

// Аккаунт без номера и почты живёт ограниченное время.
func TestCredentialsWindow(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	d := registerDevice(t, ts, "+79990072001")
	person, err := srv.Store.PersonOfUser(ctx, d.userID)
	if err != nil {
		t.Fatal(err)
	}

	// Данные есть — срок не назначен, вход свободен.
	if err := srv.Store.CheckCredentialsWindow(ctx, person, time.Now()); err != nil {
		t.Fatalf("аккаунт с номером не пускает: %v", err)
	}

	// Номер забрал новый владелец: аккаунт и переписка остаются, номер — нет.
	if err := srv.Store.TakePhoneFromPerson(ctx, person, 90); err != nil {
		t.Fatal(err)
	}
	// В пределах срока входить ещё можно — иначе человек оказался бы заперт мгновенно.
	if err := srv.Store.CheckCredentialsWindow(ctx, person, time.Now()); err != nil {
		t.Fatalf("сразу после потери номера вход закрыт: %v", err)
	}
	// После срока — только привязав данные.
	if err := srv.Store.CheckCredentialsWindow(ctx, person, time.Now().Add(91*24*time.Hour)); err == nil {
		t.Fatal("срок вышел, а вход без номера и почты всё ещё разрешён")
	}

	// Привязали данные — срок снят.
	if err := srv.Store.ClearCredentialsWindow(ctx, person); err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.CheckCredentialsWindow(ctx, person, time.Now().Add(365*24*time.Hour)); err != nil {
		t.Fatalf("после привязки данных вход закрыт: %v", err)
	}
}

// Номер освободился — регистрируется новый владелец и получает СВОЙ аккаунт.
func TestPhoneGoesToNewOwner(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	old := registerDevice(t, ts, "+79990073001")
	oldPerson, err := srv.Store.PersonOfUser(ctx, old.userID)
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.TakePhoneFromPerson(ctx, oldPerson, 90); err != nil {
		t.Fatal(err)
	}

	fresh := registerDevice(t, ts, "+79990073001")
	newPerson, err := srv.Store.PersonOfUser(ctx, fresh.userID)
	if err != nil {
		t.Fatal(err)
	}
	if newPerson == oldPerson {
		t.Fatal("новый владелец номера попал в чужой аккаунт")
	}
}

// Главной остаётся личность с более ДЛИННОЙ перепиской, а не просто более ранняя.
func TestHeadIdentityByHistoryLength(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	a := registerDevice(t, ts, "+79990074001")
	b := registerDevice(t, ts, "+79990074002")

	person, err := srv.Store.PersonOfUser(ctx, a.userID)
	if err != nil {
		t.Fatal(err)
	}
	// Вторая личность того же аккаунта, заведена ПОЗЖЕ.
	second, err := srv.Store.StartNewIdentity(ctx, person, a.userID, []byte("proof"))
	if err != nil {
		t.Fatal(err)
	}

	// Ни под одной не писали — берём заведённую раньше.
	head, err := srv.Store.ChooseHeadIdentity(ctx, a.userID, second)
	if err != nil {
		t.Fatal(err)
	}
	if head != a.userID {
		t.Fatalf("без переписки главной стала %s, ожидали более раннюю %s", head, a.userID)
	}

	// Теперь под ПОЗДНЕЙ личностью появляется переписка, начавшаяся раньше, чем
	// что-либо у первой (первая вообще не писала). Побеждает она.
	env := sealEnvelope(t, a, []*device{a, b}, 910003, []byte("привет"))
	env.Meta.SenderId = second
	// Подпись пересобирать не нужно: сюда пишем напрямую в хранилище.
	if err := srv.Store.SaveMessage(ctx, store.Message{
		ChatID: chatID, MessageID: 910003, ClientMsgID: "dddddddd-0000-0000-0000-000000000003",
		SenderID: second, SenderDevice: a.id, Kind: 1, CreatedAtUnixMs: 1_700_000_000_000,
		FormatVersion: 2, EncryptedPayload: env.EncryptedPayload,
		EscrowMlkemCt: env.Escrow.MlkemCt, EscrowWrappedKey: env.Escrow.WrappedMessageKey,
		EscrowKeyVersion: 1, SenderEphemeralPub: env.SenderEphemeralPub,
		Signature: env.Signature, KeyCommitment: env.KeyCommitment,
		WrappedKeys: map[string][]byte{a.id: []byte("x")},
	}); err != nil {
		t.Fatal(err)
	}
	head, err = srv.Store.ChooseHeadIdentity(ctx, a.userID, second)
	if err != nil {
		t.Fatal(err)
	}
	if head != second {
		t.Fatalf("главной стала %s, а переписка длиннее у %s", head, second)
	}
}
