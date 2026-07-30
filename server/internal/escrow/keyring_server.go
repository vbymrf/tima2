package escrow

// HTTP-поверхность связки ключей эпох (Р2). Дополняет server.go, не заменяя его:
//   POST /v1/key         — ключ области {region, epoch, chat_id}, создаётся лениво
//   GET  /v1/keys/{id}   — публичная часть по идентификатору
//   POST /v1/unseal-scoped — юридический доступ к блобам эпох (каждый блоб со своим id)
//   POST /v1/destroy-expired — принудительный проход уничтожения (обычно по таймеру)
//
// Приватный ключ наружу не отдаётся ни одним из них: анклав возвращает только
// ключи сообщений по явно перечисленным блобам (escrow-legal-access.md §3).

import (
	"crypto/mlkem"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/secretbox"
)

// RegisterKeyring вешает эндпоинты связки на тот же mux, что и Register.
func (e *Enclave) RegisterKeyring(mux *http.ServeMux, k *Keyring) {
	mux.HandleFunc("POST /v1/key", func(w http.ResponseWriter, r *http.Request) { e.handleKey(w, r, k) })
	mux.HandleFunc("GET /v1/keys/{id}", func(w http.ResponseWriter, r *http.Request) { e.handleKeyMeta(w, r, k) })
	mux.HandleFunc("POST /v1/unseal-scoped", func(w http.ResponseWriter, r *http.Request) { e.handleUnsealScoped(w, r, k) })
	mux.HandleFunc("POST /v1/destroy-expired", func(w http.ResponseWriter, r *http.Request) {
		e.handleDestroyExpired(w, r, k)
	})
}

func (e *Enclave) handleKey(w http.ResponseWriter, r *http.Request, k *Keyring) {
	var req struct {
		Region string `json:"region"`
		Epoch  string `json:"epoch"`
		ChatID string `json:"chat_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
		httpErr(w, http.StatusBadRequest, "bad_json")
		return
	}
	meta, err := k.GetOrCreate(req.Region, req.Epoch, req.ChatID)
	if errors.Is(err, ErrBadScope) {
		httpErr(w, http.StatusBadRequest, err.Error())
		return
	} else if err != nil {
		log.Printf("keyring: %v", err)
		httpErr(w, http.StatusInternalServerError, "internal")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(meta)
}

func (e *Enclave) handleKeyMeta(w http.ResponseWriter, r *http.Request, k *Keyring) {
	id, err := strconv.ParseUint(r.PathValue("id"), 10, 32)
	if err != nil {
		httpErr(w, http.StatusBadRequest, "id должен быть числом")
		return
	}
	meta, err := k.Meta(uint32(id))
	if errors.Is(err, ErrKeyUnknown) {
		// Уничтоженный по сроку ключ и никогда не существовавший неотличимы
		// снаружи — и это правильно: анклав не рассказывает, что у него было.
		httpErr(w, http.StatusNotFound, err.Error())
		return
	} else if err != nil {
		httpErr(w, http.StatusInternalServerError, "internal")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(meta)
}

// handleUnsealScoped — юридический доступ по блобам эпох. Каждый блоб несёт свой
// key_id, поэтому ордер на «чат за период» разворачивается ровно в те ключи,
// которые в него попали, и ни в один лишний.
func (e *Enclave) handleUnsealScoped(w http.ResponseWriter, r *http.Request, k *Keyring) {
	var req struct {
		Shares []string `json:"shares"`
		Reason string   `json:"reason"`
		Blobs  []struct {
			KeyID      uint32 `json:"key_id"`
			MlkemCt    string `json:"mlkem_ct"`
			WrappedKey string `json:"wrapped_key"`
		} `json:"blobs"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 16<<20)).Decode(&req); err != nil {
		httpErr(w, http.StatusBadRequest, "bad_json")
		return
	}
	if req.Reason == "" || len(req.Blobs) == 0 {
		httpErr(w, http.StatusBadRequest, "reason и blobs обязательны")
		return
	}
	if err := e.authorize(req.Shares); err != nil {
		e.audit("unseal_scoped_denied", req.Reason, len(req.Blobs), r)
		if errors.Is(err, ErrUnauthorized) {
			httpErr(w, http.StatusForbidden, err.Error())
		} else {
			httpErr(w, http.StatusInternalServerError, "internal")
		}
		return
	}
	b64 := base64.RawURLEncoding
	keys := make([]string, 0, len(req.Blobs))
	for i, blob := range req.Blobs {
		ct, err1 := b64.DecodeString(blob.MlkemCt)
		wrapped, err2 := b64.DecodeString(blob.WrappedKey)
		if err1 != nil || err2 != nil {
			httpErr(w, http.StatusBadRequest, fmt.Sprintf("blob %d: не base64url", i))
			return
		}
		key, err := unwrapWith(k, blob.KeyID, ct, wrapped)
		if err != nil {
			// Частичный успех недопустим: запрос обрабатывается целиком.
			e.audit("unseal_scoped_failed", req.Reason, len(req.Blobs), r)
			status := http.StatusUnprocessableEntity
			if errors.Is(err, ErrKeyUnknown) {
				// Ключ эпохи уничтожен по сроку — расшифровать нечем, и это
				// штатный исход, а не поломка.
				status = http.StatusGone
			}
			httpErr(w, status, fmt.Sprintf("blob %d (ключ %d): %v", i, blob.KeyID, err))
			return
		}
		keys = append(keys, b64.EncodeToString(key))
	}
	e.audit("unseal_scoped_ok", req.Reason, len(req.Blobs), r)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"keys": keys})
}

