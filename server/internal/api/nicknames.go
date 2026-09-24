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
		case errors.Is(err, store.ErrNicknameLocked):
			// Тоже 409, но другой код: место не занято, занята ПОПЫТКА — эта
			// личность свой ник уже задала. Клиент показывает ник текстом, а не полем.
			writeErr(w, http.StatusConflict, "nickname_locked",
				"ник задаётся один раз; сменить его сможет новая личность после «Начать заново»")
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

// searchNicknames — GET /nicknames?q=: точное совпадение либо похожие (Л10).
//
// Единственный способ найти человека, у которого номера нет вовсе, — а такие есть и
// они полноправны: у виртуального аккаунта только ник.
//
// ── ЦЕНА НАЗВАНА ЗАРАНЕЕ ────────────────────────────────────────────────────
//
// Это перебор пространства имён: кто угодно может вытягивать чужие ники по буквам.
// Поэтому три предела — не аккуратность, а часть барьера от спама, который ник и
// открывает (§5, §6 плана):
//
//	минимум три знака   — по одной букве выдачи не бывает, бывает выгрузка каталога
//	предел выдачи       — десяток; «нашлось много» это повод дописать, а не список
//	предел частоты      — по устройству, вдвое строже точного поиска
//
// Предел частоты по УСТРОЙСТВУ, а не по адресу: за одним адресом сидит подъезд, а
// перебирают каталог с одного аккаунта.
func searchNicknames(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if deps.limiter != nil &&
			!rateLimit(deps.limiter(), w, r, "nicksearch:"+id.DeviceID, rlNickSearch) {
			return
		}
		hits, err := deps.store.SearchNicknames(r.Context(), r.URL.Query().Get("q"), store.MaxNicknameHits)
		if errors.Is(err, store.ErrNicknameBad) {
			writeErr(w, http.StatusBadRequest, "bad_query",
				"запрос — от трёх знаков: латиница, цифры, подчёркивание")
			return
		} else if err != nil {
			log.Printf("searchNicknames: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]string, 0, len(hits))
		for _, hit := range hits {
			out = append(out, map[string]string{"user_id": hit.UserID, "nickname": hit.Nickname})
		}
		w.Header().Set("Content-Type", "application/json")
		// Выдача ПОЛНАЯ, без отбора по спискам спрашивающего: кого он убрал или
		// заблокировал, знает только его устройство, и сервер об этом не узнаёт
		// (решение заказчика 2026-09-24). Пометку рисует клиент — Л18.
		_ = json.NewEncoder(w).Encode(map[string]any{"found": out})
	}
}

// rlNickSearch — поисков по части ника с устройства за окно. Вдвое строже точного
// поиска: тот отвечает про один ник, этот перебирает каталог.
const rlNickSearch = 30

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
