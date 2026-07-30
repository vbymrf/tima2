// Package pii — шифрование персональных данных в покое: телефон, имя.
//
// # Что закрывает и чего не закрывает
//
// Модель угроз — утечка базы или её бэкапа (ДОКУМЕНТАЦИЯ/04-данные-и-удаление).
// Ключ и pepper лежат ФАЙЛОМ вне PostgreSQL: pg_dump и любая копия каталога
// данных их не содержат по построению, поэтому украденный дамп номеров не даёт.
//
// От компрометации самого хоста это не защищает — и не должно. Для этого нужен
// внешний держатель ключей; переезд к нему сводится к замене реализации Cipher,
// потому что остальной код обращается к персональным данным только через него и
// про существование ключа не знает.
//
// # Почему pepper обязан лежать здесь, а не в базе
//
// BlindIndex — это HMAC от телефона. Пространство телефонных номеров крошечное
// (порядка 10^10), поэтому утёкший вместе с базой pepper делает перебор всех
// индексов делом минут. Ценность схемы ровно в том, что pepper в дампе
// отсутствует.
package pii

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/crypto/nacl/secretbox"
)

const (
	keySize   = 32
	nonceSize = 24
	// headerSize — key_id (4 байта BE) перед nonce. Идентификатор ключа лежит
	// рядом с каждым шифртекстом: без него первая же ротация упирается в вопрос
	// «каким ключом зашифрована эта строка», а ответить на него задним числом
	// нельзя (план рефакторинга §2).
	headerSize = 4
)

var (
	ErrNoKey     = errors.New("pii: ключ не загружен")
	ErrCorrupt   = errors.New("pii: шифртекст повреждён или зашифрован другим ключом")
	ErrShortData = errors.New("pii: шифртекст короче заголовка")
)

// Cipher шифрует персональные поля и строит слепые индексы для поиска по ним.
type Cipher struct {
	keyID  uint32
	dek    [keySize]byte
	pepper []byte
}

// keyFile — формат файла ключа. Держим key_id явным, чтобы ротация была
// добавлением ключа, а не миграцией всех строк разом.
type keyFile struct {
	KeyID  uint32 `json:"key_id"`
	DEK    string `json:"dek"`    // base64url, 32 байта
	Pepper string `json:"pepper"` // base64url, 32 байта
}

// Load читает ключ из файла; если файла нет — генерирует и сохраняет с правами
// 0600. Каталог файла не должен совпадать с каталогом данных PostgreSQL и не
// должен попадать в бэкапы базы — иначе вся схема бессмысленна.
func Load(path string) (*Cipher, error) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return generate(path)
	} else if err != nil {
		return nil, fmt.Errorf("pii: чтение ключа: %w", err)
	}
	var kf keyFile
	if err := json.Unmarshal(raw, &kf); err != nil {
		return nil, fmt.Errorf("pii: разбор файла ключа: %w", err)
	}
	return fromFile(kf)
}

func fromFile(kf keyFile) (*Cipher, error) {
	dek, err := base64.RawURLEncoding.DecodeString(kf.DEK)
	if err != nil || len(dek) != keySize {
		return nil, fmt.Errorf("pii: dek должен быть %d байт в base64url", keySize)
	}
	pepper, err := base64.RawURLEncoding.DecodeString(kf.Pepper)
	if err != nil || len(pepper) != keySize {
		return nil, fmt.Errorf("pii: pepper должен быть %d байт в base64url", keySize)
	}
	if kf.KeyID == 0 {
		return nil, errors.New("pii: key_id не может быть нулём")
	}
	c := &Cipher{keyID: kf.KeyID, pepper: pepper}
	copy(c.dek[:], dek)
	return c, nil
}

func generate(path string) (*Cipher, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("pii: каталог ключа: %w", err)
	}
	buf := make([]byte, keySize*2)
	if _, err := rand.Read(buf); err != nil {
		return nil, fmt.Errorf("pii: генерация ключа: %w", err)
	}
	kf := keyFile{
		KeyID:  1,
		DEK:    base64.RawURLEncoding.EncodeToString(buf[:keySize]),
		Pepper: base64.RawURLEncoding.EncodeToString(buf[keySize:]),
	}
	body, err := json.MarshalIndent(kf, "", "  ")
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, body, 0o600); err != nil {
		return nil, fmt.Errorf("pii: запись ключа: %w", err)
	}
	return fromFile(kf)
}

// KeyID — идентификатор текущего ключа (пишется в каждый шифртекст).
func (c *Cipher) KeyID() uint32 { return c.keyID }

// NormalizePhone приводит номер к виду, от которого считается слепой индекс.
// Запись и поиск обязаны пользоваться одной функцией: разойдутся — поиск по
// номеру перестанет находить существующих пользователей.
func NormalizePhone(phone string) string {
	var b strings.Builder
	for i, r := range phone {
		switch {
		case r >= '0' && r <= '9':
			b.WriteRune(r)
		case r == '+' && i == 0:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// BlindIndex — детерминированный индекс для поиска по номеру без его хранения.
// Одинаковый номер даёт одинаковый индекс, обратно номер не восстанавливается.
func (c *Cipher) BlindIndex(phone string) []byte {
	mac := hmac.New(sha256.New, c.pepper)
	mac.Write([]byte(NormalizePhone(phone)))
	return mac.Sum(nil)
}

// Seal шифрует значение. Пустая строка даёт nil — в базе это NULL, а не
// шифртекст пустоты: так видно, что значения нет, и не тратится место.
func (c *Cipher) Seal(plain string) ([]byte, error) {
	if plain == "" {
		return nil, nil
	}
	var nonce [nonceSize]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, fmt.Errorf("pii: nonce: %w", err)
	}
	out := make([]byte, headerSize, headerSize+nonceSize+len(plain)+secretbox.Overhead)
	binary.BigEndian.PutUint32(out, c.keyID)
	out = append(out, nonce[:]...)
	return secretbox.Seal(out, []byte(plain), &nonce, &c.dek), nil
}

// Open расшифровывает значение. nil и пустой срез дают пустую строку.
func (c *Cipher) Open(b []byte) (string, error) {
	if len(b) == 0 {
		return "", nil
	}
	if len(b) < headerSize+nonceSize {
		return "", ErrShortData
	}
	if id := binary.BigEndian.Uint32(b[:headerSize]); id != c.keyID {
		return "", fmt.Errorf("%w: шифртекст ключа %d, загружен ключ %d", ErrCorrupt, id, c.keyID)
	}
	var nonce [nonceSize]byte
	copy(nonce[:], b[headerSize:headerSize+nonceSize])
	plain, ok := secretbox.Open(nil, b[headerSize+nonceSize:], &nonce, &c.dek)
	if !ok {
		return "", ErrCorrupt
	}
	return string(plain), nil
}