// unwrapWith — decapsulate + разворачивание ключа сообщения ключом эпохи.
// Деривация обёртки та же, что у клиента (crypto-protocol.md §6): контракт общий.
func unwrapWith(k *Keyring, id uint32, mlkemCt, wrapped []byte) ([]byte, error) {
	if len(mlkemCt) != CtSize || len(wrapped) < 24+16 {
		return nil, errors.New("некорректные размеры escrow-блоба")
	}
	seed, err := k.Seed(id)
	if err != nil {
		return nil, err
	}
	dk, err := mlkem.NewDecapsulationKey768(seed)
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
		return nil, errors.New("SecretBox не открылся (блоб под другим ключом?)")
	}
	return opened, nil
}

func (e *Enclave) handleDestroyExpired(w http.ResponseWriter, r *http.Request, k *Keyring) {
	n, err := e.SweepExpired(k, time.Now())
	if err != nil {
		httpErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"destroyed": n})
}

// SweepExpired уничтожает просроченные ключи и записывает это в аудит.
// Без записи «мы удалили» остаётся обещанием, которое нельзя проверить ни
// аудитору, ни нам самим.
func (e *Enclave) SweepExpired(k *Keyring, now time.Time) (int, error) {
	destroyed, err := k.DestroyExpired(now)
	if len(destroyed) > 0 {
		e.auditDestroy(destroyed, now)
	}
	return len(destroyed), err
}

func (e *Enclave) auditDestroy(ids []uint32, now time.Time) {
	line, _ := json.Marshal(map[string]any{
		"ts":        now.UTC().Format(time.RFC3339),
		"action":    "keys_destroyed",
		"key_ids":   ids,
		"destroyed": len(ids),
	})
	e.appendAudit(line)
}

// StartSweeper запускает фоновое уничтожение по таймеру. Уничтожение обязано быть
// автоматическим: если оно зависит от того, вспомнит ли кто-то нажать кнопку, срок
// хранения снова становится обещанием.
func (e *Enclave) StartSweeper(k *Keyring, every time.Duration, stop <-chan struct{}) {
	go func() {
		t := time.NewTicker(every)
		defer t.Stop()
		if n, err := e.SweepExpired(k, time.Now()); err != nil {
			log.Printf("уничтожение ключей: %v", err)
		} else if n > 0 {
			log.Printf("уничтожено просроченных ключей: %d", n)
		}
		for {
			select {
			case <-stop:
				return
			case now := <-t.C:
				n, err := e.SweepExpired(k, now)
				if err != nil {
					log.Printf("уничтожение ключей: %v", err)
					continue
				}
				if n > 0 {
					log.Printf("уничтожено просроченных ключей: %d", n)
				}
			}
		}
	}()
}
