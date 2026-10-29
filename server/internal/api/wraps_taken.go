package api

import (
	"encoding/json"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
)

// wrapsTaken — POST /chats/{chatID}/keys/taken: устройство развернуло ключи этих сообщений и держит их у
// себя (план КК, КК9–КК10). Обёртки под него становятся «забранными» и стираются через запас. Отмечается
// только обёртка под само устройство — чужой отметить нельзя.
func wrapsTaken(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		chatID := r.PathValue("chatID")
		id, _ := auth.FromContext(r.Context())
		var req struct {
			MessageIDs []int64 `json:"message_ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil || len(req.MessageIDs) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен message_ids")
			return
		}
		if len(req.MessageIDs) > 1000 {
			writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "не больше 1000 номеров за раз")
			return
		}
		n, err := deps.store.MarkWrapsTaken(r.Context(), chatID, id.DeviceID, req.MessageIDs)
		if err != nil {
			log.Printf("wrapsTaken: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"taken": n})
	}
}

// untakenWraps — GET /keys/untaken: сколько обёрток под это устройство не забрано (КК10). Пока не ноль,
// устройство не уничтожает ключ прошлой эпохи.
func untakenWraps(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		n, err := deps.store.UntakenWraps(r.Context(), id.DeviceID)
		if err != nil {
			log.Printf("untakenWraps: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"count": n})
	}
}
