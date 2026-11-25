// Социум, девятый–одиннадцатый круг (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.10–§1.12, СЦ18–СЦ23; заказчик 2026-11-23).
//
//   - Команда канала: владелец, админ, модератор (СЦ21). Админ назначает и снимает модераторов, админов — владелец.
//     Запрос без `role` — «модератор», как прежде.
//   - Подписчики канала и сообщества (СЦ19, СЦ21, СЦ23): команде — с ролями и баном; участнику — только номера, если
//     включено «Показывать участников»; «Исключить» и «Забанить». У сообщества оба наследуются на каналы и группы
//     внутри: исключённый снимается и с них, забаненный в них не входит.
//   - «Показывать участников» (СЦ19): меняют владелец и админ; читается пачкой.
//   - Медленный режим комментариев канала (СЦ21), правка записи канала и сообщения сообщества, удаление сообщения
//     сообщества (СЦ18).
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"slices"
	"strconv"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// RegisterSocialRound — ручки СЦ18–СЦ23.
func RegisterSocialRound(mux *http.ServeMux, st *store.Store, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/channels/{entityID}/moderators", requireDevice(channelTeam(st)))
	mux.HandleFunc("POST /api/v1/channels/{entityID}/moderators", requireDevice(setChannelTeamRole(st)))
	mux.HandleFunc("DELETE /api/v1/channels/{entityID}/moderators/{userID}", requireDevice(dropChannelTeamRole(st)))
	mux.HandleFunc("PUT /api/v1/channels/{entityID}/slow-mode", requireDevice(setChannelSlow(st)))
	mux.HandleFunc("PATCH /api/v1/channels/{entityID}/posts/{postID}", requireDevice(editChannelPost(st)))
	mux.HandleFunc("PATCH /api/v1/communities/{entityID}/messages/{messageID}", requireDevice(editCommunityMessage(st)))
	mux.HandleFunc("DELETE /api/v1/communities/{entityID}/messages/{messageID}", requireDevice(deleteCommunityMessage(st)))
	for _, k := range []struct{ kind, plural string }{{"channel", "channels"}, {"community", "communities"}} {
		mux.HandleFunc("GET /api/v1/"+k.plural+"/{entityID}/subscribers", requireDevice(entitySubscribers(st, k.kind)))
		mux.HandleFunc("DELETE /api/v1/"+k.plural+"/{entityID}/subscribers/{userID}", requireDevice(excludeSubscriber(st, k.kind, false)))
		mux.HandleFunc("POST /api/v1/"+k.plural+"/{entityID}/subscribers/{userID}/ban", requireDevice(excludeSubscriber(st, k.kind, true)))
		mux.HandleFunc("DELETE /api/v1/"+k.plural+"/{entityID}/subscribers/{userID}/ban", requireDevice(unbanSubscriber(st, k.kind)))
	}
	for _, k := range []struct{ kind, plural string }{{"group", "groups"}, {"channel", "channels"}, {"community", "communities"}} {
		mux.HandleFunc("PUT /api/v1/"+k.plural+"/{entityID}/show-members", requireDevice(setShowMembers(st, k.kind)))
	}
	mux.HandleFunc("POST /api/v1/entities/show-members", requireDevice(showMembersBatch(st)))
}

// entityRank — ранг человека в сущности по `roleRank` (владелец 3, админ 2, модератор 1, участник или подписчик 0);
// −1 — не участвует. У сообщества модератора нет (§1.12, 3).
func entityRank(ctx context.Context, st *store.Store, kind, id, userID string) (int, error) {
	switch kind {
	case "channel":
		role, err := store.ChannelRole(ctx, st, id, userID)
		if err != nil {
			return -1, err
		}
		if role == "" {
			return -1, nil
		}
		return roleRank[role], nil
	case "community":
		c, err := st.GetCommunity(ctx, id)
		if err != nil {
			return -1, err
		}
		if c.OwnerID == userID {
			return rankOwner, nil
		}
		admins, err := st.CommunityAdmins(ctx, id)
		if err != nil {
			return -1, err
		}
		if listHas(admins, userID) {
			return rankAdmin, nil
		}
		if sub, err := st.IsSubscribedToCommunity(ctx, id, userID); err != nil {
			return -1, err
		} else if sub {
			return 0, nil
		}
		return -1, nil
	default:
		role, err := st.GroupRole(ctx, id, userID)
		if errors.Is(err, store.ErrNotMember) {
			return -1, nil
		} else if err != nil {
			return -1, err
		}
		return roleRank[role], nil
	}
}

