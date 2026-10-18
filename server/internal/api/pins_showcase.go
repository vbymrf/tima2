// Закреплённое сообщение и описание (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО1–ЗО2).
//
// ── ЗАКРЕПЛЕНИЕ ─────────────────────────────────────────────────────────────
//
// Одно сообщение на личную переписку, группу, канал. Личная — любой из двух закрепляет,
// меняет, снимает; группа и канал — модератор и выше (заказчик 2026-10-18). Сервер знает номер
// открыто. Получившие узнают событием `message.pinned` (message_id 0 — снято) и показывают
// служебную строку без уведомления. У канала события нет, как нет его у удаления записи: лента
// канала забирается при открытии. Удалённое закреплённое открепляется в самом удалении, а
// клиенты узнают об этом по `message.deleted`.
//
// ── ОПИСАНИЕ ────────────────────────────────────────────────────────────────
//
// Сообщения уровня 0 есть у каждой сущности — группы, канала, сообщества. Не больше 50;
// забираются только окном «Описание», от старых к новым; у каждой сущности место под одно
// суперсообщение, ставит его владелец.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"slices"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// ShowcaseStore — что закреплению и описанию нужно от хранилища.
type ShowcaseStore interface {
	GetGroup(ctx context.Context, groupID string) (store.Group, error)
	GroupRole(ctx context.Context, groupID, userID string) (string, error)
	GroupCardOpenTo(ctx context.Context, groupID, userID string) (bool, error)
	ActiveMemberDevices(ctx context.Context, groupID, exceptDevice string) ([]string, error)
	GetChannel(ctx context.Context, channelID string) (store.Channel, error)
	IsSubscribed(ctx context.Context, channelID, userID string) (bool, error)
	ChannelModerators(ctx context.Context, channelID string) ([]string, error)
	CommunityMessages(ctx context.Context, communityID string, limit int) ([]store.CommunityMessage, error)
	IsChatParticipant(ctx context.Context, chatID, userID string) (bool, error)
	ChatUsers(ctx context.Context, chatID string) ([]string, error)

	SetPin(ctx context.Context, kind, container string, messageID int64, actorID string) error
	ClearPin(ctx context.Context, kind, container string) (bool, error)
	PinOf(ctx context.Context, kind, container string) (store.Pin, error)

	ShowcaseCount(ctx context.Context, kind, container string) (int, error)
	ShowcaseSuper(ctx context.Context, kind, container string) (int64, error)
	SetShowcaseSuper(ctx context.Context, kind, container string, messageID int64, actorID string) error
	GroupShowcase(ctx context.Context, groupID string) ([]store.GroupMessage, error)
	ChannelShowcase(ctx context.Context, channelID string) ([]store.ChannelPost, error)
}

var _ ShowcaseStore = (*store.Store)(nil)

// RegisterShowcase — закрепление у переписки, группы, канала; описание и суперсообщение у
// группы, канала, сообщества.
func RegisterShowcase(mux *http.ServeMux, st ShowcaseStore, n *Notifier, requireDevice Middleware) {
	for _, m := range []string{"GET", "PUT", "DELETE"} {
		mux.HandleFunc(m+" /api/v1/chats/{chatID}/pin", requireDevice(chatPin(st, n)))
		mux.HandleFunc(m+" /api/v1/groups/{groupID}/pin", requireDevice(groupPin(st, n)))
		mux.HandleFunc(m+" /api/v1/channels/{channelID}/pin", requireDevice(channelPin(st)))
	}
	mux.HandleFunc("GET /api/v1/groups/{groupID}/showcase", requireDevice(groupShowcase(st)))
	mux.HandleFunc("GET /api/v1/channels/{channelID}/showcase", requireDevice(channelShowcase(st)))
	mux.HandleFunc("GET /api/v1/communities/{communityID}/showcase", requireDevice(communityShowcase(st)))
	for _, m := range []string{"PUT", "DELETE"} {
		mux.HandleFunc(m+" /api/v1/groups/{groupID}/showcase/super", requireDevice(showcaseSuper(st, store.PinGroup, "groupID")))
		mux.HandleFunc(m+" /api/v1/channels/{channelID}/showcase/super", requireDevice(showcaseSuper(st, store.PinChannel, "channelID")))
		mux.HandleFunc(m+" /api/v1/communities/{communityID}/showcase/super", requireDevice(showcaseSuper(st, store.ShowcaseCommunity, "communityID")))
	}
}

