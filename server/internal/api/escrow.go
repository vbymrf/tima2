// Публичный ключ escrow для клиентов (crypto-protocol.md §6): tima проксирует
// GET /v1/pubkey stub-анклава (cmd/escrow-stub) с кэшем — сам анклав наружу
// не торчит, а к приватному ключу у tima доступа нет вовсе.
package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"regexp"
	"time"

	"tima/server/internal/escrow"
	"tima/server/internal/store"
)

// uuidRe — chat_id уходит в путь файла внутри анклава, поэтому форма проверяется
// строго, а не «на глаз».
var uuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

const escrowCacheTTL = 10 * time.Minute

// escrowOverlapDefault — за сколько до конца эпохи начинаем отдавать клиенту ещё и
// СЛЕДУЮЩИЙ ключ. Без перекрытия клиент с закэшированным конфигом в момент смены
// эпохи попытается зашифровать на ключ, окно которого уже закрылось, и при
// fail-closed просто не сможет отправить сообщение.
const escrowOverlapDefault = 7 * 24 * time.Hour

// escrowPubkey — GET /api/v1/escrow/pubkey → {escrow_key_version, public_key}.
func (s *Server) escrowPubkey(w http.ResponseWriter, r *http.Request) {
	if s.EscrowURL == "" {
		writeErr(w, http.StatusServiceUnavailable, "no_escrow", "escrow-анклав не сконфигурирован (ESCROW_URL)")
		return
	}
	s.escrowMu.Lock()
	cached, fresh := s.escrowCached, time.Since(s.escrowFetched) < escrowCacheTTL
	s.escrowMu.Unlock()
	if !fresh {
		client := http.Client{Timeout: 5 * time.Second}
		resp, err := client.Get(s.EscrowURL + "/v1/pubkey")
		if err != nil {
			log.Printf("escrowPubkey: %v", err)
			writeErr(w, http.StatusBadGateway, "escrow_unreachable", "escrow-анклав недоступен")
			return
		}
		defer resp.Body.Close()
		var got struct {
			Version   int    `json:"escrow_key_version"`
			PublicKey string `json:"public_key"`
			Signature string `json:"signature"` // подпись анклава Ed25519 (Р2) — проверяет клиент
		}
		if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&got) != nil ||
			got.PublicKey == "" || got.Signature == "" {
			log.Printf("escrowPubkey: статус %d", resp.StatusCode)
			writeErr(w, http.StatusBadGateway, "escrow_bad_response", "escrow-анклав ответил некорректно")
			return
		}
		raw, _ := json.Marshal(map[string]any{
			"escrow_key_version": got.Version, "public_key": got.PublicKey, "signature": got.Signature,
		})
		s.escrowMu.Lock()
		s.escrowCached, s.escrowFetched = raw, time.Now()
		cached = raw
		s.escrowMu.Unlock()
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(cached)
}

