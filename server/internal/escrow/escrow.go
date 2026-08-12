// Package escrow — stub-анклав (escrow-legal-access.md §7, MVP/бета):
// изолированный контейнер с тем же API, что production HSM / Nitro Enclave
// (gate фазы 6). Приватный ключ ML-KEM-768 живёт только здесь; бэкенд tima
// видит исключительно публичный ключ. Unseal — по порогу долей Шамира,
// каждый вызов — в append-only audit log.
//
// Уровень изоляции stub: seed на диске контейнера, доли авторизуют операцию
// (сверка hash восстановленного unlock-секрета). Криптографический барьер
// (ключ невыгружаем) появится с HSM — API при этом не меняется.
package escrow

import (
	"crypto/ed25519"
	"crypto/mlkem"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/secretbox"
)

const (
	hkdfInfo   = "tima/escrow/v1" // нормативная деривация (crypto-protocol.md §6)
	SharesN    = 5                // долей всего
	SharesK    = 3                // порог unseal (минимум по escrow-legal-access.md §3)
	stateFile  = "escrow-state.json"
	auditFile  = "escrow-audit.jsonl"
	CtSize     = 1088 // ML-KEM-768 ciphertext
	PubKeySize = 1184
)

// state — что лежит на диске stub-анклава. Seed здесь = уровень изоляции stub
// (см. док пакета); unlock_hash — sha256 секрета, собираемого из долей.
//
// SigningSeed/SigningPub — ключ подписи конфига (Р2): анклав подписывает всё, что
// отдаёт клиентам через бэкенд (public_key области, легаси-pubkey), приватным
// ключом Ed25519, который живёт только здесь. Без этого компрометация бэкенда
// позволяет подменить public_key, который клиент получает по пути — тот
// зашифрует сообщение на чужой ключ в обход анклава и всего механизма
// контролируемого доступа. Поле отдельное от ML-KEM ключа: разные роли, разная
// подпись не должна годиться в двух местах.
type state struct {
	Version     int    `json:"escrow_key_version"`
	Seed        string `json:"seed"`       // base64url, 64 байта (d‖z, FIPS 203)
	PublicKey   string `json:"public_key"` // base64url, 1184 байта
	UnlockHash  string `json:"unlock_hash"`
	SigningSeed string `json:"signing_seed"` // base64url, 32 байта (ed25519 seed)
	SigningPub  string `json:"signing_pub"`  // base64url, 32 байта
}

type Enclave struct {
	dir         string
	version     int
	seed        []byte
	pubKey      []byte
	unlock      []byte // sha256(unlock-секрет)
	signingPriv ed25519.PrivateKey
	signingPub  ed25519.PublicKey
}

// Open загружает состояние анклава; если его нет — генерирует ключ и доли.
// Доли возвращаются ТОЛЬКО при первой инициализации (newShares != nil):
// оператор раздаёт их держателям, повторно они не выдаются.
func Open(dir string) (enc *Enclave, newShares []string, err error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, nil, err
	}
	path := filepath.Join(dir, stateFile)
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return initialize(dir, path)
	} else if err != nil {
		return nil, nil, err
	}
	var st state
	if err := json.Unmarshal(raw, &st); err != nil {
		return nil, nil, fmt.Errorf("%s повреждён: %w", stateFile, err)
	}
	b64 := base64.RawURLEncoding
	seed, err1 := b64.DecodeString(st.Seed)
	pub, err2 := b64.DecodeString(st.PublicKey)
	unlock, err3 := b64.DecodeString(st.UnlockHash)
	if err1 != nil || err2 != nil || err3 != nil || len(seed) != 64 || len(pub) != PubKeySize || len(unlock) != 32 {
		return nil, nil, fmt.Errorf("%s: некорректные поля", stateFile)
	}
	signingPriv, signingPub, err := loadOrCreateSigningKey(&st, path)
	if err != nil {
		return nil, nil, err
	}
	return &Enclave{dir: dir, version: st.Version, seed: seed, pubKey: pub, unlock: unlock,
		signingPriv: signingPriv, signingPub: signingPub}, nil, nil
}

