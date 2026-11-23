// Сообщества: контейнер, который связывает готовое (ПЛАН-(СО)-СООБЩЕСТВ С1…С3).
//
// ── ЧТО ЗДЕСЬ ЕСТЬ ──────────────────────────────────────────────────────────
//
// Создание, каталог, страница, состав, связывание и отвязывание, подписка, роли и
// описание — сообщения уровня 0.
//
// ── ЧЕГО ЗДЕСЬ НЕТ, И ЭТО НЕ ЗАБЫВЧИВОСТЬ ───────────────────────────────────
//
// **Ни одной строки про содержимое групп и каналов.** Связывание — это одна ссылка;
// переписка, участники и ключи остаются там, где лежали. Если в этом файле однажды
// появится перенос сообщений, значит замысел сообщества потеряли.
//
// **Звукового чата нет** (решение заказчика 2026-09-08): он ждёт собственной реализации,
// и до неё сообщество о нём ничего не знает — ни колонки, ни вида элемента.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// CommunityStore — что сообществам нужно от хранилища.
type CommunityStore interface {
	CreateCommunity(ctx context.Context, c store.Community) (string, error)
	GetCommunity(ctx context.Context, communityID string) (store.Community, error)
	MyCommunities(ctx context.Context, userID string) ([]store.CommunityView, error)
	DiscoverCommunities(ctx context.Context, userID string, limit int) ([]store.CommunityView, error)
	CommunityItems(ctx context.Context, communityID string) ([]store.CommunityItem, error)
	LinkItem(ctx context.Context, communityID, kind, itemID, actorID string) error
	UnlinkItem(ctx context.Context, communityID, kind, itemID, actorID string) error
	SubscribeCommunity(ctx context.Context, communityID, userID string, on bool) error
	IsSubscribedToCommunity(ctx context.Context, communityID, userID string) (bool, error)
	SetCommunityAdmin(ctx context.Context, communityID, ownerID, userID string, on bool) error
	CommunityAdmins(ctx context.Context, communityID string) ([]string, error)
	AddCommunityMessage(ctx context.Context, m store.CommunityMessage, actorID string) (int64, error)
	CommunityMessages(ctx context.Context, communityID string, limit int) ([]store.CommunityMessage, error)
	ShowcaseCount(ctx context.Context, kind, container string) (int, error)
	ShowcaseSuper(ctx context.Context, kind, container string) (int64, error)
	// Секретное сообщество открыто подписчику, владельцу и приглашённому (ПЛАН-(СЦ) СЦ2).
	HasPendingInvite(ctx context.Context, kind, entityID, userID string) (bool, error)
}

var _ CommunityStore = (*store.Store)(nil)

// RegisterCommunities — все маршруты сообществ одним вызовом.
func RegisterCommunities(mux *http.ServeMux, st CommunityStore, requireDevice Middleware) {
	mux.HandleFunc("POST /api/v1/communities", requireDevice(createCommunity(st)))
	mux.HandleFunc("GET /api/v1/communities", requireDevice(listMyCommunities(st)))
	mux.HandleFunc("GET /api/v1/communities/discover", requireDevice(discoverCommunities(st)))
	mux.HandleFunc("GET /api/v1/communities/{communityID}", requireDevice(communityPage(st)))

	mux.HandleFunc("POST /api/v1/communities/{communityID}/items", requireDevice(linkItem(st)))
	mux.HandleFunc("DELETE /api/v1/communities/{communityID}/items/{kind}/{itemID}", requireDevice(unlinkItem(st)))

	mux.HandleFunc("POST /api/v1/communities/{communityID}/subscribe", requireDevice(subscribeCommunity(st, true)))
	mux.HandleFunc("DELETE /api/v1/communities/{communityID}/subscribe", requireDevice(subscribeCommunity(st, false)))

	mux.HandleFunc("GET /api/v1/communities/{communityID}/admins", requireDevice(communityAdmins(st)))
	mux.HandleFunc("POST /api/v1/communities/{communityID}/admins", requireDevice(setCommunityAdmin(st, true)))
	mux.HandleFunc("DELETE /api/v1/communities/{communityID}/admins/{userID}", requireDevice(setCommunityAdmin(st, false)))

	// Описание — сообщения уровня 0. Отдельного маршрута «описание» нет: это обычные
	// сообщения, и второй способ их положить означал бы второе представление описания.
	mux.HandleFunc("GET /api/v1/communities/{communityID}/messages", requireDevice(communityMessages(st)))
	mux.HandleFunc("POST /api/v1/communities/{communityID}/messages", requireDevice(addCommunityMessage(st)))
}

func communityJSON(v store.CommunityView) map[string]any {
	return map[string]any{
		"community_id": v.CommunityID, "title": v.Title, "owner_id": v.OwnerID,
		"is_public": v.IsPublic, "subscribed": v.Subscribed, "owner": v.Owner, "admin": v.Admin,
		// Секретное (ПЛАН-(СЦ) СЦ2).
		"hidden": v.Hidden,
	}
}

