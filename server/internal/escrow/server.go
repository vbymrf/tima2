// HTTP-API stub-анклава — контракт будущего HSM/Nitro (escrow-legal-access.md §3, §7):
//   GET  /healthz    — liveness
//   GET  /v1/pubkey  — публичный ключ escrow (его же проксирует tima клиентам)
//   POST /v1/unseal  — юридический доступ: k долей Шамира + blobs → ключи;
//                      каждый вызов (включая отказ) — строка в append-only аудите
package escrow

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

func (e *Enclave) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"status":"ok"}`)
	})
	mux.HandleFunc("GET /v1/pubkey", e.handlePubkey)
	mux.HandleFunc("POST /v1/unseal", e.handleUnseal)
}

func (e *Enclave) handlePubkey(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"escrow_key_version": e.version,
		"public_key":         base64.RawURLEncoding.EncodeToString(e.pubKey),
	})
}

func (e *Enclave) handleUnseal(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Shares []string `json:"shares"`
		Reason string   `json:"reason"` // основание (номер запроса/решения) — в аудит
		Blobs  []struct {
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
		e.audit("unseal_denied", req.Reason, len(req.Blobs), r)
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
		key, err := e.unwrap(ct, wrapped)
		if err != nil {
			// Частичный успех недопустим: юридический запрос обрабатывается целиком
			e.audit("unseal_failed", req.Reason, len(req.Blobs), r)
			httpErr(w, http.StatusUnprocessableEntity, fmt.Sprintf("blob %d: %v", i, err))
			return
		}
		keys = append(keys, b64.EncodeToString(key))
	}
	e.audit("unseal_ok", req.Reason, len(req.Blobs), r)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"keys": keys})
}

// audit — append-only JSONL (контента нет — только метаданные доступа,
// escrow-legal-access.md §8). WORM-хранилище — gate фазы 6.
func (e *Enclave) audit(action, reason string, blobs int, r *http.Request) {
	line, _ := json.Marshal(map[string]any{
		"ts":     time.Now().UTC().Format(time.RFC3339),
		"action": action,
		"reason": reason,
		"blobs":  blobs,
		"remote": r.RemoteAddr,
	})
	f, err := os.OpenFile(filepath.Join(e.dir, auditFile), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		log.Printf("audit: %v", err) // аудит не должен молча теряться
		return
	}
	defer f.Close()
	if _, err := f.Write(append(line, '\n')); err != nil {
		log.Printf("audit: %v", err)
	}
}

func httpErr(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