// teamRank — с какого ранга человек в команде, распоряжающейся подписчиками: у канала — модератор, у сообщества —
// админ (модератора у сообщества нет).
func teamRank(kind string) int {
	if kind == "community" {
		return rankAdmin
	}
	return rankModerator
}

// rankOf — ранг просящего и цели; false — ответ уже записан (нет сущности, сбой хранилища).
func rankOf(w http.ResponseWriter, r *http.Request, st *store.Store, kind, id, userID string) (int, bool) {
	rank, err := entityRank(r.Context(), st, kind, id, userID)
	if entityMissing(err) || errors.Is(err, store.ErrCommunityNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "сущность не найдена")
		return -1, false
	} else if err != nil {
		log.Printf("rankOf %s: %v", kind, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return -1, false
	}
	return rank, true
}

// refuseBanned — 403 `banned`, если человек забанен в сущности или в сообществе, куда она входит (СЦ23). true —
// ответ записан. Хранилище — интерфейсом: проверка идёт, когда за ним настоящее хранилище.
func refuseBanned(w http.ResponseWriter, r *http.Request, st any, kind, id, userID string) bool {
	s, ok := st.(*store.Store)
	if !ok {
		return false
	}
	banned, err := store.BannedFrom(r.Context(), s, kind, id, userID)
	if err != nil {
		log.Printf("refuseBanned %s: %v", kind, err)
		return false
	}
	if banned {
		writeErr(w, http.StatusForbidden, "banned", "вы забанены в этой сущности или в её сообществе")
		return true
	}
	return false
}

// bannedErr — то же для путей без ответа под рукой (вход по приглашению): ErrBanned или nil.
func bannedErr(ctx context.Context, st any, kind, id, userID string) error {
	s, ok := st.(*store.Store)
	if !ok {
		return nil
	}
	if banned, err := store.BannedFrom(ctx, s, kind, id, userID); err == nil && banned {
		return store.ErrBanned
	}
	return nil
}

