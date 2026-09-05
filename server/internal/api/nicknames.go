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

// Ник: занять, проверить занятость, найти по нему человека (ПЛАН-КОНТАКТОВ.md, Д1).
//
// Три ручки, и третья — единственный способ найти человека, чьего номера не знаешь.
// Поиска по имени на сервере нет и не будет: имя пишет о человеке кто-то другой,
// и выдавать по нему каталог людей нельзя (решение заказчика 2026-09-05).

// setNickname — PATCH /users/me/nickname {nickname}.
func setNickname(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Nickname string `json:"nickname"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		id, _ := auth.FromContext(r.Context())
		err := deps.store.SetNickname(r.Context(), id.UserID, req.Nickname)
		switch {
		case errors.Is(err, store.ErrNicknameBad):
			writeErr(w, http.StatusBadRequest, "bad_nickname",
				"ник — от 10 до 20 знаков: латиница, цифры, подчёркивание")
			return
		case errors.Is(err, store.ErrNicknameTaken):
			// 409, а не 400: запрос правильный, занято место. Клиент по коду
			// различает «исправь написание» и «придумай другой».
			writeErr(w, http.StatusConflict, "nickname_taken", "этот ник уже занят")
			return
		case err != nil:
			log.Printf("setNickname: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"nickname": req.Nickname})
	}
}

// nicknameFree — GET /nicknames/{nick}/free: свободен ли.
//
// Отвечает до нажатия «Сохранить»: узнать о занятости после отправки формы значит
// потерять уже введённое. Существование аккаунта эта ручка раскрывает — но ровно то
// же раскрывает и поиск по нику, ради которого ник и заводится.
func nicknameFree(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		free, err := deps.store.NicknameFree(r.Context(), r.PathValue("nick"))
		if errors.Is(err, store.ErrNicknameBad) {
			writeErr(w, http.StatusBadRequest, "bad_nickname",
				"ник — от 10 до 20 знаков: латиница, цифры, подчёркивание")
			return
		} else if err != nil {
			log.Printf("nicknameFree: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]bool{"free": free})
	}
}

// lookupByNickname — GET /nicknames/{nick}: чей это ник.
//
// Отдельным маршрутом, а не параметром к /users/lookup: тот отвечает по номеру,
// то есть тому, кто номер и так знает. Здесь другая природа ответа, и смешивать
// их в одной ручке значит однажды случайно распространить предел частоты одного
// на другое.
func lookupByNickname(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID, err := deps.store.FindUserByNickname(r.Context(), r.PathValue("nick"))
		switch {
		case errors.Is(err, store.ErrNicknameBad):
			writeErr(w, http.StatusBadRequest, "bad_nickname",
				"ник — от 10 до 20 знаков: латиница, цифры, подчёркивание")
			return
		case errors.Is(err, store.ErrUserUnknown):
			writeErr(w, http.StatusNotFound, "user_not_found", "никто не занял этот ник")
			return
		case err != nil:
			log.Printf("lookupByNickname: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"user_id": userID})
	}
}