func createCommunity(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Title    string `json:"title"`
			IsPublic *bool  `json:"is_public,omitempty"`
			// Секретное (ПЛАН-(СЦ) СЦ2): не в каталоге, вход по приглашению.
			Hidden bool `json:"hidden"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.Title == "" || len(req.Title) > 200 {
			writeErr(w, http.StatusBadRequest, "bad_title", "title обязателен, до 200 байт")
			return
		}
		public := true
		if req.IsPublic != nil {
			public = *req.IsPublic
		}
		id, _ := auth.FromContext(r.Context())
		communityID, err := st.CreateCommunity(r.Context(), store.Community{
			Title: req.Title, OwnerID: id.UserID, IsPublic: public, Hidden: req.Hidden,
		})
		if err != nil {
			log.Printf("createCommunity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"community_id": communityID})
	}
}

func listMyCommunities(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		list, err := st.MyCommunities(r.Context(), id.UserID)
		if err != nil {
			log.Printf("listMyCommunities: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writeCommunities(w, list)
	}
}

func discoverCommunities(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		list, err := st.DiscoverCommunities(r.Context(), id.UserID, limit)
		if err != nil {
			log.Printf("discoverCommunities: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writeCommunities(w, list)
	}
}

func writeCommunities(w http.ResponseWriter, list []store.CommunityView) {
	out := make([]map[string]any, 0, len(list))
	for _, v := range list {
		out = append(out, communityJSON(v))
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"communities": out})
}

// communityPage — GET /communities/{id}: название, описание, состав по правам просящего.
//
// **Личные группы в списке не показываются никому, кроме владельца сообщества и его
// админов** — то же правило, что и в поиске: личная группа не ищется никогда
// (ADR-0018 п. 5). Показать её постороннему значило бы сообщить о её существовании.
func communityPage(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		communityID := r.PathValue("communityID")
		c, err := st.GetCommunity(r.Context(), communityID)
		if errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		} else if err != nil {
			log.Printf("communityPage: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		inCharge := c.OwnerID == id.UserID
		if !inCharge {
			admins, err := st.CommunityAdmins(r.Context(), communityID)
			if err != nil {
				log.Printf("communityPage: админы: %v", err)
			}
			for _, a := range admins {
				if a == id.UserID {
					inCharge = true
					break
				}
			}
		}
		subscribed, _ := st.IsSubscribedToCommunity(r.Context(), communityID, id.UserID)
		// Секретное — постороннему «не найдено»; приглашённому — страница, чтобы знал, куда зовут (СЦ2).
		if c.Hidden && !inCharge && !subscribed {
			if invited, err := st.HasPendingInvite(r.Context(), "community", communityID, id.UserID); err != nil || !invited {
				writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
				return
			}
		}
		items, err := st.CommunityItems(r.Context(), communityID)
		if err != nil {
			log.Printf("communityPage: состав: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(items))
		for _, it := range items {
			// Личная и секретная — только тем, кто распоряжается (ADR-0018 п. 5; СЦ2).
			if (it.Personal || it.Hidden) && !inCharge {
				continue
			}
			out = append(out, map[string]any{
				"kind": it.Kind, "id": it.ID, "title": it.Title, "personal": it.Personal, "hidden": it.Hidden,
			})
		}
		messages, err := st.CommunityMessages(r.Context(), communityID, 0)
		if err != nil {
			log.Printf("communityPage: описание: %v", err)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"community": communityJSON(store.CommunityView{
				Community: c, Subscribed: subscribed, Owner: c.OwnerID == id.UserID, Admin: inCharge,
			}),
			"items":       out,
			"description": messagesJSON(messages),
		})
	}
}

func messagesJSON(messages []store.CommunityMessage) []map[string]any {
	out := make([]map[string]any, 0, len(messages))
	for _, m := range messages {
		item := map[string]any{
			"message_id": m.MessageID, "author_id": m.AuthorID, "nodes": m.Nodes,
			"created_at_unix_ms": m.CreatedAtUnixMs,
			// Уровень назван прямо, хотя он всегда 0: клиент показывает круг словом, а
			// не догадывается по типу контейнера.
			"level": levelPublicShowcase,
		}
		if len(m.Markup) > 0 {
			item["markup"] = json.RawMessage(m.Markup)
		}
		out = append(out, item)
	}
	return out
}

// linkItem — POST /communities/{id}/items {kind, id}: связать готовое.
func linkItem(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Kind string `json:"kind"`
			ID   string `json:"id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if !knownItemKind(req.Kind) || req.ID == "" {
			writeErr(w, http.StatusBadRequest, "bad_kind", "kind — group или channel, нужен id")
			return
		}
		id, _ := auth.FromContext(r.Context())
		err := st.LinkItem(r.Context(), r.PathValue("communityID"), req.Kind, req.ID, id.UserID)
		if !answerLink(w, err, "linkItem") {
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"kind": req.Kind, "id": req.ID})
	}
}

