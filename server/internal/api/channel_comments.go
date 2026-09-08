// Комментарии в каналах и лентах (ADR-0024, ПЛАН-КАНАЛОВ К1).
//
// ── ОДИН МЕХАНИЗМ, А НЕ ВТОРАЯ СУЩНОСТЬ ─────────────────────────────────────
//
// Комментарий — обычная запись канала, у которой контейнер не канал, а другая запись.
// Отсюда и отсутствие второго набора прав: круг у комментария не свой, он берётся у корня
// в момент выдачи. Скопировать уровень при записи нельзя — корень сужают задним числом
// (ADR-0019 §6), и копия осталась бы прежней.
//
// ── КОММЕНТИРУЕТ ТОТ, КТО ВИДИТ ─────────────────────────────────────────────
//
// Решение заказчика 2026-09-08 (ADR-0024 §8): отдельного права «комментировать» нет.
// Есть индекс видимости, и он отвечает на оба вопроса сразу:
//
//	видит корень  ⇔  может комментировать
//
// Поэтому проверка здесь одна и та же для чтения разговора и для записи в него, а
// «только подписчик» не появляется нигде: в публичном канале запись уровня 1 комментирует
// любой, кто её видит.
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

// subscriptionChecker — минимум, которым считается круг: подписан ли этот человек.
//
// Интерфейс отдельный и узкий, потому что границу считают два потребителя с разными
// хранилищами: маршруты каналов и маршруты ленты. Общий тип связал бы их без нужды.
type subscriptionChecker interface {
	IsSubscribed(ctx context.Context, channelID, userID string) (bool, error)
}

// maxLevelInChannel — граница выдачи в канале или ленте.
//
// Владелец видит всё; подписчик — до «своим»; посторонний — «всем». Для ленты человека
// подписчик и есть друг (подписка = дружба, миграция 0040), поэтому правило одно на оба
// вида контейнера, а не два похожих.
//
// Уровень 3 в канале сегодня достаётся только владельцу: поимённые разрешения у ленты —
// этап К4, до него список пуст.
func maxLevelInChannel(r *http.Request, st subscriptionChecker, ch store.Channel, userID string) int16 {
	if ch.OwnerID == userID {
		return levelByGrant
	}
	if sub, err := st.IsSubscribed(r.Context(), ch.ChannelID, userID); err != nil {
		// Ошибка чтения подписки не должна расширять круг: считаем посторонним.
		log.Printf("maxLevelInChannel: подписка: %v", err)
	} else if sub {
		return levelMembers
	}
	return levelEveryone
}

