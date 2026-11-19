// Переписка «сущность — человек» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.9, СЦ15).
//
// Одна лента на пару «сущность верхнего уровня — человек»; у элемента сообщества — лента сообщества. Сторона
// «сущность» — владелец, админ, модератор (у сообщества — владелец и админы); право проверяется при каждом запросе.
// Автор стороны сущности человеку не отдаётся. Предел — сущность пишет не больше чем пяти разным людям в сутки,
// ответ написавшему первым считается так же; сообщений одному — сколько угодно.
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"
	"unicode/utf8"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// threadPeoplePerDay — предел людей в сутки на сущность (§1.9, 1); больше — потом, подпиской.
const threadPeoplePerDay = 5

// ThreadEntities — что ленте нужно от сущностей.
type ThreadEntities interface {
	OwnershipEntities
	GroupOpenToOutsider(ctx context.Context, groupID, userID string) (bool, error)
	ListGroupMembers(ctx context.Context, groupID string) ([]store.Member, error)
	IsSubscribed(ctx context.Context, channelID, userID string) (bool, error)
	IsSubscribedToCommunity(ctx context.Context, communityID, userID string) (bool, error)
}

var _ ThreadEntities = (*store.Store)(nil)

type threadDeps struct {
	st ThreadEntities
	th store.Threads
	n  *Notifier
}

// RegisterThreads — ленты «сущность — человек».
func RegisterThreads(mux *http.ServeMux, st ThreadEntities, th store.Threads, n *Notifier, requireDevice Middleware) {
	d := threadDeps{st: st, th: th, n: n}
	for _, k := range []struct{ kind, plural string }{{"group", "groups"}, {"channel", "channels"}, {"community", "communities"}} {
		base := "/api/v1/" + k.plural + "/{entityID}"
		mux.HandleFunc("GET "+base+"/thread/messages", requireDevice(userThread(d, k.kind)))
		mux.HandleFunc("POST "+base+"/thread/messages", requireDevice(userWrites(d, k.kind)))
		mux.HandleFunc("PUT "+base+"/thread/state", requireDevice(userThreadState(d, k.kind)))
		mux.HandleFunc("PUT "+base+"/thread/read", requireDevice(userThreadRead(d, k.kind)))
		mux.HandleFunc("GET "+base+"/threads", requireDevice(entityThreads(d, k.kind)))
		mux.HandleFunc("GET "+base+"/threads/{userID}/messages", requireDevice(entityThread(d, k.kind)))
		mux.HandleFunc("POST "+base+"/threads/{userID}/messages", requireDevice(entityWrites(d, k.kind)))
		mux.HandleFunc("PUT "+base+"/threads/{userID}/read", requireDevice(entityThreadRead(d, k.kind)))
		mux.HandleFunc("PUT "+base+"/threads/{userID}/block", requireDevice(entityBlocks(d, k.kind)))
		mux.HandleFunc("PUT "+base+"/appeals", requireDevice(setAppeals(d, k.kind)))
	}
	mux.HandleFunc("GET /api/v1/users/me/threads", requireDevice(myThreads(d)))
}

// staffOf — кто пишет за сущность: владелец и те, кто может принять владение (модератор и выше; в сообществе —
// админы).
func staffOf(ctx context.Context, st ThreadEntities, kind, id string) ([]string, error) {
	owner, err := entityOwner(ctx, st, kind, id)
	if err != nil {
		return nil, err
	}
	out := []string{owner}
	switch kind {
	case "group":
		members, err := st.ListGroupMembers(ctx, id)
		if err != nil {
			return nil, err
		}
		for _, m := range members {
			if m.UserID != owner && roleRank[m.Role] >= rankModerator {
				out = append(out, m.UserID)
			}
		}
	case "channel":
		mods, err := st.ChannelModerators(ctx, id)
		if err != nil {
			return nil, err
		}
		out = append(out, mods...)
	default:
		admins, err := st.CommunityAdmins(ctx, id)
		if err != nil {
			return nil, err
		}
		out = append(out, admins...)
	}
	return out, nil
}

