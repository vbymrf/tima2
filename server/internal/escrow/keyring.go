package escrow

// Связка ключей эпох (ПЛАН-РЕФАКТОРИНГА.md Р2, решения 4 и 5).
//
// # Зачем
//
// Единственный глобальный escrow-ключ означает, что одна его утечка вскрывает всю
// историю навсегда, а «храним 6 месяцев» остаётся текстом политики, ничем не
// подкреплённым. Ключ на пару «чат × эпоха» с уничтожением по расписанию делает
// срок хранения физическим фактом: истёк — расшифровать нечем никому.
//
// # Почему у каждого ключа СВОЙ случайный seed
//
// Соблазн вывести ключи эпох из одного корневого seed через HKDF велик: одна
// секретная величина вместо миллиона файлов. Так делать нельзя — уничтожение
// превратится в фикцию, потому что корень восстановит любой стёртый ключ. Здесь
// каждый seed читается из CSPRNG независимо, и стереть его можно только физически.
// Именно поэтому ключ лежит отдельным файлом: удаление файла и есть уничтожение.
//
// # Почему ключ на чат, а не на «шард»
//
// Дробление по бакетам (hash(chat) mod N) даёт радиус «1/N всех чатов за период».
// Ключ на чат тоньше и ложится на ордер один в один: запрошены чат и период —
// ровно эти ключи и нужны, лишнего в scope не попадает.

import (
	"crypto/mlkem"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	keysDir  = "keys"  // keys/<id>.json — материал ключа
	scopeDir = "scope" // scope/<region>/<epoch>/<chat_id> — файл с id (обратный поиск)
)

// EpochRe — формат эпохи: год-месяц. Держим строкой, а не датой: она попадает в
// пути и в подписанный конфиг, и должна читаться человеком в аудите.
var EpochRe = regexp.MustCompile(`^\d{4}-(0[1-9]|1[0-2])$`)

var (
	ErrBadScope   = errors.New("escrow: некорректная область ключа (регион/эпоха/чат)")
	ErrKeyUnknown = errors.New("escrow: ключ не найден — уничтожен по сроку или не создавался")
)

// KeyMeta — публичная часть записи связки. Приватный seed наружу не отдаётся никогда.
type KeyMeta struct {
	ID        uint32    `json:"id"` // то, что летит в EscrowBlob.escrow_key_version
	Region    string    `json:"region"`
	Epoch     string    `json:"epoch"`
	ChatID    string    `json:"chat_id"`
	PublicKey string    `json:"public_key"` // base64url, 1184 байта
	ValidFrom time.Time `json:"valid_from"` // окно, в котором на ключ шифруют
	ValidTo   time.Time `json:"valid_to"`
	DestroyAt time.Time `json:"destroy_at"` // после этого момента приватный стирается
}

// keyFileOnDisk — то, что реально лежит в keys/<id>.json.
type keyFileOnDisk struct {
	KeyMeta
	Seed string `json:"seed"` // base64url, 64 байта; стирается вместе с файлом
}

// Keyring — связка ключей эпох поверх каталога анклава.
type Keyring struct {
	dir       string
	mu        sync.Mutex
	next      uint32        // следующий свободный идентификатор
	retention time.Duration // сколько ключ живёт ПОСЛЕ конца эпохи (срок по закону)
}

// OpenKeyring поднимает связку в каталоге анклава. retention — срок хранения по
// закону: ключ уничтожается через (конец эпохи + retention), поэтому даже сообщение
// последнего дня эпохи доступно положенный срок целиком.
func OpenKeyring(dir string, retention time.Duration) (*Keyring, error) {
	for _, d := range []string{filepath.Join(dir, keysDir), filepath.Join(dir, scopeDir)} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return nil, err
		}
	}
	k := &Keyring{dir: dir, retention: retention}
	next, err := k.scanMaxID()
	if err != nil {
		return nil, err
	}
	k.next = next + 1
	return k, nil
}

