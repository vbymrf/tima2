// Эмоции, пальцы, комментарии к сообщениям уровня 0 и настройки «Описания» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.18,
// СЦ37–СЦ40; заказчик 2026-11-25).
//
//   - Эмоции — публичная оценка: одна на человека, у записи канала и у сообщения уровня 0 («Описание») группы,
//     канала, сообщества. Ставит участник (подписчик, член, команда); счёт видят все, кто видит сообщение.
//   - Палец вверх и вниз — частная оценка записи канала: ею человек подбирает свою ленту; её не видит никто, кроме
//     него самого, — в выдаче только своя.
//   - Комментарии к сообщениям уровня 0 у групп и сообществ — при включённой настройке «Комментарии к описанию»;
//     пишет участник, читает каждый, кто видит «Описание» (3а). У канала сообщения «Описания» — записи уровня 0, и
//     комментарии к ним — прежние комментарии записи.
//   - Настройки «Описания» — «Управление → Основное»: целиком или обрезкой, комментарии к уровню 0. Меняют владелец и
//     админ; читаются вместе с «Описанием».
//
// Всё — расширение API: новые ручки и новые поля в прежних ответах.
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

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// RegisterReactions — ручки эмоций, пальцев, комментариев к уровню 0 и настроек «Описания».
func RegisterReactions(mux *http.ServeMux, st *store.Store, requireDevice Middleware) {
	mux.HandleFunc("PUT /api/v1/reactions/{kind}/{entityID}/{messageID}", requireDevice(setReaction(st)))
	mux.HandleFunc("DELETE /api/v1/reactions/{kind}/{entityID}/{messageID}", requireDevice(setReaction(st)))
	mux.HandleFunc("PUT /api/v1/votes/channel/{entityID}/{postID}", requireDevice(setVote(st)))
	mux.HandleFunc("GET /api/v1/level0/{kind}/{entityID}/{messageID}/comments", requireDevice(level0Comments(st)))
	mux.HandleFunc("POST /api/v1/level0/{kind}/{entityID}/{messageID}/comments", requireDevice(addLevel0Comment(st)))
	for _, k := range []struct{ kind, plural string }{{"group", "groups"}, {"channel", "channels"}, {"community", "communities"}} {
		mux.HandleFunc("PUT /api/v1/"+k.plural+"/{entityID}/showcase-settings", requireDevice(setShowcaseSettings(st, k.kind)))
	}
}

// participantOrRefuse — участник ли человек сущности (подписчик, член, команда) и не забанен; false — ответ записан.
func participantOrRefuse(w http.ResponseWriter, r *http.Request, st *store.Store, kind, id, userID string) bool {
	rank, ok := rankOf(w, r, st, kind, id, userID)
	if !ok {
		return false
	}
	if rank < 0 {
		writeErr(w, http.StatusForbidden, "not_member", "оценивают и комментируют участники")
		return false
	}
	return !refuseBanned(w, r, st, kind, id, userID)
}

func entityKindOf(w http.ResponseWriter, r *http.Request) (string, bool) {
	kind := r.PathValue("kind")
	if kind != "channel" && kind != "group" && kind != "community" {
		writeErr(w, http.StatusBadRequest, "bad_kind", "вид — channel, group или community")
		return "", false
	}
	return kind, true
}

func messageIDOf(w http.ResponseWriter, r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_message_id", "номер сообщения — целое число")
		return 0, false
	}
	return id, true
}

// reactionsJSON — эмоции одного сообщения в выдаче: счёт по убыванию и своя.
func reactionsJSON(rs store.Reactions) map[string]any {
	counts := rs.Counts
	if counts == nil {
		counts = []store.EmotionCount{}
	}
	return map[string]any{"reactions": counts, "mine": rs.Mine}
}

