// Доступ по разрешению и срок участия (ADR-0019 §8–§9, ПЛАН-СОЦИУМА Г4).
//
// Третий уровень — единственный, который не расходится по лентам: админ открывает его
// поимённо и может ограничить сроком. Срок закрывает будущее, а не прошлое — прочитанное
// остаётся у человека на устройстве, сервер лишь перестаёт отдавать новое.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"regexp"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// epochRe — «2026-09». Формат тот же, что у эпох escrow: срок и ротация ключа обязаны
// говорить на одном языке, иначе выбытие участника разойдётся со сменой ключа.
var epochRe = regexp.MustCompile(`^\d{4}-(0[1-9]|1[0-2])$`)

// postLevelRequest — POST /groups/{groupID}/level-requests: попросить доступ.
func postLevelRequest(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if role == "" {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		}
		id, _ := auth.FromContext(r.Context())
		state, err := deps.store.AskForLevel(r.Context(), r.PathValue("groupID"), id.UserID, levelByGrant)
		if err != nil {
			log.Printf("postLevelRequest: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"state": state})
	}
}

// listLevelGrants — GET /groups/{groupID}/level-grants: состав глазами админа.
func listLevelGrants(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())

		if roleRank[role] < rankAdmin {
			// Участник спрашивает про себя: «есть ли у меня доступ и до какого срока».
			if role == "" {
				writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
				return
			}
			level, err := deps.store.GrantedLevelFor(r.Context(), groupID, id.UserID)
			if err != nil {
				log.Printf("listLevelGrants: my grant: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"my_level": level})
			return
		}

		list, err := deps.store.ListLevelGrants(r.Context(), groupID)
		if err != nil {
			log.Printf("listLevelGrants: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(list))
		for _, g := range list {
			out = append(out, map[string]any{
				"user_id": g.UserID, "level": g.Level, "state": g.State,
				"until_epoch": g.UntilEpoch, "asked_at": g.AskedAt.UTC(),
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"grants": out})
	}
}

// putLevelGrant — PUT /groups/{groupID}/level-grants/{userID}: открыть доступ или отказать.
//
// `until_epoch` пустой — бессрочно; «2026-10» — до конца октября включительно. Срока в
// днях здесь нет намеренно: он потребовал бы ежедневной фоновой задачи, а месячная
// эпоха уже есть и уже двигает ключ.
func putLevelGrant(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		g, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if roleRank[role] < rankAdmin {
			writeErr(w, http.StatusForbidden, "forbidden", "доступ открывают owner и admin")
			return
		}
		// Третий уровень существует только в публичной группе: в личной уровней всего
		// два, и открывать там нечего (ADR-0019 §2).
		if g.Kind == "private" {
			writeErr(w, http.StatusBadRequest, "no_grants_in_private", "в личной группе доступа по разрешению нет")
			return
		}
		var req struct {
			Grant      *bool  `json:"grant"`
			UntilEpoch string `json:"until_epoch"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Grant == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен grant: true|false")
			return
		}
		if req.UntilEpoch != "" && !epochRe.MatchString(req.UntilEpoch) {
			writeErr(w, http.StatusBadRequest, "bad_epoch", "срок — эпоха вида 2026-10")
			return
		}
		id, _ := auth.FromContext(r.Context())
		userID := r.PathValue("userID")
		if err := deps.store.GrantLevel(r.Context(), r.PathValue("groupID"), userID,
			levelByGrant, req.UntilEpoch, id.UserID, *req.Grant); err != nil {
			if errors.Is(err, store.ErrGrantNotFound) {
				writeErr(w, http.StatusNotFound, "grant_not_found", "разрешение не найдено")
				return
			}
			log.Printf("putLevelGrant: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		// Ответ виден просившему — как и по заявке на вступление.
		if devices, err := deps.store.ListUserDevices(r.Context(), userID); err == nil {
			for _, d := range devices {
				deps.notifier.Device(r.Context(), d.DeviceID, "group.level_answered", map[string]any{
					"group_id": r.PathValue("groupID"), "granted": *req.Grant, "until_epoch": req.UntilEpoch,
				})
			}
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"user_id": userID, "granted": *req.Grant, "until_epoch": req.UntilEpoch})
	}
}

// putMembershipTerm — PUT /groups/{groupID}/members/{userID}/term: срок участия.
//
// Пустой срок снимает ограничение. Выбытие происходит на смене эпохи, когда групповой
// ключ и так меняется, — но читать группу просроченный участник перестаёт немедленно:
// `GroupRole` проверяет срок тем же запросом, которым читает роль.
func putMembershipTerm(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if roleRank[role] < rankAdmin {
			writeErr(w, http.StatusForbidden, "forbidden", "срок ставят owner и admin")
			return
		}
		var req struct {
			UntilEpoch string `json:"until_epoch"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.UntilEpoch != "" && !epochRe.MatchString(req.UntilEpoch) {
			writeErr(w, http.StatusBadRequest, "bad_epoch", "срок — эпоха вида 2026-10")
			return
		}
		err := deps.store.SetMembershipTerm(r.Context(), r.PathValue("groupID"), r.PathValue("userID"), req.UntilEpoch)
		if errors.Is(err, store.ErrNotMember) {
			writeErr(w, http.StatusNotFound, "not_member", "участник не найден")
			return
		} else if err != nil {
			log.Printf("putMembershipTerm: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"user_id": r.PathValue("userID"), "until_epoch": req.UntilEpoch})
	}
}
