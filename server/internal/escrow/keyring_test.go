package escrow

import (
	"bytes"
	"crypto/mlkem"
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
	"time"
)

const halfYear = 6 * 30 * 24 * time.Hour

func testKeyring(t *testing.T) *Keyring {
	t.Helper()
	k, err := OpenKeyring(t.TempDir(), halfYear)
	if err != nil {
		t.Fatalf("OpenKeyring: %v", err)
	}
	return k
}

func TestGetOrCreateIsIdempotent(t *testing.T) {
	k := testKeyring(t)
	a, err := k.GetOrCreate("ru", "2026-07", "chat-1")
	if err != nil {
		t.Fatal(err)
	}
	b, err := k.GetOrCreate("ru", "2026-07", "chat-1")
	if err != nil {
		t.Fatal(err)
	}
	if a.ID != b.ID || a.PublicKey != b.PublicKey {
		t.Fatal("повторный запрос той же области создал новый ключ")
	}
}

// Ключ на пару «чат × эпоха»: разные чаты и разные месяцы не делят ключ, иначе
// утечка одного вскрывала бы чужую переписку или соседний период.
func TestScopeIsolation(t *testing.T) {
	k := testKeyring(t)
	base, _ := k.GetOrCreate("ru", "2026-07", "chat-1")
	otherChat, _ := k.GetOrCreate("ru", "2026-07", "chat-2")
	otherEpoch, _ := k.GetOrCreate("ru", "2026-08", "chat-1")
	otherRegion, _ := k.GetOrCreate("eu", "2026-07", "chat-1")

	seen := map[string]string{
		"базовый":       base.PublicKey,
		"другой чат":    otherChat.PublicKey,
		"другая эпоха":  otherEpoch.PublicKey,
		"другой регион": otherRegion.PublicKey,
	}
	for nameA, a := range seen {
		for nameB, b := range seen {
			if nameA != nameB && a == b {
				t.Fatalf("%s и %s получили один ключ", nameA, nameB)
			}
		}
	}
	ids := map[uint32]bool{base.ID: true, otherChat.ID: true, otherEpoch.ID: true, otherRegion.ID: true}
	if len(ids) != 4 {
		t.Fatal("идентификаторы повторились — блоб указывал бы не на тот ключ")
	}
}

// Главное свойство схемы: seed каждого ключа независим. Если бы ключи выводились
// из общего корня, уничтожение было бы фикцией — корень восстановил бы стёртое.
func TestSeedsAreIndependent(t *testing.T) {
	k := testKeyring(t)
	a, _ := k.GetOrCreate("ru", "2026-07", "chat-1")
	b, _ := k.GetOrCreate("ru", "2026-07", "chat-2")

	sa, err := k.Seed(a.ID)
	if err != nil {
		t.Fatal(err)
	}
	sb, err := k.Seed(b.ID)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(sa, sb) {
		t.Fatal("seed'ы совпали")
	}
	// Ни один seed не является префиксом/суффиксом другого и не выводится
	// тривиально — грубая проверка на «случайно одинаковый источник».
	if bytes.Contains(sa, sb[:8]) || bytes.Contains(sb, sa[:8]) {
		t.Fatal("seed'ы пересекаются — похоже на общий корень")
	}
}

func TestDestroyExpiredRemovesKeyMaterial(t *testing.T) {
	dir := t.TempDir()
	k, err := OpenKeyring(dir, halfYear)
	if err != nil {
		t.Fatal(err)
	}
	old, _ := k.GetOrCreate("ru", "2026-01", "chat-1")
	fresh, _ := k.GetOrCreate("ru", "2026-07", "chat-1")

	// Момент, когда старая эпоха уже пережила свой срок, а свежая — ещё нет.
	now := old.DestroyAt.Add(time.Hour)
	destroyed, err := k.DestroyExpired(now)
	if err != nil {
		t.Fatal(err)
	}
	if len(destroyed) != 1 || destroyed[0] != old.ID {
		t.Fatalf("уничтожено %v, ожидали только %d", destroyed, old.ID)
	}

	if _, err := k.Seed(old.ID); err == nil {
		t.Fatal("seed уничтоженного ключа всё ещё читается")
	}
	if _, err := k.Meta(old.ID); err == nil {
		t.Fatal("метаданные уничтоженного ключа всё ещё читаются")
	}
	if _, err := k.Seed(fresh.ID); err != nil {
		t.Fatalf("живой ключ пострадал: %v", err)
	}

	// Файла на диске тоже нет: уничтожение физическое, а не пометка.
	if _, err := os.Stat(filepath.Join(dir, keysDir)); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(filepath.Join(dir, keysDir))
	for _, e := range entries {
		if e.Name() == "1.json" {
			t.Fatal("файл уничтоженного ключа остался на диске")
		}
	}
}