// seesPage — видит ли человек страницу сущности (писать первым может любой, кто видит, §1.9, 3).
func seesPage(ctx context.Context, st ThreadEntities, kind, id, userID string) (bool, error) {
	switch kind {
	case "group":
		if role, err := st.GroupRole(ctx, id, userID); err == nil && role != "" {
			return true, nil
		}
		return st.GroupOpenToOutsider(ctx, id, userID)
	case "channel":
		c, err := st.GetChannel(ctx, id)
		if err != nil {
			return false, err
		}
		if !c.Hidden {
			return true, nil
		}
		return st.IsSubscribed(ctx, id, userID)
	default:
		c, err := st.GetCommunity(ctx, id)
		if err != nil {
			return false, err
		}
		if !c.Hidden {
			return true, nil
		}
		return st.IsSubscribedToCommunity(ctx, id, userID)
	}
}

// threadTop — сущность верхнего уровня; нет — 404 уже записан.
func threadTop(w http.ResponseWriter, r *http.Request, d threadDeps, kind string) (string, string, bool) {
	k, id, err := d.th.TopLevel(r.Context(), kind, r.PathValue("entityID"))
	if errors.Is(err, store.ErrGroupNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "не найдено")
		return "", "", false
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return "", "", false
	}
	return k, id, true
}

func threadJSON(t store.Thread) map[string]any {
	return map[string]any{
		"thread_id": t.ThreadID, "entity_kind": t.EntityKind, "entity_id": t.EntityID, "user_id": t.UserID,
		"state": t.UserState, "blocked": t.UserBlocked, "last_id": t.LastID, "last_at": t.LastAt.UTC().Format(time.RFC3339),
		"title": t.Title, "preview": t.Preview, "unread": t.Unread,
	}
}

func messagesJSONThread(list []store.ThreadMessage, withAuthor bool) []map[string]any {
	out := make([]map[string]any, 0, len(list))
	for _, m := range list {
		item := map[string]any{
			"message_id": m.MessageID, "side": m.Side, "body": base64.RawURLEncoding.EncodeToString(m.Body),
			"preview": m.Preview, "created_at": m.CreatedAt.UTC().Format(time.RFC3339), "at_ms": m.CreatedAt.UnixMilli(),
		}
		// Автор стороны сущности — только команде сущности (§1.9, 2).
		if withAuthor {
			item["author_id"] = m.AuthorID
		}
		out = append(out, item)
	}
	return out
}

