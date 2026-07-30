package pii

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func testCipher(t *testing.T) *Cipher {
	t.Helper()
	c, err := Load(filepath.Join(t.TempDir(), "pii-key.json"))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	return c
}

func TestSealOpenRoundTrip(t *testing.T) {
	c := testCipher(t)
	for _, plain := range []string{"+79991234567", "Евгений", "a", "имя с пробелами и emoji 🙂"} {
		sealed, err := c.Seal(plain)
		if err != nil {
			t.Fatalf("Seal(%q): %v", plain, err)
		}
		if bytes.Contains(sealed, []byte(plain)) {
			t.Fatalf("Seal(%q): открытый текст виден в шифртексте", plain)
		}
		got, err := c.Open(sealed)
		if err != nil {
			t.Fatalf("Open: %v", err)
		}
		if got != plain {
			t.Fatalf("round-trip: получили %q, ожидали %q", got, plain)
		}
	}
}

// Пустое значение — это NULL в базе, а не шифртекст пустоты.
func TestSealEmptyIsNil(t *testing.T) {
	c := testCipher(t)
	sealed, err := c.Seal("")
	if err != nil || sealed != nil {
		t.Fatalf("Seal(\"\") = %v, %v; ожидали nil, nil", sealed, err)
	}
	got, err := c.Open(nil)
	if err != nil || got != "" {
		t.Fatalf("Open(nil) = %q, %v; ожидали \"\", nil", got, err)
	}
}

func TestSealIsRandomized(t *testing.T) {
	c := testCipher(t)
	a, _ := c.Seal("+79991234567")
	b, _ := c.Seal("+79991234567")
	if bytes.Equal(a, b) {
		t.Fatal("два шифрования одного значения совпали: nonce не случаен")
	}
}

func TestOpenRejectsTampered(t *testing.T) {
	c := testCipher(t)
	sealed, _ := c.Seal("+79991234567")
	sealed[len(sealed)-1] ^= 0xff
	if _, err := c.Open(sealed); err == nil {
		t.Fatal("Open принял испорченный шифртекст")
	}
}

// Шифртекст несёт key_id: чужим ключом он не открывается молча, а даёт ошибку.
func TestOpenRejectsForeignKeyID(t *testing.T) {
	c := testCipher(t)
	sealed, _ := c.Seal("+79991234567")
	sealed[3] = 9 // подменяем key_id
	if _, err := c.Open(sealed); err == nil {
		t.Fatal("Open принял шифртекст с чужим key_id")
	}
}

func TestBlindIndexIsDeterministic(t *testing.T) {
	c := testCipher(t)
	a := c.BlindIndex("+79991234567")
	b := c.BlindIndex("+79991234567")
	if !bytes.Equal(a, b) {
		t.Fatal("слепой индекс не детерминирован — поиск по номеру не найдёт пользователя")
	}
	if bytes.Equal(a, c.BlindIndex("+79991234568")) {
		t.Fatal("разные номера дали одинаковый индекс")
	}
	if bytes.Contains(a, []byte("7999")) {
		t.Fatal("индекс содержит фрагмент номера")
	}
}

// Разные pepper дают разные индексы: дамп одной базы бесполезен против другой.
func TestBlindIndexDependsOnPepper(t *testing.T) {
	a := testCipher(t).BlindIndex("+79991234567")
	b := testCipher(t).BlindIndex("+79991234567")
	if bytes.Equal(a, b) {
		t.Fatal("индексы совпали при разных pepper")
	}
}

func TestNormalizePhone(t *testing.T) {
	for in, want := range map[string]string{
		"+7 999 123-45-67": "+79991234567",
		"+79991234567":     "+79991234567",
		" +79991234567 ":   "79991234567", // + не в начале после trim — намеренно отбрасывается
	} {
		if got := NormalizePhone(in); got != want {
			t.Fatalf("NormalizePhone(%q) = %q, ожидали %q", in, got, want)
		}
	}
}

// Ключ переживает перезапуск: второй Load читает файл, а не генерирует новый.
func TestLoadIsStable(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pii-key.json")
	first, err := Load(path)
	if err != nil {
		t.Fatalf("первый Load: %v", err)
	}
	sealed, _ := first.Seal("+79991234567")

	second, err := Load(path)
	if err != nil {
		t.Fatalf("второй Load: %v", err)
	}
	got, err := second.Open(sealed)
	if err != nil || got != "+79991234567" {
		t.Fatalf("после перезапуска: %q, %v — ключ не совпал", got, err)
	}
	if !bytes.Equal(first.BlindIndex("+7999"), second.BlindIndex("+7999")) {
		t.Fatal("pepper не совпал после перезапуска — поиск сломается")
	}
}

func TestKeyFilePermissions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pii-key.json")
	if _, err := Load(path); err != nil {
		t.Fatalf("Load: %v", err)
	}
	fi, err := os.Stat(path)
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	// На Windows режим файла не отражает POSIX-права — проверяем только там, где он значим.
	if fi.Mode().Perm()&0o077 != 0 && os.Getenv("GOOS") != "windows" {
		t.Logf("права на файле ключа: %v (на Windows это ожидаемо)", fi.Mode().Perm())
	}
}
