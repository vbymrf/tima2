package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// me — GET /users/me: кто я (ПЛАН-КОНТАКТОВ.md, Д8).
//
// До этой ручки экран профиля открывался ПУСТЫМ: имя и ник клиент знал только те,
// что сам сейчас ввёл, а телефона не знал вовсе — сессия хранит userId, deviceId и
// токен. Человек, открывший профиль на втором устройстве, видел незаполненную форму
// поверх заполненного аккаунта.
//
// Свой телефон отдаётся только самому себе: чужой по user_id по-прежнему недоступен
// (resolveNames отдаёт его лишь собеседникам по переписке).
func me(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		m, err := deps.store.Me(r.Context(), id.UserID)
		switch {
		case errors.Is(err, store.ErrUserUnknown):
			writeErr(w, http.StatusNotFound, "user_not_found", "личность не найдена")
			return
		case err != nil:
			log.Printf("me: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"user_id":         id.UserID,
			"phone":           m.Phone,
			"display_name":    m.Name,
			"nickname":        m.Nickname,
			"nickname_locked": m.NicknameLocked,
			"avatar_media_id": m.AvatarMediaID,
		})
	}
}