// scanMaxID восстанавливает счётчик по содержимому каталога. Отдельного файла со
// счётчиком нет намеренно: он мог бы разъехаться с реальностью и выдать
// повторный идентификатор, а повтор означает перезапись живого ключа.
func (k *Keyring) scanMaxID() (uint32, error) {
	entries, err := os.ReadDir(filepath.Join(k.dir, keysDir))
	if err != nil {
		return 0, err
	}
	var max uint32
	for _, e := range entries {
		name := strings.TrimSuffix(e.Name(), ".json")
		id, err := strconv.ParseUint(name, 10, 32)
		if err != nil {
			continue
		}
		if uint32(id) > max {
			max = uint32(id)
		}
	}
	return max, nil
}

func (k *Keyring) keyPath(id uint32) string {
	return filepath.Join(k.dir, keysDir, strconv.FormatUint(uint64(id), 10)+".json")
}

func (k *Keyring) scopePath(region, epoch, chatID string) string {
	return filepath.Join(k.dir, scopeDir, region, epoch, chatID)
}

// validScope — регион, эпоха и чат идут в пути файлов, поэтому проверяем строго:
// «..» или слэш в chat_id увели бы запись за пределы каталога связки.
func validScope(region, epoch, chatID string) error {
	if region == "" || chatID == "" || !EpochRe.MatchString(epoch) {
		return ErrBadScope
	}
	for _, s := range []string{region, chatID} {
		if strings.ContainsAny(s, `/\`) || s == "." || s == ".." {
			return ErrBadScope
		}
	}
	return nil
}

// epochBounds — границы календарного месяца эпохи.
func epochBounds(epoch string) (from, to time.Time, err error) {
	t, err := time.Parse("2006-01", epoch)
	if err != nil {
		return time.Time{}, time.Time{}, ErrBadScope
	}
	from = t.UTC()
	return from, from.AddDate(0, 1, 0), nil
}

// GetOrCreate возвращает ключ для области, создавая его при первом обращении.
// Ленивая генерация — принципиальная часть: ключи существуют только для чатов,
// в которых в эту эпоху действительно что-то происходило.
func (k *Keyring) GetOrCreate(region, epoch, chatID string) (KeyMeta, error) {
	if err := validScope(region, epoch, chatID); err != nil {
		return KeyMeta{}, err
	}
	k.mu.Lock()
	defer k.mu.Unlock()

	if meta, err := k.lookupScope(region, epoch, chatID); err == nil {
		return meta, nil
	} else if !errors.Is(err, ErrKeyUnknown) {
		return KeyMeta{}, err
	}

	from, to, err := epochBounds(epoch)
	if err != nil {
		return KeyMeta{}, err
	}

	// СВОЙ случайный seed. Никакой деривации из общего корня — см. док пакета.
	seed := make([]byte, 64)
	if _, err := rand.Read(seed); err != nil {
		return KeyMeta{}, err
	}
	dk, err := mlkem.NewDecapsulationKey768(seed)
	if err != nil {
		return KeyMeta{}, err
	}

	id := k.next
	meta := KeyMeta{
		ID:        id,
		Region:    region,
		Epoch:     epoch,
		ChatID:    chatID,
		PublicKey: base64.RawURLEncoding.EncodeToString(dk.EncapsulationKey().Bytes()),
		ValidFrom: from,
		ValidTo:   to,
		DestroyAt: to.Add(k.retention),
	}
	body, err := json.MarshalIndent(keyFileOnDisk{
		KeyMeta: meta,
		Seed:    base64.RawURLEncoding.EncodeToString(seed),
	}, "", "  ")
	if err != nil {
		return KeyMeta{}, err
	}
	if err := os.WriteFile(k.keyPath(id), body, 0o600); err != nil {
		return KeyMeta{}, err
	}
	sp := k.scopePath(region, epoch, chatID)
	if err := os.MkdirAll(filepath.Dir(sp), 0o700); err != nil {
		return KeyMeta{}, err
	}
	if err := os.WriteFile(sp, []byte(strconv.FormatUint(uint64(id), 10)), 0o600); err != nil {
		return KeyMeta{}, err
	}
	k.next++
	return meta, nil
}

func (k *Keyring) lookupScope(region, epoch, chatID string) (KeyMeta, error) {
	raw, err := os.ReadFile(k.scopePath(region, epoch, chatID))
	if errors.Is(err, os.ErrNotExist) {
		return KeyMeta{}, ErrKeyUnknown
	} else if err != nil {
		return KeyMeta{}, err
	}
	id, err := strconv.ParseUint(strings.TrimSpace(string(raw)), 10, 32)
	if err != nil {
		return KeyMeta{}, fmt.Errorf("escrow: повреждена запись области %s/%s/%s", region, epoch, chatID)
	}
	return k.Meta(uint32(id))
}

// Meta — публичная часть ключа по идентификатору. Приватный seed не отдаётся.
func (k *Keyring) Meta(id uint32) (KeyMeta, error) {
	f, err := k.read(id)
	if err != nil {
		return KeyMeta{}, err
	}
	return f.KeyMeta, nil
}

func (k *Keyring) read(id uint32) (keyFileOnDisk, error) {
	raw, err := os.ReadFile(k.keyPath(id))
	if errors.Is(err, os.ErrNotExist) {
		return keyFileOnDisk{}, ErrKeyUnknown
	} else if err != nil {
		return keyFileOnDisk{}, err
	}
	var f keyFileOnDisk
	if err := json.Unmarshal(raw, &f); err != nil {
		return keyFileOnDisk{}, fmt.Errorf("escrow: ключ %d повреждён: %w", id, err)
	}
	return f, nil
}

// Seed — приватный материал ключа. Только для операций внутри анклава; наружу
// не отдаётся ни при каких условиях (escrow-legal-access.md §3).
func (k *Keyring) Seed(id uint32) ([]byte, error) {
	f, err := k.read(id)
	if err != nil {
		return nil, err
	}
	seed, err := base64.RawURLEncoding.DecodeString(f.Seed)
	if err != nil || len(seed) != 64 {
		return nil, fmt.Errorf("escrow: ключ %d: некорректный seed", id)
	}
	return seed, nil
}

// DestroyExpired стирает ключи, у которых истёк destroy_at. Возвращает список
// уничтоженных идентификаторов — вызывающий обязан записать это в аудит: без
// доказуемой записи «мы удалили» остаётся обещанием.
func (k *Keyring) DestroyExpired(now time.Time) ([]uint32, error) {
	k.mu.Lock()
	defer k.mu.Unlock()

	entries, err := os.ReadDir(filepath.Join(k.dir, keysDir))
	if err != nil {
		return nil, err
	}
	var destroyed []uint32
	for _, e := range entries {
		id64, err := strconv.ParseUint(strings.TrimSuffix(e.Name(), ".json"), 10, 32)
		if err != nil {
			continue
		}
		id := uint32(id64)
		f, err := k.read(id)
		if err != nil {
			continue
		}
		if now.Before(f.DestroyAt) {
			continue
		}
		// Порядок: сначала запись области, потом сам ключ. Упади процесс между
		// ними — останется ключ без области: он недостижим для новых сообщений,
		// но ещё расшифровывается по id, и следующий проход его добьёт. Обратный
		// порядок оставил бы область, указывающую в пустоту.
		_ = os.Remove(k.scopePath(f.Region, f.Epoch, f.ChatID))
		if err := os.Remove(k.keyPath(id)); err != nil {
			return destroyed, err
		}
		destroyed = append(destroyed, id)
	}
	return destroyed, nil
}

// EpochOf — эпоха, которой принадлежит момент времени.
func EpochOf(t time.Time) string { return t.UTC().Format("2006-01") }
