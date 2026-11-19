// Закреплённые группой (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ12; влито из ПЛАН-(ЗГ)).
//
// Одно сообщение или группа до 20 на переписку, группу, канал; права — как у прежнего `…/pin`: личная — любой из двух,
// группа — модератор и выше, канал — владелец и модератор. Прежние `…/pin` работают поверх того же списка.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"slices"
	"unicode/utf8"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// pinTitleMax — предел названия группы закреплённых (ЗГ-1).
const pinTitleMax = 64

// RegisterPinGroups — `…/pins` у личной переписки, группы, канала.
func RegisterPinGroups(mux *http.ServeMux, st ShowcaseStore, board store.PinBoard, n *Notifier, requireDevice Middleware) {
	for _, k := range []struct{ kind, path string }{
		{store.PinPersonal, "/api/v1/chats/{chatID}/pins"},
		{store.PinGroup, "/api/v1/groups/{groupID}/pins"},
		{store.PinChannel, "/api/v1/channels/{channelID}/pins"},
	} {
		mux.HandleFunc("GET "+k.path, requireDevice(pinsList(st, board, k.kind)))
		mux.HandleFunc("POST "+k.path, requireDevice(pinsAdd(st, board, n, k.kind)))
		mux.HandleFunc("DELETE "+k.path, requireDevice(pinsRemove(st, board, n, k.kind)))
		mux.HandleFunc("PUT "+k.path+"/title", requireDevice(pinsTitle(st, board, n, k.kind)))
	}
}

// pinAccess — контейнер и вправе ли просящий менять закреплённое; ответ об отказе уже написан.
func pinAccess(w http.ResponseWriter, r *http.Request, st ShowcaseStore, kind string) (string, bool, bool) {
	id, _ := auth.FromContext(r.Context())
	switch kind {
	case store.PinPersonal:
		chatID := r.PathValue("chatID")
		ok, err := st.IsChatParticipant(r.Context(), chatID, id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return "", false, false
		}
		if !ok {
			writeErr(w, http.StatusNotFound, "chat_not_found", "переписки нет")
			return "", false, false
		}
		return chatID, true, true
	case store.PinGroup:
		groupID := r.PathValue("groupID")
		role, err := st.GroupRole(r.Context(), groupID, id.UserID)
		if errors.Is(err, store.ErrNotMember) {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return "", false, false
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return "", false, false
		}
		return groupID, roleRank[role] >= rankModerator, true
	default:
		ch, ok := channelForShowcase(w, r, st)
		if !ok {
			return "", false, false
		}
		if ch.OwnerID == id.UserID {
			return ch.ChannelID, true, true
		}
		mods, err := st.ChannelModerators(r.Context(), ch.ChannelID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return "", false, false
		}
		return ch.ChannelID, slices.Contains(mods, id.UserID), true
	}
}

func writePins(w http.ResponseWriter, title string, items []store.PinItem, extra map[string]any) {
	out := make([]map[string]any, 0, len(items))
	for _, it := range items {
		out = append(out, map[string]any{"message_id": it.MessageID, "by": it.By, "pinned_at_unix_ms": it.At.UnixMilli()})
	}
	answer := map[string]any{"title": title, "items": out, "max": store.PinsMax}
	for k, v := range extra {
		answer[k] = v
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(answer)
}

// pinsChanged — событие `message.pinned` со списком: прежнее `message_id` (новейшее) остаётся для установленных
// клиентов; новые поля — `message_ids`, `title`, `change`.
func pinsChanged(r *http.Request, st ShowcaseStore, board store.PinBoard, n *Notifier, kind, container, change string) {
	if n == nil {
		return
	}
	id, _ := auth.FromContext(r.Context())
	title, items, err := board.List(r.Context(), kind, container)
	if err != nil {
		return
	}
	ids := make([]int64, 0, len(items))
	for _, it := range items {
		ids = append(ids, it.MessageID)
	}
	var newest int64
	if len(ids) > 0 {
		newest = ids[0]
	}
	payload := map[string]any{"message_id": newest, "message_ids": ids, "title": title, "change": change, "by": id.UserID}
	switch kind {
	case store.PinPersonal:
		payload["chat_id"] = container
		if users, err := st.ChatUsers(r.Context(), container); err == nil {
			n.Users(r.Context(), users, "message.pinned", payload)
		}
	case store.PinGroup:
		payload["group_id"] = container
		if devices, err := st.ActiveMemberDevices(r.Context(), container, ""); err == nil {
			for _, dev := range devices {
				n.Device(r.Context(), dev, "message.pinned", payload)
			}
		}
	}
}

func pinsList(st ShowcaseStore, board store.PinBoard, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		container, _, ok := pinAccess(w, r, st, kind)
		if !ok {
			return
		}
		title, items, err := board.List(r.Context(), kind, container)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writePins(w, title, items, nil)
	}
}

