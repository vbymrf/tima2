// Удаление сообщения у всех (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §8; ПЛАН-(РС) РС3): личное — только
// автор, в группе — ещё модератор и админ. Получившие узнают событием `message.deleted` и
// показывают строку «Сообщение удалено». Вернуть нельзя. Правки уходят вместе с исходным.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// deleteRefusal — отказ хранилища ответом. true — ответ уже написан.
func deleteRefusal(w http.ResponseWriter, err error, where string) bool {
	switch {
	case err == nil:
		return false
	case errors.Is(err, store.ErrMessageGone):
		writeErr(w, http.StatusNotFound, "message_not_found", "сообщения нет или оно уже удалено")
	case errors.Is(err, store.ErrGroupMessageForeign):
		writeErr(w, http.StatusForbidden, "not_allowed", "удалить у всех может автор (в группе — ещё модератор и админ)")
	default:
		log.Printf("%s: %v", where, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
	}
	return true
}

func messageNumber(w http.ResponseWriter, r *http.Request) (int64, bool) {
	n, err := strconv.ParseInt(r.PathValue("messageID"), 10, 64)
	if err != nil || n <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_message_id", "message_id — целое число")
		return 0, false
	}
	return n, true
}

// deletePersonalMessage — DELETE /chats/{chatID}/messages/{messageID}.
func deletePersonalMessage(deps messagesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		n, ok := messageNumber(w, r)
		if !ok {
			return
		}
		id, _ := auth.FromContext(r.Context())
		chatID := r.PathValue("chatID")
		devices, err := deps.store.DeleteMessage(r.Context(), false, chatID, n, id.UserID, false)
		if deleteRefusal(w, err, "deletePersonalMessage") {
			return
		}
		// Всем, у кого есть ключ сообщения, кроме удалившего устройства: оно знает само.
		for _, dev := range devices {
			if dev == id.DeviceID {
				continue
			}
			deps.notifier.Device(r.Context(), dev, "message.deleted", map[string]any{
				"chat_id": chatID, "message_id": n, "by": id.UserID,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": n, "deleted": true})
	}
}

// deleteGroupMessage — DELETE /groups/{groupID}/messages/{messageID}.
func deleteGroupMessage(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if role == "" {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		}
		n, ok := messageNumber(w, r)
		if !ok {
			return
		}
		id, _ := auth.FromContext(r.Context())
		groupID := r.PathValue("groupID")
		_, err := deps.store.DeleteMessage(r.Context(), true, groupID, n, id.UserID, roleRank[role] >= rankModerator)
		if deleteRefusal(w, err, "deleteGroupMessage") {
			return
		}
		devices, err := deps.store.ActiveMemberDevices(r.Context(), groupID, id.DeviceID)
		if err != nil {
			log.Printf("deleteGroupMessage: member devices: %v", err)
		}
		for _, dev := range devices {
			deps.notifier.Device(r.Context(), dev, "message.deleted", map[string]any{
				"group_id": groupID, "message_id": n, "by": id.UserID,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": n, "deleted": true})
	}
}
