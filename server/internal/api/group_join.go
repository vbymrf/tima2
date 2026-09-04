// Заявки на вступление (ADR-0018 п. 7, ПЛАН-СОЦИУМА Г3).
//
// Личная группа не ищется и вступить в неё самому некуда. Всё, что может человек,
// получивший карточку, — попросить админа. Отсюда право на просьбу: быть в аудитории
// карточки. Кому карточку не передавали, для того группы не существует, и заявка от него
// невозможна не по запрету, а потому что он о группе не знает.
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

// postJoinRequest — POST /groups/{groupID}/join-requests.
func postJoinRequest(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		g, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if role != "" {
			writeErr(w, http.StatusConflict, "already_member", "вы уже участник")
			return
		}
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())

		// Право просить = право видеть карточку. Проверка та же, что в getGroup, и
		// ответ тот же — 404: иначе «нельзя просить» выдало бы существование группы.
		open, err := cardOpenTo(deps, r, g.Kind, groupID, id.UserID)
		if err != nil {
			log.Printf("postJoinRequest: card audience: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !open {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		}

		state, err := deps.store.AskToJoin(r.Context(), groupID, id.UserID)
		if err != nil {
			log.Printf("postJoinRequest: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		// Админам — событие в очередь: заявка не должна ждать, пока кто-то догадается
		// заглянуть в список.
		if devices, err := deps.store.ActiveMemberDevices(r.Context(), groupID, ""); err == nil {
			for _, dev := range devices {
				deps.notifier.Device(r.Context(), dev, "group.join_requested", map[string]any{
					"group_id": groupID, "user_id": id.UserID,
				})
			}
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"state": state})
	}
}

// listJoinRequests — GET /groups/{groupID}/join-requests: очередь админа.
//
// Просивший спрашивает своё состояние тем же маршрутом и получает одну строку — свою.
// Отдельный маршрут ради этого не заводится: вопрос один и тот же, разнится только то,
// на что человек имеет право смотреть.
func listJoinRequests(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		g, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())

		if roleRank[role] >= rankAdmin {
			list, err := deps.store.ListJoinRequests(r.Context(), groupID)
			if err != nil {
				log.Printf("listJoinRequests: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			out := make([]map[string]any, 0, len(list))
			for _, jr := range list {
				out = append(out, map[string]any{
					"user_id": jr.UserID, "state": jr.State, "asked_at": jr.AskedAt.UTC(),
				})
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"requests": out})
			return
		}

		// Не админ: своё состояние — и только если карточка ему открыта.
		open, err := cardOpenTo(deps, r, g.Kind, groupID, id.UserID)
		if err != nil {
			log.Printf("listJoinRequests: card audience: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !open && role == "" {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		}
		state, err := deps.store.MyJoinRequestState(r.Context(), groupID, id.UserID)
		if err != nil {
			log.Printf("listJoinRequests: my state: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"my_state": state})
	}
}

// patchJoinRequest — PATCH /groups/{groupID}/join-requests/{userID}: принять или отказать.
//
// Принятая заявка добавляет участника ОБЫЧНЫМ путём — тем же, что «позвать». Инвариант
// ADR-0017 не знает, откуда пришёл участник: смена состава обязана сменить ключ, и
// напоминание об этом уходит так же, как при добавлении.
func patchJoinRequest(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if roleRank[role] < rankAdmin {
			writeErr(w, http.StatusForbidden, "forbidden", "на заявки отвечают owner и admin")
			return
		}
		groupID, userID := r.PathValue("groupID"), r.PathValue("userID")

		var req struct {
			Accept *bool `json:"accept"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Accept == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен accept: true|false")
			return
		}

		state := "declined"
		if *req.Accept {
			state = "accepted"
		}
		id, _ := auth.FromContext(r.Context())
		if err := deps.store.AnswerJoinRequest(r.Context(), groupID, userID, state, id.UserID); err != nil {
			if errors.Is(err, store.ErrJoinRequestNotFound) {
				writeErr(w, http.StatusNotFound, "request_not_found", "заявки нет или на неё уже ответили")
				return
			}
			log.Printf("patchJoinRequest: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		if *req.Accept {
			err := deps.store.AddGroupMember(r.Context(), groupID, userID, "member")
			if err != nil && !errors.Is(err, store.ErrUserUnknown) {
				log.Printf("patchJoinRequest: add member: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			// Состав сменился — ключ обязан смениться. Сервер этого не умеет (ключа он не
			// видит), поэтому просит участников: кто первым откроет приложение, тот и
			// ротирует (ADR-0017 §2).
			if devices, err := deps.store.ActiveMemberDevices(r.Context(), groupID, ""); err == nil {
				remindAboutRotation(deps, r.Context(), groupID, devices)
			}
		}

		// Ответ виден просившему — решение заказчика 2026-09-04. Молчаливый отказ
		// неотличим от «не дошло», и человек попросит снова.
		if devices, err := deps.store.ListUserDevices(r.Context(), userID); err == nil {
			for _, d := range devices {
				deps.notifier.Device(r.Context(), d.DeviceID, "group.join_answered", map[string]any{
					"group_id": groupID, "state": state,
				})
			}
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"user_id": userID, "state": state})
	}
}
