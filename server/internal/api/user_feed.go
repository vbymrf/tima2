// Лента пользователя: страница, куда человек кладёт своё и принесённое (ADR-0019 §7,
// ПЛАН-СОЦИУМА Г8).
//
// ── ЛЕНТА ЕСТЬ КАНАЛ ────────────────────────────────────────────────────────
//
// Решение заказчика 2026-09-04: лента — не новая подсистема, а канал, который ищут по
// человеку. Односторонняя трансляция: пишет владелец, читают остальные. Оттого посты,
// подписчики и удаление достались готовыми, а добавились три вещи — связь «человек → его
// канал», пост-ссылка вместо копии и граница выдачи по кругу.
//
// ── ПОДПИСКА АВТОМАТИЧЕСКАЯ ─────────────────────────────────────────────────
//
// Кнопки «подписаться» на странице нет: подписчиком становится тот, кто её открыл.
//
// ── КРУГ «СВОИМ» ────────────────────────────────────────────────────────────
//
// Здесь было написано, что своих на сервере нет, а появятся друзья — поменяется
// граница выдачи, а не устройство ленты. Друзья появились (Д1б), и поменялась ровно
// граница: друг владельца видит уровень 2 «своим», посторонний — только 1 «всем».
// Направление именно такое: круг задаёт владелец страницы, а не читатель. Иначе
// «добавь его в друзья» открывало бы чужое узкое.
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// FeedStore — что ленте нужно от хранилища.
//
// Узкий интерфейс у потребителя, как у каналов и групп: сужение или расширение — правка
// этого файла, а не общего типа, который читают все.
type FeedStore interface {
	FeedOf(ctx context.Context, userID string) (string, error)
	EnsureFeed(ctx context.Context, userID, title string) (string, error)
	SubscribeToFeed(ctx context.Context, channelID, userID string) error
	CarryToFeed(ctx context.Context, channelID, carrierID, srcGroupID string, srcMessageID int64, level, visibleTo int16) (uint64, error)
	ListFeed(ctx context.Context, channelID string, before uint64, limit int, maxLevel int16) ([]store.FeedItem, error)
	RemoveFeedItem(ctx context.Context, channelID string, postID uint64, ownerID string) error

	// Круг «своим» на чужой странице: друг владельца — свой (Д1б).
	IsFriend(ctx context.Context, ownerUserID, otherUserID string) (bool, error)

	// Право видеть оригинал проверяется до переноса: унести можно только то, что тебе
	// показали. Роль и поимённое разрешение отвечают на этот вопрос вместе.
	GroupRole(ctx context.Context, groupID, userID string) (string, error)
	GrantedLevelFor(ctx context.Context, groupID, userID string) (int16, error)
}

var _ FeedStore = (*store.Store)(nil)

// RegisterFeeds — четыре маршрута страницы.
func RegisterFeeds(mux *http.ServeMux, st FeedStore, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/feed", requireDevice(myFeed(st)))
	mux.HandleFunc("POST /api/v1/users/me/feed/items", requireDevice(carryToFeed(st)))
	mux.HandleFunc("DELETE /api/v1/users/me/feed/items/{postID}", requireDevice(removeFeedItem(st)))
	// Позже «me», иначе «me» будет принято за идентификатор пользователя.
	mux.HandleFunc("GET /api/v1/users/{userID}/feed", requireDevice(userFeed(st)))
}

