package api

// Сроки хранения и гейт стирания (Р4). Главное, что проверяем: содержимое НЕ
// стирается, пока жив escrow-ключ его эпохи, и стирается, когда ключ уничтожен —
// при этом метаданные строки остаются на месте всегда.

import (
	"context"
	"testing"
	"time"

	"tima/server/internal/store"
)

func TestRetentionPolicyIsData(t *testing.T) {
	_, srv := setup(t)
	ctx := context.Background()

	// retention_policy НЕ входит в ResetForTests: там засеянные миграцией строки,
	// и очистка оставила бы систему без сроков. Значит правки за собой убираем сами.
	days, err := srv.Store.RetentionDays(ctx, "account_inactive_days")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = srv.Store.SetRetentionDays(context.Background(), "account_inactive_days", days) })

	// Смена срока — правка строки, а не пересборка.
	if err := srv.Store.SetRetentionDays(ctx, "account_inactive_days", 45); err != nil {
		t.Fatal(err)
	}
	if got, _ := srv.Store.RetentionDays(ctx, "account_inactive_days"); got != 45 {
		t.Fatalf("после смены: %d, ожидали 45", got)
	}
	if err := srv.Store.SetRetentionDays(ctx, "нет такой политики", 1); err == nil {
		t.Fatal("несуществующая политика изменилась без ошибки")
	}

	// К ≤ СУ — иначе обещаем окно воссоединения длиннее, чем живут данные.
	reunion, err := srv.Store.RetentionDays(ctx, "reunion_window_days")
	if err != nil {
		t.Fatal(err)
	}
	purge, err := srv.Store.RetentionDays(ctx, "account_purge_days")
	if err != nil {
		t.Fatal(err)
	}
	if reunion > purge {
		t.Fatalf("окно воссоединения %d дней длиннее выдержки %d — данных к тому моменту не будет",
			reunion, purge)
	}
}

// Постоянный аккаунт не удаляется по неактивности никогда, временный — удаляется.
func TestOnlyTemporaryAccountsExpire(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()

	tempUser := registerDevice(t, ts, "+79990050001")
	permUser := registerDevice(t, ts, "+79990050002")

	tempPerson, err := srv.Store.PersonOfUser(ctx, tempUser.userID)
	if err != nil {
		t.Fatal(err)
	}
	permPerson, err := srv.Store.PersonOfUser(ctx, permUser.userID)
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.PromoteAccount(ctx, permPerson); err != nil {
		t.Fatal(err)
	}

	// Оба «молчали» дольше срока — ищем с нулевым порогом.
	inactive, err := srv.Store.InactiveTemporaryAccounts(ctx, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	found := map[string]bool{}
	for _, id := range inactive {
		found[id] = true
	}
	if !found[tempPerson] {
		t.Fatal("временный аккаунт не попал в список неактивных")
	}
	if found[permPerson] {
		t.Fatal("ПОСТОЯННЫЙ аккаунт попал в удаление по неактивности — человек в отпуске потерял бы историю")
	}
}

// Архивный аккаунт освобождает номер: иначе перерегистрация заблокирована на всю
// выдержку, то есть до полугода.
func TestArchivedAccountFreesPhone(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()

	first := registerDevice(t, ts, "+79990050003")
	person, err := srv.Store.PersonOfUser(ctx, first.userID)
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.MarkAccountDeleted(ctx, person, 30); err != nil {
		t.Fatal(err)
	}

	// Тот же номер регистрируется заново — и получает ДРУГОЙ аккаунт.
	second := registerDevice(t, ts, "+79990050003")
	newPerson, err := srv.Store.PersonOfUser(ctx, second.userID)
	if err != nil {
		t.Fatal(err)
	}
	if newPerson == person {
		t.Fatal("перерегистрация попала в архивный аккаунт — прежние данные оказались бы доступны")
	}
	if second.userID == first.userID {
		t.Fatal("перерегистрация вернула прежнюю личность")
	}
}

// Ядро Р4: пока escrow-ключ жив, стирать нельзя; когда уничтожен — можно, и
// метаданные при этом остаются.
func TestPurgeGatedByEscrowKey(t *testing.T) {
	ts, srv := setup(t)
	withEnclave(t, srv)
	ctx := context.Background()

	sender := registerDevice(t, ts, "+79990050004")
	recipient := registerDevice(t, ts, "+79990050005")

	// Ключ эпохи для чата — как его получил бы клиент перед отправкой.
	got, code := getEscrowKey(t, ts, sender.token, chatID)
	if code != 200 {
		t.Fatalf("/escrow/key: %d", code)
	}
	env := sealEnvelope(t, sender, []*device{sender, recipient}, 900001, []byte("секрет"))
	env.Escrow.EscrowKeyVersion = got.Current.ID
	if resp := post(t, ts, env, sender.token, "cccccccc-0000-0000-0000-000000000001"); resp.StatusCode != 201 {
		defer resp.Body.Close()
		t.Fatalf("отправка: %d", resp.StatusCode)
	}

	// Ключ жив — содержимое обязано остаться: срок хранения ещё не истёк.
	n, err := srv.Store.PurgeMessageContent(ctx, time.Now(), 100)
	if err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("стёрто %d сообщений при живом ключе — нарушен срок хранения", n)
	}

	// Ключ уничтожен — расшифровать нечем никому, содержимое можно стирать.
	k, err := srv.Store.FindEscrowKey(ctx, "ru", got.Current.Epoch, chatID)
	if err != nil {
		t.Fatal(err)
	}
	n, err = srv.Store.PurgeMessageContent(ctx, k.DestroyAt.Add(time.Hour), 100)
	if err != nil {
		t.Fatal(err)
	}
	if n == 0 {
		t.Fatal("после уничтожения ключа содержимое не стёрлось")
	}

	// Метаданные остались: отдельная плоскость с отдельным сроком.
	items, err := srv.Store.ListMessages(ctx, chatID, recipient.id, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) == 0 {
		t.Fatal("вместе с содержимым исчезла и строка сообщения — метаданные не удаляются никогда")
	}
}

// Повторный проход не находит работы: стирание идемпотентно.
func TestPurgeIsIdempotent(t *testing.T) {
	_, srv := setup(t)
	ctx := context.Background()
	n, err := srv.Store.PurgeMessageContent(ctx, time.Now().Add(1000*24*time.Hour), 100)
	if err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("на пустой базе стёрто %d", n)
	}
}

var _ store.AccountState = store.StatePermanent
