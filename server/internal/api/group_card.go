// Карточка группы для не-участника (ADR-0018, ПЛАН-СОЦИУМА Г2).
//
// Три состояния вместо двух, и различать их обязан сервер:
//
//	не передавали карточку   → 404: группы для тебя не существует
//	передали, но не вступил  → карточка: название и сообщения уровня 0
//	участник                 → всё, как и было
//
// Публичная группа видна всем и без списка: её находят поиском и каталогом, скрывать
// нечего. Список получателей нужен личной — она не ищется никогда.
package api

import (
	"encoding/json"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
)

// maxAudience — предел списка получателей карточки.
//
// Книга контактов у человека конечна; тысяча — с большим запасом. Без предела запрос
// становится способом занять таблицу чужими строками.
const maxAudience = 1000

// cardOpenTo — видит ли этот человек карточку группы, не будучи участником.
func cardOpenTo(deps groupsDeps, r *http.Request, groupKind, groupID, userID string) (bool, error) {
	if groupKind == "public" {
		return true, nil
	}
	return deps.store.GroupCardOpenTo(r.Context(), groupID, userID)
}

// putGroupAudience — PUT /groups/{groupID}/audience: список получателей карточки целиком.
//
// Право — админ и владелец: карточку раздаёт тот, кто распоряжается группой. Список
// приходит как есть и заменяет прежний; пустой означает «убрал со страницы».
func putGroupAudience(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if roleRank[role] < rankAdmin {
			writeErr(w, http.StatusForbidden, "forbidden", "карточку раздают owner и admin")
			return
		}
		var req struct {
			UserIDs []string `json:"user_ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if len(req.UserIDs) > maxAudience {
			writeErr(w, http.StatusBadRequest, "audience_too_large", "получателей карточки больше предела")
			return
		}
		if err := deps.store.SetGroupCardAudience(r.Context(), r.PathValue("groupID"), req.UserIDs); err != nil {
			log.Printf("putGroupAudience: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"count": len(req.UserIDs)})
	}
}

// cardJSON — что видит не-участник: чем группа себя называет, и ничего сверх.
//
// Состава, настроек и счётчиков здесь нет намеренно: они говорят о группе больше, чем
// та решила показать. Сообщения уровня 0 отдаются обычным запросом истории — отдельного
// поля «витрина» не существует (ADR-0019 §4).
func cardJSON(g groupCard) map[string]any {
	return map[string]any{
		"group_id":    g.GroupID,
		"kind":        g.Kind,
		"title":       g.Title,
		"description": g.Description,
		"is_member":   false,
		"can_ask":     true, // единственное действие с чужой личной группой — попроситься
	}
}

// groupCard — поля карточки, которые не-участнику показать можно.
type groupCard struct {
	GroupID     string
	Kind        string
	Title       string
	Description string
}

// answerWithCard отвечает карточкой, если она этому человеку открыта, и 404 иначе.
// Возвращает true, если ответ уже отправлен.
func answerWithCard(deps groupsDeps, w http.ResponseWriter, r *http.Request, g groupCard) bool {
	id, _ := auth.FromContext(r.Context())
	open, err := cardOpenTo(deps, r, g.Kind, g.GroupID, id.UserID)
	if err != nil {
		log.Printf("answerWithCard: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return true
	}
	if !open {
		// Именно «не найдена», а не «нет доступа»: разница в ответах выдала бы
		// существование личной группы тому, кому её не показывали.
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return true
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(cardJSON(g))
	return true
}

// listMyCards — GET /groups/cards: карточки, которые мне открыли.
//
// Вкладка «Друзья» окна 2. Своих групп здесь нет: они в «Каталоге», и показывать одну
// группу в двух списках значило бы заставить человека гадать, чем эти списки различаются.
func listMyCards(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		cards, err := deps.store.CardsFor(r.Context(), id.UserID)
		if err != nil {
			log.Printf("listMyCards: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(cards))
		for _, c := range cards {
			out = append(out, map[string]any{
				"group_id": c.GroupID, "kind": c.Kind, "title": c.Title, "description": c.Description,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"cards": out})
	}
}