// escrowKeyForChat — GET /api/v1/escrow/key?chat_id=…
//
// Отдаёт ключ escrow для текущей эпохи чата и, у границы эпохи, для следующей.
// Ключ на пару «чат × эпоха»: утечка одного вскрывает один чат за один месяц, а
// ордер на «чат за период» разворачивается ровно в те ключи, что в него попали.
//
// Ключ создаётся анклавом лениво — при первом обращении. Молчащие чаты ключей не
// имеют вовсе.
func (s *Server) escrowKeyForChat(w http.ResponseWriter, r *http.Request) {
	if s.EscrowURL == "" {
		writeErr(w, http.StatusServiceUnavailable, "no_escrow", "escrow-анклав не сконфигурирован (ESCROW_URL)")
		return
	}
	chatID := r.URL.Query().Get("chat_id")
	if !uuidRe.MatchString(chatID) {
		writeErr(w, http.StatusBadRequest, "bad_chat_id", "нужен chat_id (UUID)")
		return
	}
	now := time.Now().UTC()
	region := s.EscrowRegionEffective()

	current, err := s.escrowKey(r.Context(), region, escrow.EpochOf(now), chatID)
	if err != nil {
		log.Printf("escrowKeyForChat: %v", err)
		writeErr(w, http.StatusBadGateway, "escrow_unreachable", "escrow-анклав недоступен")
		return
	}
	out := map[string]any{"region": region, "current": current}

	// У границы эпохи добавляем следующий ключ. Раньше этого момента не создаём:
	// иначе ленивая генерация превратилась бы в создание ключей всем чатам заранее.
	if now.Add(s.escrowOverlap()).After(current.ValidTo) {
		next, err := s.escrowKey(r.Context(), region, escrow.EpochOf(current.ValidTo), chatID)
		if err != nil {
			log.Printf("escrowKeyForChat next: %v", err) // не фатально: текущий ключ уже есть
		} else {
			out["next"] = next
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(out)
}

// escrowKeyView — то, что видит клиент. Приватной части здесь нет и быть не может.
//
// DestroyAt — не приватность, а материал подписи (Р2): клиент проверяет подпись
// анклава над KeyMetaSigningBytes, а туда входит destroy_at. Без этого поля
// проверить подпись было бы нечем — пришлось бы либо не подписывать destroy_at
// (и тогда его можно подменить в обход подписи), либо не отдавать его клиенту
// вовсе (тогда клиент не может проверить остальное).
type escrowKeyView struct {
	ID        uint32    `json:"id"` // → EscrowBlob.escrow_key_version
	Epoch     string    `json:"epoch"`
	PublicKey string    `json:"public_key"` // base64url, ML-KEM-768
	Signature string    `json:"signature"`  // base64url, подпись анклава Ed25519 (Р2)
	ValidFrom time.Time `json:"valid_from"`
	ValidTo   time.Time `json:"valid_to"`
	DestroyAt time.Time `json:"destroy_at"`
}

// escrowKey — ключ области из кэша, иначе у анклава (и в кэш).
func (s *Server) escrowKey(ctx context.Context, region, epoch, chatID string) (escrowKeyView, error) {
	if k, err := s.Store.FindEscrowKey(ctx, region, epoch, chatID); err == nil {
		return escrowKeyView{
			ID: k.ID, Epoch: k.Epoch,
			PublicKey: base64.RawURLEncoding.EncodeToString(k.PublicKey),
			Signature: base64.RawURLEncoding.EncodeToString(k.Signature),
			ValidFrom: k.ValidFrom, ValidTo: k.ValidTo, DestroyAt: k.DestroyAt,
		}, nil
	} else if !errors.Is(err, store.ErrEscrowKeyUnknown) {
		return escrowKeyView{}, err
	}

	body, _ := json.Marshal(map[string]string{"region": region, "epoch": epoch, "chat_id": chatID})
	client := http.Client{Timeout: 5 * time.Second}
	resp, err := client.Post(s.EscrowURL+"/v1/key", "application/json", bytes.NewReader(body))
	if err != nil {
		return escrowKeyView{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return escrowKeyView{}, fmt.Errorf("анклав ответил %d", resp.StatusCode)
	}
	// Подпись летит в том же JSON, что и KeyMeta (signedKeyMeta на стороне
	// анклава), но escrow.KeyMeta о подписи не знает — decode в локальную
	// обёртку, а не расширяет тип, который переиспользует и Keyring.
	var meta struct {
		escrow.KeyMeta
		Signature string `json:"signature"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&meta); err != nil {
		return escrowKeyView{}, err
	}
	pub, err := base64.RawURLEncoding.DecodeString(meta.PublicKey)
	if err != nil {
		return escrowKeyView{}, err
	}
	sig, err := base64.RawURLEncoding.DecodeString(meta.Signature)
	if err != nil || len(sig) == 0 {
		return escrowKeyView{}, fmt.Errorf("анклав не подписал ключ")
	}
	if err := s.Store.SaveEscrowKey(ctx, store.EscrowKey{
		ID: meta.ID, Region: meta.Region, Epoch: meta.Epoch, ChatID: meta.ChatID,
		PublicKey: pub, Signature: sig, ValidFrom: meta.ValidFrom, ValidTo: meta.ValidTo, DestroyAt: meta.DestroyAt,
	}); err != nil {
		return escrowKeyView{}, err
	}
	return escrowKeyView{
		ID: meta.ID, Epoch: meta.Epoch, PublicKey: meta.PublicKey, Signature: meta.Signature,
		ValidFrom: meta.ValidFrom, ValidTo: meta.ValidTo, DestroyAt: meta.DestroyAt,
	}, nil
}

// EscrowRegionEffective — регион, который реально используется (с учётом умолчания).
// Экспортирован, чтобы старт печатал в лог действующее значение, а не переменную.
func (s *Server) EscrowRegionEffective() string {
	if s.EscrowRegion != "" {
		return s.EscrowRegion
	}
	return "ru"
}

func (s *Server) escrowOverlap() time.Duration {
	if s.EscrowOverlap > 0 {
		return s.EscrowOverlap
	}
	return escrowOverlapDefault
}