// setReaction — PUT {emotion} ставит или меняет свою эмоцию, DELETE снимает. Ответ — эмоции сообщения после правки.
func setReaction(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		kind, ok := entityKindOf(w, r)
		if !ok {
			return
		}
		messageID, ok := messageIDOf(w, r, "messageID")
		if !ok {
			return
		}
		emotion := ""
		if r.Method == http.MethodPut {
			var req struct {
				Emotion string `json:"emotion"`
			}
			if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || !store.IsEmotion(req.Emotion) {
				writeErr(w, http.StatusBadRequest, "bad_emotion", "эмоция — из шкалы «Оценки»")
				return
			}
			emotion = req.Emotion
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		if !participantOrRefuse(w, r, st, kind, id, me.UserID) {
			return
		}
		if exists, err := store.ReactionTargetExists(r.Context(), st, kind, id, messageID); err != nil {
			log.Printf("setReaction: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if !exists {
			writeErr(w, http.StatusNotFound, "message_not_found", "сообщение не найдено")
			return
		}
		if err := store.SetReaction(r.Context(), st, kind, id, messageID, me.UserID, emotion); err != nil {
			log.Printf("setReaction: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		all, err := store.ReactionsOf(r.Context(), st, kind, id, []int64{messageID}, me.UserID)
		if err != nil {
			log.Printf("setReaction: read: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(reactionsJSON(all[messageID]))
	}
}

// setVote — PUT /votes/channel/{id}/{postID} {vote}: палец вверх 1, вниз −1, снять 0. Ответ — своя оценка.
func setVote(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		postID, ok := messageIDOf(w, r, "postID")
		if !ok {
			return
		}
		var req struct {
			Vote *int `json:"vote"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Vote == nil ||
			*req.Vote < -1 || *req.Vote > 1 {
			writeErr(w, http.StatusBadRequest, "bad_vote", "vote — 1, −1 или 0")
			return
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		if !participantOrRefuse(w, r, st, "channel", id, me.UserID) {
			return
		}
		if exists, err := store.ReactionTargetExists(r.Context(), st, "channel", id, postID); err != nil {
			log.Printf("setVote: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if !exists {
			writeErr(w, http.StatusNotFound, "message_not_found", "запись не найдена")
			return
		}
		if err := store.SetVote(r.Context(), st, id, postID, me.UserID, *req.Vote); err != nil {
			log.Printf("setVote: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"vote": *req.Vote})
	}
}

// showcaseVisible — видит ли человек «Описание» сущности: участник — всегда; не вступивший — если сущность ему открыта
// (публичная, по карточке, по приглашению — как у самого «Описания»). false — ответ записан.
func showcaseVisible(w http.ResponseWriter, r *http.Request, st *store.Store, kind, id, userID string) bool {
	rank, ok := rankOf(w, r, st, kind, id, userID)
	if !ok {
		return false
	}
	if rank >= 0 || kind == "community" {
		// Сообщество читает каждый, как его страницу.
		return true
	}
	open, err := st.GroupOpenToOutsider(r.Context(), id, userID)
	if err != nil {
		log.Printf("showcaseVisible: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return false
	}
	if !open {
		writeErr(w, http.StatusNotFound, "not_found", "сущность не найдена")
		return false
	}
	return true
}

func level0KindOf(w http.ResponseWriter, r *http.Request) (string, bool) {
	kind := r.PathValue("kind")
	if kind != "group" && kind != "community" {
		// У канала сообщения «Описания» — записи, и комментарии к ним — /channels/{id}/posts/{postID}/comments.
		writeErr(w, http.StatusBadRequest, "bad_kind", "вид — group или community; у канала — комментарии записи")
		return "", false
	}
	return kind, true
}

// level0Comments — GET: комментарии к сообщению «Описания» по возрастанию (`after` — с какого номера). Читает каждый,
// кто видит «Описание»; `allowed` — может ли просящий написать (настройка включена и он участник).
func level0Comments(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		kind, ok := level0KindOf(w, r)
		if !ok {
			return
		}
		messageID, ok := messageIDOf(w, r, "messageID")
		if !ok {
			return
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		if !showcaseVisible(w, r, st, kind, id, me.UserID) {
			return
		}
		if exists, err := store.ReactionTargetExists(r.Context(), st, kind, id, messageID); err != nil {
			log.Printf("level0Comments: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if !exists {
			writeErr(w, http.StatusNotFound, "message_not_found", "сообщение не найдено")
			return
		}
		var after int64
		if v := r.URL.Query().Get("after"); v != "" {
			after, _ = strconv.ParseInt(v, 10, 64)
		}
		list, err := store.Level0Comments(r.Context(), st, kind, id, messageID, after, 200)
		if err != nil {
			log.Printf("level0Comments: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		settings, err := store.ShowcaseSettingsOf(r.Context(), st, kind, id)
		if err != nil {
			log.Printf("level0Comments: settings: %v", err)
		}
		rank, _ := entityRank(r.Context(), st, kind, id, me.UserID)
		out := make([]map[string]any, 0, len(list))
		for _, c := range list {
			item := map[string]any{"comment_id": c.CommentID, "author_id": c.AuthorID, "text": c.Text,
				"created_at_unix_ms": c.CreatedAtUnixMs}
			if len(c.Body) > 0 {
				item["body"] = base64.RawURLEncoding.EncodeToString(c.Body)
			}
			out = append(out, item)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"kind": kind, "entity_id": id, "message_id": messageID,
			"comments": out, "allowed": settings.Comments && rank >= 0})
	}
}

// addLevel0Comment — POST {text, body?}: комментарий к сообщению «Описания». Только при включённой настройке и только
// участнику (3а).
func addLevel0Comment(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		kind, ok := level0KindOf(w, r)
		if !ok {
			return
		}
		messageID, ok := messageIDOf(w, r, "messageID")
		if !ok {
			return
		}
		var req struct {
			Text string `json:"text"`
			Body string `json:"body"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		var body []byte
		if req.Body != "" {
			b, err := base64.RawURLEncoding.DecodeString(req.Body)
			if err != nil {
				writeErr(w, http.StatusBadRequest, "bad_body", "body — base64url")
				return
			}
			body = b
		}
		if req.Text == "" && len(body) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_text", "пустой комментарий")
			return
		}
		if len([]rune(req.Text)) > 4000 {
			writeErr(w, http.StatusBadRequest, "bad_text", "комментарий длиннее 4000 знаков")
			return
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		if !participantOrRefuse(w, r, st, kind, id, me.UserID) {
			return
		}
		settings, err := store.ShowcaseSettingsOf(r.Context(), st, kind, id)
		if errors.Is(err, store.ErrEntityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сущность не найдена")
			return
		} else if err != nil {
			log.Printf("addLevel0Comment: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !settings.Comments {
			writeErr(w, http.StatusForbidden, "comments_closed", "комментарии к описанию выключены")
			return
		}
		if exists, err := store.ReactionTargetExists(r.Context(), st, kind, id, messageID); err != nil {
			log.Printf("addLevel0Comment: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if !exists {
			writeErr(w, http.StatusNotFound, "message_not_found", "сообщение не найдено")
			return
		}
		commentID, err := store.AddLevel0Comment(r.Context(), st, kind, id, messageID, me.UserID, req.Text, body)
		if err != nil {
			log.Printf("addLevel0Comment: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"comment_id": commentID})
	}
}

// setShowcaseSettings — PUT /{вид}/{id}/showcase-settings {whole?, comments?}: настройки «Описания». Владелец и админ.
func setShowcaseSettings(st *store.Store, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Whole    *bool `json:"whole"`
			Comments *bool `json:"comments"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || (req.Whole == nil && req.Comments == nil) {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен whole или comments")
			return
		}
		id := r.PathValue("entityID")
		me, _ := auth.FromContext(r.Context())
		allowed, err := mayManageMain(r, st, kind, id, me.UserID)
		if entityMissing(err) || errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сущность не найдена")
			return
		} else if err != nil {
			log.Printf("setShowcaseSettings: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !allowed {
			writeErr(w, http.StatusForbidden, "not_allowed", "настройку меняют владелец и админ")
			return
		}
		out, err := store.SetShowcaseSettings(r.Context(), st, kind, id, req.Whole, req.Comments)
		if errors.Is(err, store.ErrEntityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сущность не найдена")
			return
		} else if err != nil {
			log.Printf("setShowcaseSettings: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	}
}

// itemID — номер сообщения в элементе выдачи (`post_id` или `message_id`, любого целого типа).
func itemID(it map[string]any, key string) (int64, bool) {
	switch v := it[key].(type) {
	case int64:
		return v, true
	case uint64:
		return int64(v), true
	case int:
		return int64(v), true
	case int32:
		return int64(v), true
	}
	return 0, false
}

// attachReactions — к элементам выдачи: эмоции (`reactions`, `my_emotion`); у записей канала — свой палец
// (`my_vote`, 0 — нет); у «Описания» групп и сообществ — число комментариев (`comments`). Хранилище — интерфейсом:
// поля появляются, когда за ним настоящее хранилище; сбой — выдача без них, а не отказ.
func attachReactions(ctx context.Context, st any, kind, entityID, viewerID, key string, items []map[string]any, level0Comments bool) {
	s, ok := st.(*store.Store)
	if !ok || len(items) == 0 {
		return
	}
	ids := make([]int64, 0, len(items))
	for _, it := range items {
		if id, ok := itemID(it, key); ok {
			ids = append(ids, id)
		}
	}
	reactions, err := store.ReactionsOf(ctx, s, kind, entityID, ids, viewerID)
	if err != nil {
		log.Printf("attachReactions: %v", err)
		return
	}
	var votes map[int64]int
	if kind == "channel" {
		if votes, err = store.VotesOf(ctx, s, entityID, ids, viewerID); err != nil {
			log.Printf("attachReactions: votes: %v", err)
		}
	}
	var counts map[int64]int
	if level0Comments {
		if counts, err = store.Level0CommentCounts(ctx, s, kind, entityID, ids); err != nil {
			log.Printf("attachReactions: comments: %v", err)
		}
	}
	for _, it := range items {
		id, ok := itemID(it, key)
		if !ok {
			continue
		}
		rs := reactions[id]
		if rs.Counts == nil {
			rs.Counts = []store.EmotionCount{}
		}
		it["reactions"] = rs.Counts
		it["my_emotion"] = rs.Mine
		if kind == "channel" {
			it["my_vote"] = votes[id]
		}
		if level0Comments {
			it["comments"] = counts[id]
		}
	}
}

// showcaseSettingsFor — настройки «Описания» для выдачи; сбой — умолчания (обрезкой, без комментариев).
func showcaseSettingsFor(ctx context.Context, st any, kind, id string) store.ShowcaseSettings {
	s, ok := st.(*store.Store)
	if !ok {
		return store.ShowcaseSettings{}
	}
	out, err := store.ShowcaseSettingsOf(ctx, s, kind, id)
	if err != nil && !errors.Is(err, store.ErrEntityNotFound) {
		log.Printf("showcaseSettingsFor: %v", err)
	}
	return out
}