// channelTeam — GET /channels/{id}/moderators: команда канала. `moderators` — все, у кого есть роль (как прежде:
// прежний клиент видит их модераторами), `admins` — из них админы.
func channelTeam(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("entityID")
		ids, err := st.ChannelModerators(r.Context(), id)
		if err != nil {
			log.Printf("channelTeam: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		roles, err := store.ChannelStaffRoles(r.Context(), st, id)
		if err != nil {
			log.Printf("channelTeam: роли: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if ids == nil {
			ids = []string{}
		}
		admins := []string{}
		for _, u := range ids {
			if roles[u] == "admin" {
				admins = append(admins, u)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"moderators": ids, "admins": admins})
	}
}

// setChannelTeamRole — POST /channels/{id}/moderators {user_id, role?}: role — moderator (умолчание) или admin.
// Админа назначает владелец; модератора — владелец и админ; роль цели — строго ниже своей.
func setChannelTeamRole(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			UserID string `json:"user_id"`
			Role   string `json:"role"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.UserID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
			return
		}
		if req.Role == "" {
			req.Role = "moderator"
		}
		if req.Role != "moderator" && req.Role != "admin" {
			writeErr(w, http.StatusBadRequest, "bad_role", "роль — admin или moderator")
			return
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		actor, ok := rankOf(w, r, st, "channel", id, me.UserID)
		if !ok {
			return
		}
		target, ok := rankOf(w, r, st, "channel", id, req.UserID)
		if !ok {
			return
		}
		if actor < rankAdmin || roleRank[req.Role] >= actor || target >= actor {
			writeErr(w, http.StatusForbidden, "not_allowed", "админа назначает владелец, модератора — владелец и админ")
			return
		}
		if refuseBanned(w, r, st, "channel", id, req.UserID) {
			return
		}
		if err := store.SetChannelStaffRole(r.Context(), st, id, req.UserID, req.Role); err != nil {
			log.Printf("setChannelTeamRole: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		noteEntityRole(r, st, "channel", id, req.UserID, req.Role)
		w.WriteHeader(http.StatusCreated)
	}
}

// dropChannelTeamRole — DELETE /channels/{id}/moderators/{userID}: снять роль. Владелец — любую, админ — модератора;
// снять с себя может каждый.
func dropChannelTeamRole(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, targetID := r.PathValue("entityID"), r.PathValue("userID")
		me, _ := auth.FromContext(r.Context())
		actor, ok := rankOf(w, r, st, "channel", id, me.UserID)
		if !ok {
			return
		}
		target, ok := rankOf(w, r, st, "channel", id, targetID)
		if !ok {
			return
		}
		if target == rankOwner || (targetID != me.UserID && (actor < rankAdmin || target >= actor)) {
			writeErr(w, http.StatusForbidden, "not_allowed", "админа снимает владелец, модератора — владелец и админ")
			return
		}
		if err := store.SetChannelStaffRole(r.Context(), st, id, targetID, ""); err != nil {
			log.Printf("dropChannelTeamRole: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		noteEntityRole(r, st, "channel", id, targetID, "member")
		w.WriteHeader(http.StatusNoContent)
	}
}

// entitySubscribers — GET /{channels|communities}/{id}/subscribers. Команде (канал — модератор и выше, сообщество —
// владелец и админы) — номера, владелец, команда, забаненные. Подписчику — только номера, если у сущности включено
// «Показывать участников» (СЦ19: «только список имён»); иначе — 403.
func entitySubscribers(st *store.Store, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		rank, ok := rankOf(w, r, st, kind, id, me.UserID)
		if !ok {
			return
		}
		var ids []string
		var err error
		if kind == "channel" {
			ids, err = st.SubscriberIDs(r.Context(), id)
		} else {
			ids, err = store.CommunitySubscriberIDs(r.Context(), st, id)
		}
		if err != nil {
			log.Printf("entitySubscribers: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if ids == nil {
			ids = []string{}
		}
		w.Header().Set("Content-Type", "application/json")
		if rank < teamRank(kind) {
			show, err := store.ShowMembersOf(r.Context(), st, kind, []string{id})
			if rank < 0 || err != nil || !show[id] {
				writeErr(w, http.StatusForbidden, "not_allowed", "участников сущности видит её команда")
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"subscribers": ids})
			return
		}
		owner, _ := entityOwner(r.Context(), st, kind, id)
		team, admins := []string{}, []string{}
		if kind == "channel" {
			roles, err := store.ChannelStaffRoles(r.Context(), st, id)
			if err != nil {
				log.Printf("entitySubscribers: роли: %v", err)
			}
			for u, role := range roles {
				team = append(team, u)
				if role == "admin" {
					admins = append(admins, u)
				}
			}
		} else if a, err := st.CommunityAdmins(r.Context(), id); err == nil && a != nil {
			admins = a
		}
		slices.Sort(team)
		slices.Sort(admins)
		banned, err := store.BannedIDs(r.Context(), st, kind, id)
		if err != nil {
			log.Printf("entitySubscribers: бан: %v", err)
			banned = []string{}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"subscribers": ids, "owner_id": owner, "moderators": team, "admins": admins, "banned": banned,
		})
	}
}

// excludeSubscriber — DELETE …/subscribers/{userID} («Исключить» — может подписаться снова) и POST …/ban
// («Забанить» — не может, пока не снимут). Команда, цель — строго ниже рангом; владельца — никто. Исключённый из
// команды теряет и роль. У сообщества — с наследованием на каналы и группы внутри (СЦ23).
func excludeSubscriber(st *store.Store, kind string, ban bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, targetID := r.PathValue("entityID"), r.PathValue("userID")
		me, _ := auth.FromContext(r.Context())
		actor, ok := rankOf(w, r, st, kind, id, me.UserID)
		if !ok {
			return
		}
		target, ok := rankOf(w, r, st, kind, id, targetID)
		if !ok {
			return
		}
		switch {
		case actor < teamRank(kind):
			writeErr(w, http.StatusForbidden, "not_allowed", "исключает и банит команда сущности")
			return
		case target == rankOwner:
			writeErr(w, http.StatusForbidden, "owner", "владельца исключить нельзя")
			return
		case target >= actor || targetID == me.UserID:
			writeErr(w, http.StatusForbidden, "rank", "исключить можно только того, кто ниже рангом")
			return
		}
		var err error
		switch {
		case ban:
			err = store.BanUser(r.Context(), st, kind, id, targetID, me.UserID)
		case kind == "community":
			err = store.ExcludeFromCommunity(r.Context(), st, id, targetID)
		default:
			err = store.SetChannelStaffRole(r.Context(), st, id, targetID, "")
			if err == nil {
				err = st.Unsubscribe(r.Context(), id, targetID)
			}
		}
		if err == nil && kind == "community" && target == rankAdmin {
			if c, cerr := st.GetCommunity(r.Context(), id); cerr == nil {
				err = st.SetCommunityAdmin(r.Context(), id, c.OwnerID, targetID, false)
			}
		}
		if err != nil {
			log.Printf("excludeSubscriber %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		action := "remove"
		if ban {
			action = "ban"
		}
		if err := store.RoleLogOf(st).Note(r.Context(), kind, id, me.UserID, targetID, action, ""); err != nil {
			log.Printf("журнал ролей: %v", err)
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// unbanSubscriber — DELETE …/subscribers/{userID}/ban: снять бан; подписаться человек может сам.
func unbanSubscriber(st *store.Store, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, targetID := r.PathValue("entityID"), r.PathValue("userID")
		me, _ := auth.FromContext(r.Context())
		actor, ok := rankOf(w, r, st, kind, id, me.UserID)
		if !ok {
			return
		}
		if actor < teamRank(kind) {
			writeErr(w, http.StatusForbidden, "not_allowed", "бан снимает команда сущности")
			return
		}
		if err := store.UnbanUser(r.Context(), st, kind, id, targetID); err != nil {
			log.Printf("unbanSubscriber: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if err := store.RoleLogOf(st).Note(r.Context(), kind, id, me.UserID, targetID, "unban", ""); err != nil {
			log.Printf("журнал ролей: %v", err)
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// setShowMembers — PUT /{вид}/{id}/show-members {show}: «Показывать участников» (СЦ19). Владелец и админ.
func setShowMembers(st *store.Store, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Show *bool `json:"show"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Show == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен show")
			return
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		allowed, err := mayManageMain(r, st, kind, id, me.UserID)
		if entityMissing(err) || errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сущность не найдена")
			return
		} else if err != nil {
			log.Printf("setShowMembers: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !allowed {
			writeErr(w, http.StatusForbidden, "not_allowed", "настройку меняют владелец и админ")
			return
		}
		if err := store.SetShowMembers(r.Context(), st, kind, id, *req.Show); errors.Is(err, store.ErrEntityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сущность не найдена")
			return
		} else if err != nil {
			log.Printf("setShowMembers: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"show_members": *req.Show})
	}
}

// showMembersBatch — POST /entities/show-members {kind, ids}: номер → включено ли «Показывать участников».
// Открытое, как название: по нему клиент решает, показывать ли вкладку участнику.
func showMembersBatch(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Kind string   `json:"kind"`
			IDs  []string `json:"ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil || len(req.IDs) > 200 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны kind и ids (до 200)")
			return
		}
		if req.Kind != "group" && req.Kind != "channel" && req.Kind != "community" {
			writeErr(w, http.StatusBadRequest, "bad_kind", "вид — group, channel или community")
			return
		}
		show, err := store.ShowMembersOf(r.Context(), st, req.Kind, req.IDs)
		if err != nil {
			log.Printf("showMembersBatch: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"show": show})
	}
}

// setChannelSlow — PUT /channels/{id}/slow-mode {sec}: медленный режим комментариев (СЦ21). Владелец и админ.
func setChannelSlow(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Sec *int `json:"sec"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Sec == nil || *req.Sec < 0 || *req.Sec > 86400 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен sec от 0 до 86400")
			return
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		actor, ok := rankOf(w, r, st, "channel", id, me.UserID)
		if !ok {
			return
		}
		if actor < rankAdmin {
			writeErr(w, http.StatusForbidden, "not_allowed", "медленный режим ставят владелец и админ")
			return
		}
		if err := store.SetChannelSlowMode(r.Context(), st, id, *req.Sec); err != nil {
			log.Printf("setChannelSlow: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"slow_mode_sec": *req.Sec})
	}
}

// openPostBody — разбор тела записи и правки канала, сообщения сообщества: текст, узлы, разметка, тело документом.
type openPostBody struct {
	Text   string   `json:"text"`
	Nodes  []string `json:"nodes,omitempty"`
	Markup string   `json:"markup,omitempty"`
	// Body — MessageBody открытым (СЦ22): блоки и вложения; base64url без выравнивания.
	Body string `json:"body,omitempty"`
}

// parse — узлы, разметка, тело; false — ответ записан. Пустой текст допустим, если есть тело (запись из одного фото).
func (b openPostBody) parse(w http.ResponseWriter) ([]string, []byte, []byte, bool) {
	var body []byte
	if b.Body != "" {
		raw, err := base64.RawURLEncoding.DecodeString(b.Body)
		if err != nil || len(raw) > maxPlainPayloadBytes*4 {
			writeErr(w, http.StatusBadRequest, "bad_body", "body — base64url, до 64 КБ")
			return nil, nil, nil, false
		}
		body = raw
	}
	nodes := b.Nodes
	if len(nodes) == 0 && b.Text != "" {
		nodes = []string{b.Text}
	}
	if len(nodes) == 0 && len(body) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_text", "нужен текст или тело")
		return nil, nil, nil, false
	}
	if nodes == nil {
		nodes = []string{}
	}
	var markup []byte
	if b.Markup != "" {
		if !json.Valid([]byte(b.Markup)) {
			writeErr(w, http.StatusBadRequest, "bad_markup", "markup должен быть валидным JSON")
			return nil, nil, nil, false
		}
		markup = []byte(b.Markup)
	}
	return nodes, markup, body, true
}

// editChannelPost — PATCH /channels/{id}/posts/{postID}: правка своей записи или комментария (СЦ18). Чужое не
// правится — как у группы, где правка подписана автором.
func editChannelPost(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		postID, err := strconv.ParseUint(r.PathValue("postID"), 10, 64)
		if err != nil || postID == 0 {
			writeErr(w, http.StatusBadRequest, "bad_post_id", "post_id — целое число")
			return
		}
		var req openPostBody
		if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		nodes, markup, body, ok := req.parse(w)
		if !ok {
			return
		}
		me, _ := auth.FromContext(r.Context())
		err = store.EditChannelPost(r.Context(), st, r.PathValue("entityID"), postID, me.UserID, req.Text, nodes, markup, body)
		if errors.Is(err, store.ErrNotAllowed) {
			// Код прежний — расширился круг тех, кому можно (автор и команда канала, §1.18, 8)
			writeErr(w, http.StatusForbidden, "edit_not_author", "исправить запись могут автор и команда канала")
			return
		} else if err != nil {
			log.Printf("editChannelPost: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// communityMessageOf — номер сообщения сообщества и ранг просящего; пишут и удаляют владелец и админы.
func communityMessageOf(w http.ResponseWriter, r *http.Request, st *store.Store) (int64, string, bool) {
	messageID, err := strconv.ParseInt(r.PathValue("messageID"), 10, 64)
	if err != nil || messageID <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_message_id", "message_id — целое число")
		return 0, "", false
	}
	me, _ := auth.FromContext(r.Context())
	actor, ok := rankOf(w, r, st, "community", r.PathValue("entityID"), me.UserID)
	if !ok {
		return 0, "", false
	}
	if actor < rankAdmin {
		writeErr(w, http.StatusForbidden, "not_allowed", "сообщения описания сообщества — у владельца и админов")
		return 0, "", false
	}
	return messageID, me.UserID, true
}

// editCommunityMessage — PATCH /communities/{id}/messages/{messageID}: правка своего сообщения описания (СЦ18).
func editCommunityMessage(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		messageID, userID, ok := communityMessageOf(w, r, st)
		if !ok {
			return
		}
		var req openPostBody
		if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		nodes, markup, body, ok := req.parse(w)
		if !ok {
			return
		}
		err := store.EditCommunityMessage(r.Context(), st, r.PathValue("entityID"), messageID, userID, nodes, markup)
		if err == nil {
			err = store.SetCommunityMessageBody(r.Context(), st, r.PathValue("entityID"), messageID, body)
		}
		if errors.Is(err, store.ErrNotAllowed) {
			writeErr(w, http.StatusForbidden, "edit_not_author", "исправить сообщение может только автор")
			return
		} else if err != nil {
			log.Printf("editCommunityMessage: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// deleteCommunityMessage — DELETE /communities/{id}/messages/{messageID}: владелец и админы (СЦ18).
func deleteCommunityMessage(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		messageID, _, ok := communityMessageOf(w, r, st)
		if !ok {
			return
		}
		err := store.DeleteCommunityMessage(r.Context(), st, r.PathValue("entityID"), messageID)
		if errors.Is(err, store.ErrMessageGone) {
			writeErr(w, http.StatusNotFound, "message_not_found", "сообщения нет")
			return
		} else if err != nil {
			log.Printf("deleteCommunityMessage: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// attachBodies — тела записей канала документом (СЦ22) в готовую выдачу: у каждой записи с телом — поле `body`
// (base64url). Хранилище — интерфейсом: без настоящего хранилища выдача остаётся прежней.
func attachBodies(ctx context.Context, st any, channelID string, items []map[string]any) {
	s, ok := st.(*store.Store)
	if !ok || len(items) == 0 {
		return
	}
	ids := make([]uint64, 0, len(items))
	for _, it := range items {
		if id, ok := it["post_id"].(uint64); ok {
			ids = append(ids, id)
		}
	}
	bodies, err := store.PostBodiesOf(ctx, s, channelID, ids)
	if err != nil {
		log.Printf("attachBodies: %v", err)
		return
	}
	for _, it := range items {
		if id, ok := it["post_id"].(uint64); ok {
			if b := bodies[id]; len(b) > 0 {
				it["body"] = base64.RawURLEncoding.EncodeToString(b)
			}
		}
	}
}

// channelTeamHas — в команде ли канала человек (владелец, админ, модератор). Хранилище — интерфейсом.
func channelTeamHas(ctx context.Context, st any, ch store.Channel, userID string) bool {
	if ch.OwnerID == userID {
		return true
	}
	m, ok := st.(interface {
		ChannelModerators(ctx context.Context, channelID string) ([]string, error)
	})
	if !ok {
		return false
	}
	ids, err := m.ChannelModerators(ctx, ch.ChannelID)
	return err == nil && listHas(ids, userID)
}
