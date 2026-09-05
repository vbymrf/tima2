package api

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Друзья — список аккаунта (ПЛАН-КОНТАКТОВ.md, Д1б).
//
// Список **свой**: чужой не читается и не правится. Иначе это был бы социальный
// справочник, где по одному знакомому раскручивается круг общения человека.
//
// Добавление и удаление тянут за собой подписку на ленту: «есть в контактах — друг»
// читается в обе стороны (решение заказчика 2026-09-05). Подписка живёт в ленте, и
// делается она здесь явно, а не спрятана в хранилище друзей: два хранилища, тихо
// правящих друг друга, расходятся при первой же ошибке.

// FriendStore — что друзьям нужно от хранилища.
type FriendStore interface {
	AddFriend(ctx context.Context, ownerUserID, friendUserID string) error
	RemoveFriend(ctx context.Context, ownerUserID, friendUserID string) error
	ListFriends(ctx context.Context, ownerUserID string) ([]string, error)

	// Лента друга: подписаться при добавлении, отписаться при удалении
	EnsureFeed(ctx context.Context, userID, title string) (string, error)
	FeedOf(ctx context.Context, userID string) (string, error)
	SubscribeToFeed(ctx context.Context, channelID, userID string) error
	// Отписка — общая для каналов: лента человека и есть канал (Г8), и второй
	// метод «отписаться, но от ленты» отличался бы от этого только названием.
	Unsubscribe(ctx context.Context, channelID, userID string) error
}

var _ FriendStore = (*store.Store)(nil)

// RegisterFriends — три маршрута.
func RegisterFriends(mux *http.ServeMux, st FriendStore, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/friends", requireDevice(listFriends(st)))
	mux.HandleFunc("POST /api/v1/users/me/friends", requireDevice(addFriend(st)))
	mux.HandleFunc("DELETE /api/v1/users/me/friends/{userID}", requireDevice(removeFriend(st)))
}

func listFriends(st FriendStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		ids, err := st.ListFriends(r.Context(), id.UserID)
		if err != nil {
			log.Printf("listFriends: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if ids == nil {
			ids = []string{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"friends": ids})
	}
}

// addFriend — POST /users/me/friends {user_id}.
//
// Подписка на ленту оформляется тут же: человек добавил друга, чтобы читать его,
// а не чтобы совершить второе действие.
func addFriend(st FriendStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			UserID string `json:"user_id"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil || req.UserID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
			return
		}
		id, _ := auth.FromContext(r.Context())
		err := st.AddFriend(r.Context(), id.UserID, req.UserID)
		switch {
		case errors.Is(err, store.ErrSelfFriend):
			writeErr(w, http.StatusBadRequest, "self_friend", "себя добавлять не нужно")
			return
		case errors.Is(err, store.ErrUserUnknown):
			writeErr(w, http.StatusNotFound, "user_not_found", "такого пользователя нет")
			return
		case err != nil:
			log.Printf("addFriend: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		// Лента друга может ещё не существовать: она заводится лениво, при первой
		// записи. Заводим её сами — иначе подписка ждала бы, пока он что-то положит,
		// и первая же запись прошла бы мимо подписчика.
		channelID, err := st.EnsureFeed(r.Context(), req.UserID, "Лента")
		if err != nil {
			log.Printf("addFriend: лента %s: %v", req.UserID, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if err := st.SubscribeToFeed(r.Context(), channelID, id.UserID); err != nil {
			log.Printf("addFriend: подписка: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusCreated)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"user_id": req.UserID, "subscribed": true})
	}
}

// removeFriend — DELETE /users/me/friends/{userID}.
//
// Снимает и подписку: иначе лента копит тех, кого человек уже убрал, и он не
// понимает, откуда они там (решение заказчика 2026-09-05).
func removeFriend(st FriendStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		other := r.PathValue("userID")
		if err := st.RemoveFriend(r.Context(), id.UserID, other); err != nil {
			log.Printf("removeFriend: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Ленты может не быть вовсе — тогда и отписываться не от чего.
		if channelID, err := st.FeedOf(r.Context(), other); err == nil {
			if err := st.Unsubscribe(r.Context(), channelID, id.UserID); err != nil {
				log.Printf("removeFriend: отписка: %v", err)
			}
		} else if !errors.Is(err, store.ErrNoFeed) {
			log.Printf("removeFriend: лента %s: %v", other, err)
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