func unlinkItem(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		kind := r.PathValue("kind")
		if !knownItemKind(kind) {
			writeErr(w, http.StatusBadRequest, "bad_kind", "kind — group или channel")
			return
		}
		id, _ := auth.FromContext(r.Context())
		err := st.UnlinkItem(r.Context(), r.PathValue("communityID"), kind, r.PathValue("itemID"), id.UserID)
		if !answerLink(w, err, "unlinkItem") {
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// knownItemKind — вид элемента, который сообщество умеет связывать.
//
// Звукового чата здесь нет намеренно: он ждёт реализации, и принять его значило бы
// пообещать связывание, которое некуда применить.
func knownItemKind(kind string) bool { return kind == "group" || kind == "channel" }

func answerLink(w http.ResponseWriter, err error, what string) bool {
	switch {
	case errors.Is(err, store.ErrElementBusy):
		writeErr(w, http.StatusConflict, "element_busy", "этот элемент уже в другом сообществе")
		return false
	case errors.Is(err, store.ErrNotAllowed):
		writeErr(w, http.StatusForbidden, "not_allowed", "связывать может владелец сообщества и владелец элемента")
		return false
	case errors.Is(err, store.ErrCommunityNotFound):
		writeErr(w, http.StatusNotFound, "not_found", "сообщество или элемент не найдены")
		return false
	case err != nil:
		log.Printf("%s: %v", what, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return false
	}
	return true
}

func subscribeCommunity(st CommunityStore, on bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		communityID := r.PathValue("communityID")
		c, err := st.GetCommunity(r.Context(), communityID)
		if errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		// В секретное — только по приглашению (СЦ2); отписаться можно всегда.
		if on && c.Hidden && c.OwnerID != id.UserID {
			sub, _ := st.IsSubscribedToCommunity(r.Context(), communityID, id.UserID)
			invited, err := st.HasPendingInvite(r.Context(), "community", communityID, id.UserID)
			if !sub && (err != nil || !invited) {
				writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
				return
			}
		}
		// Забаненный в сообществе не подписывается (СЦ23).
		if on && refuseBanned(w, r, st, "community", communityID, id.UserID) {
			return
		}
		if err := st.SubscribeCommunity(r.Context(), communityID, id.UserID, on); err != nil {
			log.Printf("subscribeCommunity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"subscribed": on})
	}
}

func communityAdmins(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ids, err := st.CommunityAdmins(r.Context(), r.PathValue("communityID"))
		if err != nil {
			log.Printf("communityAdmins: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if ids == nil {
			ids = []string{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"admins": ids})
	}
}

func setCommunityAdmin(st CommunityStore, on bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := r.PathValue("userID")
		if on {
			var req struct {
				UserID string `json:"user_id"`
			}
			if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.UserID == "" {
				writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
				return
			}
			userID = req.UserID
		}
		id, _ := auth.FromContext(r.Context())
		err := st.SetCommunityAdmin(r.Context(), r.PathValue("communityID"), id.UserID, userID, on)
		switch {
		case errors.Is(err, store.ErrNotAllowed):
			writeErr(w, http.StatusForbidden, "not_allowed", "роли раздаёт владелец сообщества")
			return
		case errors.Is(err, store.ErrCommunityNotFound):
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		case err != nil:
			log.Printf("setCommunityAdmin: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		role := "member"
		if on {
			role = "admin"
		}
		noteEntityRole(r, st, "community", r.PathValue("communityID"), userID, role)
		if on {
			w.WriteHeader(http.StatusCreated)
		} else {
			w.WriteHeader(http.StatusNoContent)
		}
	}
}

func communityMessages(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		messages, err := st.CommunityMessages(r.Context(), r.PathValue("communityID"), limit)
		if errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		} else if err != nil {
			log.Printf("communityMessages: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"messages": messagesJSON(messages)})
	}
}

// addCommunityMessage — описание: сообщение уровня 0. Пишут владелец и админы.
func addCommunityMessage(st CommunityStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req openPostBody
		if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		// Тело документом (СЦ25): с ним текст может быть пустым — фото, файл, голосовое.
		nodes, markup, body, ok := req.parse(w)
		if !ok {
			return
		}
		if showcaseFull(w, r, st, store.ShowcaseCommunity, r.PathValue("communityID")) {
			return
		}
		id, _ := auth.FromContext(r.Context())
		messageID, err := st.AddCommunityMessage(r.Context(), store.CommunityMessage{
			CommunityID:     r.PathValue("communityID"),
			Nodes:           nodes,
			Markup:          markup,
			MarkupVersion:   1,
			CreatedAtUnixMs: time.Now().UnixMilli(),
		}, id.UserID)
		switch {
		case errors.Is(err, store.ErrNotAllowed):
			writeErr(w, http.StatusForbidden, "not_allowed", "описание пишет владелец сообщества или админ")
			return
		case errors.Is(err, store.ErrCommunityNotFound):
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		case err != nil:
			log.Printf("addCommunityMessage: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if s, isStore := st.(*store.Store); isStore {
			if err := store.SetCommunityMessageBody(r.Context(), s, r.PathValue("communityID"), messageID, body); err != nil {
				log.Printf("addCommunityMessage: тело: %v", err)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": messageID})
	}
}