// myFeed — GET /users/me/feed: своя страница, создаётся при первом обращении.
func myFeed(st FeedStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		channelID, err := st.EnsureFeed(r.Context(), id.UserID, "Лента")
		if err != nil {
			log.Printf("myFeed: ensure: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Своя страница видна целиком: круги нужны, чтобы показывать другим, а не себе.
		writeFeed(w, st, r, channelID, levelByGrant)
	}
}

// userFeed — GET /users/{userID}/feed: чужая страница.
func userFeed(st FeedStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		owner := r.PathValue("userID")
		channelID, err := st.FeedOf(r.Context(), owner)
		if errors.Is(err, store.ErrNoFeed) {
			// Ленты нет — показывать нечего. Не ошибка человека и не тайна: страницу
			// просто ещё не завели.
			writeErr(w, http.StatusNotFound, "feed_not_found", "у этого человека нет ленты")
			return
		}
		if err != nil {
			log.Printf("userFeed: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if owner == id.UserID {
			writeFeed(w, st, r, channelID, levelByGrant)
			return
		}
		// Подписка случается сама, от факта чтения. Молчаливый отказ подписки не должен
		// ломать показ: страница важнее учёта.
		if err := st.SubscribeToFeed(r.Context(), channelID, id.UserID); err != nil {
			log.Printf("userFeed: subscribe: %v", err)
		}
		// Свой — тот, кого владелец добавил к себе в друзья. Ошибка чтения списка не
		// должна закрывать страницу: показываем открытое, как постороннему.
		граница := levelEveryone
		if свой, err := st.IsFriend(r.Context(), owner, id.UserID); err != nil {
			log.Printf("userFeed: друзья %s: %v", owner, err)
		} else if свой {
			граница = levelMembers
		}
		writeFeed(w, st, r, channelID, граница)
	}
}

func writeFeed(w http.ResponseWriter, st FeedStore, r *http.Request, channelID string, maxLevel int16) {
	var before uint64
	if v := r.URL.Query().Get("before"); v != "" {
		before, _ = strconv.ParseUint(v, 10, 64)
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

	items, err := st.ListFeed(r.Context(), channelID, before, limit, maxLevel)
	if err != nil {
		log.Printf("writeFeed: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	b64 := base64.RawURLEncoding
	out := make([]map[string]any, 0, len(items))
	for _, it := range items {
		out = append(out, map[string]any{
			"post_id":            it.PostID,
			"level":              it.Level,
			"created_at_unix_ms": it.CreatedAtUnixMs,
			"author_id":          it.AuthorID,
			"nodes":              it.Nodes,
			// Пусто у своей записи, заполнено у принесённой. По этим трём полям экран
			// показывает её «от лица группы», а не как свою.
			"carried_by":     it.CarriedBy,
			"ref_group_id":   it.RefGroupID,
			"ref_message_id": it.RefMessageID,
			"source_title":   it.SourceTitle,
			// Содержимое оригинала как есть: подпись считается по этим байтам, и
			// пересобирать их нельзя.
			"payload":       b64.EncodeToString(it.Payload),
			"signature":     b64.EncodeToString(it.Signature),
			"sender_device": it.SenderDevice,
			"kind":          it.Kind,
		})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"channel_id": channelID, "items": out})
}

// carryToFeed — POST /users/me/feed/items: принести чужую запись к себе.
func carryToFeed(st FeedStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			GroupID   string `json:"group_id"`
			MessageID int64  `json:"message_id"`
			Level     *int16 `json:"level"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.GroupID == "" || req.MessageID <= 0 {
			writeErr(w, http.StatusBadRequest, "bad_request", "нужны group_id и message_id")
			return
		}
		// Круг у себя назначает принёсший (ADR-0019 §7). Не назвал — «всем»: страница по
		// смыслу открытая, а «всем и всегда» это витрина группы, и она не переносится.
		level := levelEveryone
		if req.Level != nil {
			level = *req.Level
		}
		if level < levelPublicShowcase || level > levelByGrant {
			writeErr(w, http.StatusBadRequest, "bad_level", "круг вне 0…3")
			return
		}

		// **Унести можно только то, что тебе показали.** Иначе перенос стал бы способом
		// достать запись, которую сервер тебе не отдавал.
		role, err := st.GroupRole(r.Context(), req.GroupID, id.UserID)
		if err != nil && !errors.Is(err, store.ErrNotMember) {
			log.Printf("carryToFeed: role: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		visible := maxLevelFor(role)
		if role != "" && visible < levelByGrant {
			if granted, err := st.GrantedLevelFor(r.Context(), req.GroupID, id.UserID); err != nil {
				log.Printf("carryToFeed: grant: %v", err)
			} else if granted > visible {
				visible = granted
			}
		}

		channelID, err := st.EnsureFeed(r.Context(), id.UserID, "Лента")
		if err != nil {
			log.Printf("carryToFeed: ensure: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		postID, err := st.CarryToFeed(r.Context(), channelID, id.UserID, req.GroupID, req.MessageID, level, visible)
		switch {
		case errors.Is(err, store.ErrGroupMessageNotFound):
			writeErr(w, http.StatusNotFound, "message_not_found", "записи нет")
			return
		case errors.Is(err, store.ErrCannotCarry):
			writeErr(w, http.StatusForbidden, "cannot_carry", "эту запись нельзя унести к себе")
			return
		case err != nil:
			log.Printf("carryToFeed: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		w.WriteHeader(http.StatusCreated)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"post_id": postID, "level": level})
	}
}

// removeFeedItem — DELETE /users/me/feed/items/{postID}: убрать со своей страницы.
func removeFeedItem(st FeedStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		postID, err := strconv.ParseUint(r.PathValue("postID"), 10, 64)
		if err != nil || postID == 0 {
			writeErr(w, http.StatusBadRequest, "bad_post_id", "post_id — целое число")
			return
		}
		channelID, err := st.FeedOf(r.Context(), id.UserID)
		if errors.Is(err, store.ErrNoFeed) {
			writeErr(w, http.StatusNotFound, "feed_not_found", "ленты нет")
			return
		}
		if err != nil {
			log.Printf("removeFeedItem: feed: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if err := st.RemoveFeedItem(r.Context(), channelID, postID, id.UserID); err != nil {
			if errors.Is(err, store.ErrGroupMessageNotFound) {
				writeErr(w, http.StatusNotFound, "item_not_found", "записи нет на вашей странице")
				return
			}
			log.Printf("removeFeedItem: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
