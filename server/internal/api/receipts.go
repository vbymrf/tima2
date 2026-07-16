// Квитанции прочтения (✓✓) и индикатор «печатает» для личных чатов.
// Оба адресуются устройствам собеседника через ChatHelperDevices (участники чата,
// кроме своих устройств). Квитанция персистится (device_events → доедет офлайну);
// typing эфемерен — публикуется только онлайн (Redis Pub/Sub, event_id=0, без ack).
package api

import (
	"encoding/json"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
)

// chatRead — POST /chats/{chatID}/read {message_id}: собеседник узнаёт, что прочитано до message_id.
func (s *Server) chatRead(w http.ResponseWriter, r *http.Request) {
	id, _ := auth.FromContext(r.Context())
	chatID := r.PathValue("chatID")
	var req struct {
		MessageID uint64 `json:"message_id"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req) != nil || req.MessageID == 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "нужен message_id")
		return
	}
	peers, err := s.Store.ChatHelperDevices(r.Context(), chatID, id.DeviceID, id.UserID)
	if err != nil {
		log.Printf("chatRead: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	for _, p := range peers {
		if p.Own {
			continue // уведомляем устройства собеседника, не свои
		}
		s.notify(r.Context(), p.DeviceID, "receipt.read", map[string]any{
			"chat_id": chatID, "message_id": req.MessageID, "reader_id": id.UserID,
		})
	}
	w.WriteHeader(http.StatusNoContent)
}

// chatTyping — POST /chats/{chatID}/typing: эфемерный сигнал «печатает» собеседнику.
func (s *Server) chatTyping(w http.ResponseWriter, r *http.Request) {
	if s.Events == nil {
		w.WriteHeader(http.StatusNoContent) // без шины typing просто не доставляется
		return
	}
	id, _ := auth.FromContext(r.Context())
	chatID := r.PathValue("chatID")
	peers, err := s.Store.ChatHelperDevices(r.Context(), chatID, id.DeviceID, id.UserID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	for _, p := range peers {
		if p.Own {
			continue
		}
		// event_id=0 → живой кадр без персистенции и без ack (websocket-events.md)
		_ = s.Events.Publish(r.Context(), p.DeviceID, "typing", 0, map[string]any{
			"chat_id": chatID, "user_id": id.UserID,
		})
	}
	w.WriteHeader(http.StatusNoContent)
}