// showcaseFull — 409 `showcase_full`, если у сущности уже 50 сообщений уровня 0. true — ответ
// написан. Ошибка счёта отправку не останавливает: предел — порядок, а не защита данных.
func showcaseFull(w http.ResponseWriter, r *http.Request, st interface {
	ShowcaseCount(ctx context.Context, kind, container string) (int, error)
}, kind, container string) bool {
	n, err := st.ShowcaseCount(r.Context(), kind, container)
	if err != nil {
		log.Printf("showcaseFull %s %s: %v", kind, container, err)
		return false
	}
	if n >= store.ShowcaseLimit {
		writeErr(w, http.StatusConflict, "showcase_full", "у описания уже 50 сообщений уровня 0")
		return true
	}
	return false
}

// pinRequest — тело PUT …/pin.
func pinRequest(w http.ResponseWriter, r *http.Request) (int64, bool) {
	var req struct {
		MessageID int64 `json:"message_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.MessageID <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_message_id", "нужен message_id > 0")
		return 0, false
	}
	return req.MessageID, true
}

// pinChange — общая часть PUT/DELETE/GET: меняет закрепление и отвечает. Возвращает номер после
// изменения и true, если оно было (событие слать есть о чём).
func pinChange(w http.ResponseWriter, r *http.Request, st ShowcaseStore, kind, container string) (int64, bool) {
	id, _ := auth.FromContext(r.Context())
	switch r.Method {
	case http.MethodPut:
		n, ok := pinRequest(w, r)
		if !ok {
			return 0, false
		}
		if err := st.SetPin(r.Context(), kind, container, n, id.UserID); errors.Is(err, store.ErrMessageGone) {
			writeErr(w, http.StatusConflict, "message_not_pinnable", "закрепить можно живое сообщение этой переписки")
			return 0, false
		} else if err != nil {
			log.Printf("pin %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return 0, false
		}
		writePin(w, store.Pin{MessageID: n, By: id.UserID})
		return n, true
	case http.MethodDelete:
		had, err := st.ClearPin(r.Context(), kind, container)
		if err != nil {
			log.Printf("unpin %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return 0, false
		}
		writePin(w, store.Pin{})
		return 0, had
	default:
		p, err := st.PinOf(r.Context(), kind, container)
		if err != nil {
			log.Printf("pin of %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return 0, false
		}
		writePin(w, p)
		return 0, false
	}
}

func writePin(w http.ResponseWriter, p store.Pin) {
	out := map[string]any{"message_id": p.MessageID}
	if p.MessageID != 0 {
		out["by"] = p.By
		if !p.At.IsZero() {
			out["pinned_at_unix_ms"] = p.At.UnixMilli()
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(out)
}

// chatPin — GET/PUT/DELETE /chats/{chatID}/pin: любой из участников переписки.
func chatPin(st ShowcaseStore, n *Notifier) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		chatID := r.PathValue("chatID")
		id, _ := auth.FromContext(r.Context())
		if ok, err := st.IsChatParticipant(r.Context(), chatID, id.UserID); err != nil {
			log.Printf("chatPin: participant: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if !ok {
			writeErr(w, http.StatusNotFound, "chat_not_found", "переписки нет")
			return
		}
		number, changed := pinChange(w, r, st, store.PinPersonal, chatID)
		if !changed {
			return
		}
		users, err := st.ChatUsers(r.Context(), chatID)
		if err != nil {
			log.Printf("chatPin: users: %v", err)
			return
		}
		n.Users(r.Context(), users, "message.pinned", map[string]any{
			"chat_id": chatID, "message_id": number, "by": id.UserID,
		})
	}
}

// groupPin — GET участнику; PUT/DELETE — модератору и выше.
func groupPin(st ShowcaseStore, n *Notifier) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())
		role, err := st.GroupRole(r.Context(), groupID, id.UserID)
		if errors.Is(err, store.ErrNotMember) {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		} else if err != nil {
			log.Printf("groupPin: role: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if r.Method != http.MethodGet && roleRank[role] < rankModerator {
			writeErr(w, http.StatusForbidden, "not_moderator", "закрепляет модератор и выше")
			return
		}
		number, changed := pinChange(w, r, st, store.PinGroup, groupID)
		if !changed {
			return
		}
		devices, err := st.ActiveMemberDevices(r.Context(), groupID, "")
		if err != nil {
			log.Printf("groupPin: devices: %v", err)
			return
		}
		for _, dev := range devices {
			n.Device(r.Context(), dev, "message.pinned", map[string]any{
				"group_id": groupID, "message_id": number, "by": id.UserID,
			})
		}
	}
}

// channelPin — GET читающему ленту; PUT/DELETE — владельцу и модератору канала.
func channelPin(st ShowcaseStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ch, ok := channelForShowcase(w, r, st)
		if !ok {
			return
		}
		id, _ := auth.FromContext(r.Context())
		if r.Method != http.MethodGet && ch.OwnerID != id.UserID {
			mods, err := st.ChannelModerators(r.Context(), ch.ChannelID)
			if err != nil {
				log.Printf("channelPin: moderators: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if !slices.Contains(mods, id.UserID) {
				writeErr(w, http.StatusForbidden, "not_moderator", "закрепляет владелец или модератор канала")
				return
			}
		}
		pinChange(w, r, st, store.PinChannel, ch.ChannelID)
	}
}

// channelForShowcase — канал, ленту которого просящий читает: публичный — всем, приватный —
// подписчику и владельцу, как у ленты.
func channelForShowcase(w http.ResponseWriter, r *http.Request, st ShowcaseStore) (store.Channel, bool) {
	ch, err := st.GetChannel(r.Context(), r.PathValue("channelID"))
	if errors.Is(err, store.ErrChannelNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "канал не найден")
		return ch, false
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return ch, false
	}
	id, _ := auth.FromContext(r.Context())
	if !ch.IsPublic && ch.OwnerID != id.UserID {
		if sub, _ := st.IsSubscribed(r.Context(), ch.ChannelID, id.UserID); !sub {
			writeErr(w, http.StatusForbidden, "not_subscribed", "лента приватного канала — для подписчиков")
			return ch, false
		}
	}
	return ch, true
}

// groupShowcase — GET /groups/{groupID}/showcase: участнику и тому, кому открыта карточка.
func groupShowcase(st ShowcaseStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID := r.PathValue("groupID")
		g, err := st.GetGroup(r.Context(), groupID)
		if errors.Is(err, store.ErrGroupNotFound) {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		} else if err != nil {
			log.Printf("groupShowcase: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if _, err := st.GroupRole(r.Context(), groupID, id.UserID); errors.Is(err, store.ErrNotMember) {
			open := g.Kind == "public"
			if !open {
				if open, err = st.GroupCardOpenTo(r.Context(), groupID, id.UserID); err != nil {
					log.Printf("groupShowcase: card: %v", err)
					writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
					return
				}
			}
			if !open {
				writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
				return
			}
		} else if err != nil {
			log.Printf("groupShowcase: role: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		msgs, err := st.GroupShowcase(r.Context(), groupID)
		if err != nil {
			log.Printf("groupShowcase: list: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(msgs))
		for _, m := range msgs {
			out = append(out, groupMessageJSON(m))
		}
		writeShowcase(w, r, st, store.PinGroup, groupID, "messages", out)
	}
}

// channelShowcase — GET /channels/{channelID}/showcase.
func channelShowcase(st ShowcaseStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ch, ok := channelForShowcase(w, r, st)
		if !ok {
			return
		}
		posts, err := st.ChannelShowcase(r.Context(), ch.ChannelID)
		if err != nil {
			log.Printf("channelShowcase: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(posts))
		for _, p := range posts {
			out = append(out, channelPostItem(p, 0))
		}
		writeShowcase(w, r, st, store.PinChannel, ch.ChannelID, "posts", out)
	}
}

// communityShowcase — GET /communities/{communityID}/showcase: всё описание сообщества и его
// суперсообщение. Читает каждый, как страницу сообщества.
func communityShowcase(st ShowcaseStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		communityID := r.PathValue("communityID")
		msgs, err := st.CommunityMessages(r.Context(), communityID, store.ShowcaseLimit)
		if errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		} else if err != nil {
			log.Printf("communityShowcase: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writeShowcase(w, r, st, store.ShowcaseCommunity, communityID, "messages", messagesJSON(msgs))
	}
}

func writeShowcase(w http.ResponseWriter, r *http.Request, st ShowcaseStore, kind, container, key string, items []map[string]any) {
	super, err := st.ShowcaseSuper(r.Context(), kind, container)
	if err != nil {
		// Без суперсообщения описание всё равно показывается — списком.
		log.Printf("showcase super %s: %v", kind, err)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"super": super, key: items, "limit": store.ShowcaseLimit})
}

// showcaseSuper — PUT {message_id} ставит суперсообщение, DELETE снимает. Только владелец.
func showcaseSuper(st ShowcaseStore, kind, pathKey string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var number int64
		if r.Method == http.MethodPut {
			n, ok := pinRequest(w, r)
			if !ok {
				return
			}
			number = n
		}
		id, _ := auth.FromContext(r.Context())
		err := st.SetShowcaseSuper(r.Context(), kind, r.PathValue(pathKey), number, id.UserID)
		switch {
		case errors.Is(err, store.ErrShowcaseNotOwner):
			writeErr(w, http.StatusForbidden, "not_owner", "суперсообщение ставит владелец")
			return
		case errors.Is(err, store.ErrMessageGone):
			writeErr(w, http.StatusConflict, "not_showcase", "суперсообщение — живое сообщение уровня 0 этой сущности")
			return
		case err != nil:
			log.Printf("showcaseSuper %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"super": number})
	}
}