// rootForComments — корень разговора и проверка, что он показан этому человеку.
//
// Возвращает false, если ответ уже записан. «Не найдено» вместо «нельзя» намеренно: отказ,
// отличающийся от отсутствия, сам сообщал бы, что запись есть.
func rootForComments(w http.ResponseWriter, r *http.Request, st ChannelStore) (store.ChannelPost, bool) {
	channelID := r.PathValue("channelID")
	postID, err := strconv.ParseUint(r.PathValue("postID"), 10, 64)
	if err != nil || postID == 0 {
		writeErr(w, http.StatusBadRequest, "bad_post_id", "post_id — целое число")
		return store.ChannelPost{}, false
	}
	ch, err := st.GetChannel(r.Context(), channelID)
	if errors.Is(err, store.ErrChannelNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "канал не найден")
		return store.ChannelPost{}, false
	} else if err != nil {
		log.Printf("rootForComments: канал: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return store.ChannelPost{}, false
	}
	root, err := st.GetPost(r.Context(), channelID, postID)
	if errors.Is(err, store.ErrChannelNotFound) {
		writeErr(w, http.StatusNotFound, "post_not_found", "записи нет")
		return store.ChannelPost{}, false
	} else if err != nil {
		log.Printf("rootForComments: запись: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return store.ChannelPost{}, false
	}
	id, _ := auth.FromContext(r.Context())
	if root.Level > maxLevelInChannel(r, st, ch, id.UserID) {
		writeErr(w, http.StatusNotFound, "post_not_found", "записи нет")
		return store.ChannelPost{}, false
	}
	// Комментарий к комментарию отклонён: глубина два уровня, дальше — упоминанием
	// (ADR-0024 §7). Для читателя это тоже «записи нет»: разговора под комментарием не
	// существует, а не «существует, но закрыт».
	if root.ParentPostID != 0 {
		writeErr(w, http.StatusNotFound, "post_not_found", "у комментария нет своего разговора")
		return store.ChannelPost{}, false
	}
	return root, true
}

// listComments — GET /channels/{channelID}/posts/{postID}/comments.
func listComments(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		root, ok := rootForComments(w, r, st)
		if !ok {
			return
		}
		var after uint64
		if v := r.URL.Query().Get("after"); v != "" {
			after, _ = strconv.ParseUint(v, 10, 64)
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		comments, err := st.ListComments(r.Context(), root.ChannelID, root.PostID, after, limit)
		if err != nil {
			log.Printf("listComments: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(comments))
		for _, c := range comments {
			item := map[string]any{
				"post_id": c.PostID, "author_id": c.AuthorID, "text": c.Text,
				"nodes": c.Nodes, "created_at_unix_ms": c.CreatedAtUnixMs,
				"parent_post_id": c.ParentPostID,
			}
			if len(c.Markup) > 0 {
				item["markup"] = json.RawMessage(c.Markup)
			}
			out = append(out, item)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"channel_id": root.ChannelID, "post_id": root.PostID,
			// Круг разговора — круг корня, и он назван прямо: клиент показывает его
			// строкой, а не догадывается по контейнеру.
			"level": root.Level, "comments": out,
		})
	}
}

// addComment — POST /channels/{channelID}/posts/{postID}/comments.
func addComment(st ChannelStore, n *Notifier) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		root, ok := rootForComments(w, r, st)
		if !ok {
			return
		}
		var req struct {
			Text   string   `json:"text"`
			Nodes  []string `json:"nodes,omitempty"`
			Markup string   `json:"markup,omitempty"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil || req.Text == "" {
			writeErr(w, http.StatusBadRequest, "bad_text", "нужен непустой text")
			return
		}
		nodes := req.Nodes
		if len(nodes) == 0 {
			nodes = []string{req.Text}
		}
		var markup []byte
		if req.Markup != "" {
			if !json.Valid([]byte(req.Markup)) {
				writeErr(w, http.StatusBadRequest, "bad_markup", "markup должен быть валидным JSON")
				return
			}
			markup = []byte(req.Markup)
		}
		id, _ := auth.FromContext(r.Context())
		now := time.Now().UnixMilli()
		postID, err := st.CreateComment(r.Context(), store.ChannelPost{
			ChannelID: root.ChannelID, AuthorID: id.UserID, Text: req.Text,
			Nodes: nodes, Markup: markup, MarkupVersion: 1, CreatedAtUnixMs: now,
		}, root.PostID)
		if errors.Is(err, store.ErrCommentRoot) {
			// Корень исчез между проверкой и вставкой — для пишущего это то же самое,
			// что его не было.
			writeErr(w, http.StatusNotFound, "post_not_found", "записи нет")
			return
		} else if err != nil {
			log.Printf("addComment: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Уведомляем автора корня (ADR-0024, следствие 5): о переносе к себе не
		// уведомляем, потому что автору нечего делать, а на комментарий отвечают.
		// Себе о своём — молчим.
		if n != nil && root.AuthorID != id.UserID {
			n.Users(r.Context(), []string{root.AuthorID}, "channel.comment", map[string]any{
				"channel_id": root.ChannelID, "post_id": root.PostID, "comment_id": postID,
				"author_id": id.UserID, "text": req.Text, "created_at_unix_ms": now,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"post_id": postID, "parent_post_id": root.PostID})
	}
}
