// Публичный ключ escrow для клиентов (crypto-protocol.md §6): tima проксирует
// GET /v1/pubkey stub-анклава (cmd/escrow-stub) с кэшем — сам анклав наружу
// не торчит, а к приватному ключу у tima доступа нет вовсе.
package api

import (
	"encoding/json"
	"log"
	"net/http"
	"time"
)

const escrowCacheTTL = 10 * time.Minute

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
		}
		if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&got) != nil || got.PublicKey == "" {
			log.Printf("escrowPubkey: статус %d", resp.StatusCode)
			writeErr(w, http.StatusBadGateway, "escrow_bad_response", "escrow-анклав ответил некорректно")
			return
		}
		raw, _ := json.Marshal(map[string]any{
			"escrow_key_version": got.Version, "public_key": got.PublicKey,
		})
		s.escrowMu.Lock()
		s.escrowCached, s.escrowFetched = raw, time.Now()
		cached = raw
		s.escrowMu.Unlock()
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(cached)
}
