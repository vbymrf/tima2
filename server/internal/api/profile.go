package api

import (
	"encoding/json"
	"errors"
	"io"
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

// setAvatar — PATCH /users/me/avatar {media_id}: поставить или убрать (пустой) аватар.
//
// Картинка идёт через медиа-хранилище тем же путём, что любой файл: init → PUT →
// complete. Здесь только ссылка — и проверка, что медиа своё и загружено до конца:
// поставить чужую картинку своим аватаром нельзя, недогруженную — тоже.
func setAvatar(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			MediaID string `json:"media_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		id, _ := auth.FromContext(r.Context())
		err := deps.store.SetAvatar(r.Context(), id.UserID, req.MediaID)
		switch {
		case errors.Is(err, store.ErrAvatarNotOwned):
			writeErr(w, http.StatusForbidden, "not_owner", "аватаром может стать только своё завершённое медиа")
			return
		case err != nil:
			// Не UUID и прочие беды формата приходят отсюда как ошибка базы: 400, не 500.
			log.Printf("setAvatar: %v", err)
			writeErr(w, http.StatusBadRequest, "bad_media", "media_id не принят")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"avatar_media_id": req.MediaID})
	}
}