// pinsAdd — POST …/pins {message_ids, mode: add|replace}.
func pinsAdd(st ShowcaseStore, board store.PinBoard, n *Notifier, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		container, may, ok := pinAccess(w, r, st, kind)
		if !ok {
			return
		}
		if !may {
			writeErr(w, http.StatusForbidden, "not_moderator", "закрепляет модератор и выше")
			return
		}
		var req struct {
			MessageIDs []int64 `json:"message_ids"`
			Mode       string  `json:"mode"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&req); err != nil || len(req.MessageIDs) == 0 || (req.Mode != "" && req.Mode != "add" && req.Mode != "replace") {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны message_ids и mode: add|replace")
			return
		}
		id, _ := auth.FromContext(r.Context())
		already, err := board.Add(r.Context(), kind, container, req.MessageIDs, req.Mode == "replace", id.UserID)
		switch {
		case errors.Is(err, store.ErrPinsFull):
			writeErr(w, http.StatusConflict, "pins_full", "закреплено 20 — уберите лишние")
			return
		case errors.Is(err, store.ErrMessageGone):
			writeErr(w, http.StatusConflict, "message_not_pinnable", "закрепить можно живое сообщение этой переписки")
			return
		case err != nil:
			log.Printf("pinsAdd %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		change := "added"
		if req.Mode == "replace" {
			change = "replaced"
		}
		pinsChanged(r, st, board, n, kind, container, change)
		title, items, _ := board.List(r.Context(), kind, container)
		if already == nil {
			already = []int64{}
		}
		writePins(w, title, items, map[string]any{"already": already})
	}
}

// pinsRemove — DELETE …/pins {message_ids?}: выбранные; без списка — все.
func pinsRemove(st ShowcaseStore, board store.PinBoard, n *Notifier, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		container, may, ok := pinAccess(w, r, st, kind)
		if !ok {
			return
		}
		if !may {
			writeErr(w, http.StatusForbidden, "not_moderator", "открепляет модератор и выше")
			return
		}
		var req struct {
			MessageIDs []int64 `json:"message_ids"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&req)
		removed, err := board.Remove(r.Context(), kind, container, req.MessageIDs)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if removed > 0 {
			change := "removed"
			if len(req.MessageIDs) == 0 {
				change = "cleared"
			}
			pinsChanged(r, st, board, n, kind, container, change)
		}
		title, items, _ := board.List(r.Context(), kind, container)
		writePins(w, title, items, map[string]any{"removed": removed})
	}
}

// pinsTitle — PUT …/pins/title {title}: до 64 знаков; пусто — «Группа сообщений» на клиенте.
func pinsTitle(st ShowcaseStore, board store.PinBoard, n *Notifier, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		container, may, ok := pinAccess(w, r, st, kind)
		if !ok {
			return
		}
		if !may {
			writeErr(w, http.StatusForbidden, "not_moderator", "название задаёт тот, кто закрепляет")
			return
		}
		var req struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 2<<10)).Decode(&req); err != nil || utf8.RuneCountInString(req.Title) > pinTitleMax {
			writeErr(w, http.StatusBadRequest, "bad_title", "название — до 64 знаков")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if err := board.Rename(r.Context(), kind, container, req.Title, id.UserID); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		pinsChanged(r, st, board, n, kind, container, "renamed")
		title, items, _ := board.List(r.Context(), kind, container)
		writePins(w, title, items, nil)
	}
}