// После уничтожения область освобождается: новое сообщение в том же чате той же
// эпохи заведёт НОВЫЙ ключ, а не воскресит старый.
func TestDestroyedScopeGetsFreshKey(t *testing.T) {
	k := testKeyring(t)
	old, _ := k.GetOrCreate("ru", "2026-01", "chat-1")
	if _, err := k.DestroyExpired(old.DestroyAt.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	again, err := k.GetOrCreate("ru", "2026-01", "chat-1")
	if err != nil {
		t.Fatal(err)
	}
	if again.ID == old.ID {
		t.Fatal("переиспользован идентификатор уничтоженного ключа")
	}
	if again.PublicKey == old.PublicKey {
		t.Fatal("уничтоженный ключ воскрес")
	}
}

// Срок ключа обязан покрывать срок хранения для ЛЮБОГО сообщения эпохи, включая
// отправленное в последний её день.
func TestKeyOutlivesRetentionForWholeEpoch(t *testing.T) {
	k := testKeyring(t)
	m, _ := k.GetOrCreate("ru", "2026-07", "chat-1")
	lastMoment := m.ValidTo.Add(-time.Second) // сообщение в самом конце эпохи
	if m.DestroyAt.Sub(lastMoment) < halfYear {
		t.Fatalf("сообщение конца эпохи доступно %v, а закон требует %v",
			m.DestroyAt.Sub(lastMoment), halfYear)
	}
}

func TestKeyringSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	first, err := OpenKeyring(dir, halfYear)
	if err != nil {
		t.Fatal(err)
	}
	a, _ := first.GetOrCreate("ru", "2026-07", "chat-1")

	second, err := OpenKeyring(dir, halfYear)
	if err != nil {
		t.Fatal(err)
	}
	got, err := second.GetOrCreate("ru", "2026-07", "chat-1")
	if err != nil {
		t.Fatal(err)
	}
	if got.ID != a.ID || got.PublicKey != a.PublicKey {
		t.Fatal("после перезапуска область получила другой ключ")
	}
	// Счётчик не откатился: новый ключ не затирает существующий.
	fresh, _ := second.GetOrCreate("ru", "2026-07", "chat-2")
	if fresh.ID == a.ID {
		t.Fatal("после перезапуска идентификатор переиспользован")
	}
}

func TestRejectsBadScope(t *testing.T) {
	k := testKeyring(t)
	for _, c := range []struct{ region, epoch, chat string }{
		{"ru", "2026-13", "chat"},   // месяца 13 не бывает
		{"ru", "2026-7", "chat"},    // формат без ведущего нуля
		{"ru", "2026-07", ""},       // пустой чат
		{"", "2026-07", "chat"},     // пустой регион
		{"ru", "2026-07", "../etc"}, // выход за каталог
		{"ru", "2026-07", "a/b"},
	} {
		if _, err := k.GetOrCreate(c.region, c.epoch, c.chat); err == nil {
			t.Fatalf("принята некорректная область %q/%q/%q", c.region, c.epoch, c.chat)
		}
	}
}

// Публичный ключ из связки действительно рабочий: на него можно инкапсулировать,
// а seed из связки — декапсулировать обратно тот же секрет.
func TestKeyIsUsableForEncapsulation(t *testing.T) {
	k := testKeyring(t)
	m, _ := k.GetOrCreate("ru", "2026-07", "chat-1")
	pub, err := base64.RawURLEncoding.DecodeString(m.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	ek, err := mlkem.NewEncapsulationKey768(pub)
	if err != nil {
		t.Fatal(err)
	}
	shared, ct := ek.Encapsulate()
	if len(ct) != CtSize {
		t.Fatalf("ciphertext %d байт, ожидали %d", len(ct), CtSize)
	}
	seed, err := k.Seed(m.ID)
	if err != nil {
		t.Fatal(err)
	}
	dk, err := mlkem.NewDecapsulationKey768(seed)
	if err != nil {
		t.Fatal(err)
	}
	back, err := dk.Decapsulate(ct)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(shared, back) {
		t.Fatal("декапсуляция дала другой секрет")
	}
}

func TestEpochOf(t *testing.T) {
	if got := EpochOf(time.Date(2026, 7, 30, 23, 59, 0, 0, time.UTC)); got != "2026-07" {
		t.Fatalf("EpochOf = %q", got)
	}
}