// readBody — тело сообщения (то же, что у личной группы, открытым) и текст для строки, до 120 знаков.
func readBody(w http.ResponseWriter, r *http.Request) ([]byte, string, bool) {
	var req struct {
		Body    string `json:"body"`
		Preview string `json:"preview"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 128<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужны body и preview")
		return nil, "", false
	}
	body, err := base64.RawURLEncoding.DecodeString(req.Body)
	if err != nil || len(body) == 0 || len(body) > 64<<10 {
		writeErr(w, http.StatusBadRequest, "bad_body", "body — base64url, до 64 КБ")
		return nil, "", false
	}
	preview := req.Preview
	if utf8.RuneCountInString(preview) > 120 {
		preview = string([]rune(preview)[:120])
	}
	return body, preview, true
}

func afterOf(r *http.Request) int64 {
	v, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	return v
}

// userThread — GET …/thread/messages: моя лента с сущностью; без автора стороны сущности.
func userThread(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		me, _ := auth.FromContext(r.Context())
		accepts, _ := d.th.Accepts(r.Context(), k, id)
		answer := map[string]any{"entity_kind": k, "entity_id": id, "accepts": accepts, "messages": []map[string]any{}}
		t, err := d.th.Get(r.Context(), k, id, me.UserID)
		if err == nil {
			list, err := d.th.Messages(r.Context(), t.ThreadID, afterOf(r), 200)
			if err != nil {
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			answer["thread"] = threadJSON(t)
			answer["messages"] = messagesJSONThread(list, false)
		} else if !errors.Is(err, store.ErrThreadNotFound) {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(answer)
	}
}

// userWrites — POST …/thread/messages: человек пишет сущности.
func userWrites(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		body, preview, ok := readBody(w, r)
		if !ok {
			return
		}
		me, _ := auth.FromContext(r.Context())
		if sees, err := seesPage(r.Context(), d.st, k, id, me.UserID); err != nil || !sees {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		}
		if accepts, err := d.th.Accepts(r.Context(), k, id); err != nil || !accepts {
			writeErr(w, http.StatusForbidden, "not_accepting", "сущность не принимает сообщения")
			return
		}
		threadID, err := d.th.Ensure(r.Context(), k, id, me.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if t, err := d.th.Get(r.Context(), k, id, me.UserID); err == nil && t.UserBlocked {
			writeErr(w, http.StatusForbidden, "blocked_by_entity", "сущность не принимает ваши сообщения")
			return
		}
		m, err := d.th.Add(r.Context(), threadID, "user", me.UserID, body, preview)
		if err != nil {
			log.Printf("userWrites: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if d.n != nil {
			if staff, err := staffOf(r.Context(), d.st, k, id); err == nil {
				d.n.Users(r.Context(), staff, "entity.thread", map[string]any{
					"entity_kind": k, "entity_id": id, "user_id": me.UserID, "message_id": m.MessageID, "side": "user",
				})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": m.MessageID, "entity_kind": k, "entity_id": id})
	}
}

// userThreadState — PUT …/thread/state {state}: включено, выключено, заблокировано.
func userThreadState(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		var req struct {
			State string `json:"state"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req); err != nil || (req.State != "on" && req.State != "off" && req.State != "blocked") {
			writeErr(w, http.StatusBadRequest, "bad_state", "state — on, off или blocked")
			return
		}
		me, _ := auth.FromContext(r.Context())
		threadID, err := d.th.Ensure(r.Context(), k, id, me.UserID)
		if err == nil {
			err = d.th.SetUserState(r.Context(), threadID, req.State)
		}
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func readUpTo(w http.ResponseWriter, r *http.Request) (int64, bool) {
	var req struct {
		UpTo int64 `json:"up_to"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req); err != nil || req.UpTo <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужен up_to")
		return 0, false
	}
	return req.UpTo, true
}

func userThreadRead(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		upTo, ok := readUpTo(w, r)
		if !ok {
			return
		}
		me, _ := auth.FromContext(r.Context())
		t, err := d.th.Get(r.Context(), k, id, me.UserID)
		if err != nil {
			writeErr(w, http.StatusNotFound, "thread_not_found", "ленты нет")
			return
		}
		if err := d.th.Read(r.Context(), t.ThreadID, false, upTo); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// staffOnly — просящий пишет за сущность; иначе 403 уже записан.
func staffOnly(w http.ResponseWriter, r *http.Request, d threadDeps, k, id string) (string, bool) {
	me, _ := auth.FromContext(r.Context())
	staff, err := staffOf(r.Context(), d.st, k, id)
	if entityMissing(err) {
		writeErr(w, http.StatusNotFound, "not_found", "не найдено")
		return "", false
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return "", false
	}
	if !listHas(staff, me.UserID) {
		writeErr(w, http.StatusForbidden, "forbidden", "за сущность пишут владелец, админ, модератор")
		return "", false
	}
	return me.UserID, true
}

// entityThreads — GET …/threads: «Обращения» — ленты сущности с людьми.
func entityThreads(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		if _, ok := staffOnly(w, r, d, k, id); !ok {
			return
		}
		list, err := d.th.ForEntity(r.Context(), k, id)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(list))
		for _, t := range list {
			out = append(out, threadJSON(t))
		}
		accepts, _ := d.th.Accepts(r.Context(), k, id)
		people, _, _ := d.th.PeopleToday(r.Context(), k, id, "")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"entity_kind": k, "entity_id": id, "threads": out, "accepts": accepts,
			"people_today": people, "people_limit": threadPeoplePerDay,
		})
	}
}

func entityThread(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		if _, ok := staffOnly(w, r, d, k, id); !ok {
			return
		}
		t, err := d.th.Get(r.Context(), k, id, r.PathValue("userID"))
		if errors.Is(err, store.ErrThreadNotFound) {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"messages": []map[string]any{}})
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		list, err := d.th.Messages(r.Context(), t.ThreadID, afterOf(r), 200)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"thread": threadJSON(t), "messages": messagesJSONThread(list, true)})
	}
}

// entityWrites — POST …/threads/{userID}/messages: пишут за сущность.
func entityWrites(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		author, ok := staffOnly(w, r, d, k, id)
		if !ok {
			return
		}
		body, preview, ok := readBody(w, r)
		if !ok {
			return
		}
		to := r.PathValue("userID")
		if t, err := d.th.Get(r.Context(), k, id, to); err == nil && t.UserState == "blocked" {
			writeErr(w, http.StatusForbidden, "blocked_by_user", "человек заблокировал сообщения этой сущности")
			return
		}
		count, already, err := d.th.PeopleToday(r.Context(), k, id, to)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !already && count >= threadPeoplePerDay {
			writeErr(w, http.StatusTooManyRequests, "people_limit", "сущность пишет не больше чем пяти людям в сутки")
			return
		}
		threadID, err := d.th.Ensure(r.Context(), k, id, to)
		if errors.Is(err, store.ErrUserUnknown) {
			writeErr(w, http.StatusNotFound, "user_not_found", "пользователь не существует")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		m, err := d.th.Add(r.Context(), threadID, "entity", author, body, preview)
		if err != nil {
			log.Printf("entityWrites: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if d.n != nil {
			// Человеку — без автора; команде — чтобы «Обращения» обновились у всех.
			d.n.Users(r.Context(), []string{to}, "entity.thread", map[string]any{
				"entity_kind": k, "entity_id": id, "message_id": m.MessageID, "side": "entity",
			})
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": m.MessageID})
	}
}

func entityThreadRead(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		if _, ok := staffOnly(w, r, d, k, id); !ok {
			return
		}
		upTo, ok := readUpTo(w, r)
		if !ok {
			return
		}
		t, err := d.th.Get(r.Context(), k, id, r.PathValue("userID"))
		if err != nil {
			writeErr(w, http.StatusNotFound, "thread_not_found", "ленты нет")
			return
		}
		if err := d.th.Read(r.Context(), t.ThreadID, true, upTo); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func entityBlocks(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		if _, ok := staffOnly(w, r, d, k, id); !ok {
			return
		}
		var req struct {
			Blocked *bool `json:"blocked"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req); err != nil || req.Blocked == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен blocked")
			return
		}
		threadID, err := d.th.Ensure(r.Context(), k, id, r.PathValue("userID"))
		if err == nil {
			err = d.th.SetBlocked(r.Context(), threadID, *req.Blocked)
		}
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// setAppeals — PUT …/appeals {accept}: «Принимать сообщения» (владелец, админ, модератор).
func setAppeals(d threadDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		k, id, ok := threadTop(w, r, d, kind)
		if !ok {
			return
		}
		if _, ok := staffOnly(w, r, d, k, id); !ok {
			return
		}
		var req struct {
			Accept *bool `json:"accept"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req); err != nil || req.Accept == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен accept")
			return
		}
		if err := d.th.SetAccepts(r.Context(), k, id, *req.Accept); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// myThreads — GET /users/me/threads: «Общение → Ответы» — мои ленты с сущностями.
func myThreads(d threadDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		me, _ := auth.FromContext(r.Context())
		list, err := d.th.ForUser(r.Context(), me.UserID)
		if err != nil {
			log.Printf("myThreads: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(list))
		for _, t := range list {
			out = append(out, threadJSON(t))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"threads": out})
	}
}