// loadOrCreateSigningKey — ключ подписи конфига (Р2). Состояния анклава, заведённые
// до этого поля, ключа подписи не имеют: генерируем и дописываем state-файл, а не
// падаем — приватный ML-KEM ключ и доли Шамира при этом не трогаются никак.
func loadOrCreateSigningKey(st *state, path string) (ed25519.PrivateKey, ed25519.PublicKey, error) {
	b64 := base64.RawURLEncoding
	if st.SigningSeed != "" {
		seed, err := b64.DecodeString(st.SigningSeed)
		if err != nil || len(seed) != ed25519.SeedSize {
			return nil, nil, fmt.Errorf("%s: некорректный signing_seed", stateFile)
		}
		priv := ed25519.NewKeyFromSeed(seed)
		return priv, priv.Public().(ed25519.PublicKey), nil
	}
	seed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(seed); err != nil {
		return nil, nil, err
	}
	priv := ed25519.NewKeyFromSeed(seed)
	pub := priv.Public().(ed25519.PublicKey)
	st.SigningSeed = b64.EncodeToString(seed)
	st.SigningPub = b64.EncodeToString(pub)
	raw, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return nil, nil, err
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		return nil, nil, err
	}
	return priv, pub, nil
}

func initialize(dir, path string) (*Enclave, []string, error) {
	seed := make([]byte, 64)
	if _, err := rand.Read(seed); err != nil {
		return nil, nil, err
	}
	dk, err := mlkem.NewDecapsulationKey768(seed)
	if err != nil {
		return nil, nil, err
	}
	pub := dk.EncapsulationKey().Bytes()

	unlockSecret := make([]byte, 32)
	if _, err := rand.Read(unlockSecret); err != nil {
		return nil, nil, err
	}
	shares, err := SplitSecret(unlockSecret, SharesN, SharesK)
	if err != nil {
		return nil, nil, err
	}
	unlockHash := sha256.Sum256(unlockSecret)

	signingSeed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(signingSeed); err != nil {
		return nil, nil, err
	}
	signingPriv := ed25519.NewKeyFromSeed(signingSeed)
	signingPub := signingPriv.Public().(ed25519.PublicKey)

	b64 := base64.RawURLEncoding
	raw, _ := json.MarshalIndent(state{
		Version:     1,
		Seed:        b64.EncodeToString(seed),
		PublicKey:   b64.EncodeToString(pub),
		UnlockHash:  b64.EncodeToString(unlockHash[:]),
		SigningSeed: b64.EncodeToString(signingSeed),
		SigningPub:  b64.EncodeToString(signingPub),
	}, "", "  ")
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		return nil, nil, err
	}
	return &Enclave{dir: dir, version: 1, seed: seed, pubKey: pub, unlock: unlockHash[:],
		signingPriv: signingPriv, signingPub: signingPub}, shares, nil
}

func (e *Enclave) Version() int      { return e.version }
func (e *Enclave) PublicKey() []byte { return e.pubKey }

// SigningPublicKey — публичный ключ подписи конфига (Р2). Не секрет: можно
// печатать в лог при каждом старте, чтобы оператор мог зашить его в официальную
// сборку клиента (тот же приём, что и с долями Шамира при инициализации, только
// без разового окна — значение не меняется между перезапусками).
func (e *Enclave) SigningPublicKey() []byte { return e.signingPub }

var ErrUnauthorized = errors.New("доли не восстанавливают unlock-секрет")

// authorize сверяет восстановленный из долей секрет с сохранённым хэшом.
func (e *Enclave) authorize(shares []string) error {
	if len(shares) < SharesK {
		return fmt.Errorf("%w: нужно минимум %d долей", ErrUnauthorized, SharesK)
	}
	secret, err := CombineShares(shares[:SharesK])
	if err != nil {
		return fmt.Errorf("%w: %v", ErrUnauthorized, err)
	}
	got := sha256.Sum256(secret)
	if subtle.ConstantTimeCompare(got[:], e.unlock) != 1 {
		return ErrUnauthorized
	}
	return nil
}

// Unwrap — операция HSM: decapsulate + разворачивание ключа сообщения/GK.
// Зеркало клиентского EscrowModule.wrap (Kotlin) — контракт hkdf-инфо общий.
func (e *Enclave) unwrap(mlkemCt, wrapped []byte) ([]byte, error) {
	if len(mlkemCt) != CtSize || len(wrapped) < 24+16 {
		return nil, errors.New("некорректные размеры escrow-блоба")
	}
	dk, err := mlkem.NewDecapsulationKey768(e.seed)
	if err != nil {
		return nil, err
	}
	shared, err := dk.Decapsulate(mlkemCt)
	if err != nil {
		return nil, err
	}
	wrapKey := make([]byte, 32)
	if _, err := io.ReadFull(hkdf.New(sha256.New, shared, nil, []byte(hkdfInfo)), wrapKey); err != nil {
		return nil, err
	}
	var key [32]byte
	copy(key[:], wrapKey)
	var nonce [24]byte
	copy(nonce[:], wrapped[:24])
	opened, ok := secretbox.Open(nil, wrapped[24:], &nonce, &key)
	if !ok {
		return nil, errors.New("SecretBox не открылся (блоб под другим ключом/версией?)")
	}
	return opened, nil
}
