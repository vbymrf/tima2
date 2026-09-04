// Уровень сообщения (ADR-0019): кому сервер отдаёт запись.
//
// Порядок доступа лежит на сообщении, а не на контейнере, поэтому выдача — сравнение
// `уровень ≤ право читателя`, а не ветвление по виду группы, роли и подписке. Контейнер
// при этом остаётся источником ПРАВ: кто пишет, кто модерирует, кто исключает — свойства
// группы, а не сообщения.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Шкала монотонная: чем больше номер, тем уже круг.
const (
	levelSecret         int16 = -1 // зашифровано групповым ключом; сервер не решает ничего
	levelPublicShowcase int16 = 0  // всем и всегда — описание группы
	levelEveryone       int16 = 1  // всем, даже не вступившим; попадает в ленты
	levelMembers        int16 = 2  // вступившим
	levelByGrant        int16 = 3  // по разрешению, выданному конкретному участнику
)

// maxPlainPayloadBytes — предел открытого сообщения.
//
// Заказчик задал предел содержимого: 4096 знаков UTF-16 на сообщение, 1024 на подпись к
// медиа. Сервер знаков не видит — payload для него байты, и разбирать protobuf ради
// подсчёта он не станет. Поэтому здесь грубая граница: 4096 знаков в UTF-8 — это до
// 12 КБ, плюс разметка и запас. Точный предел проверяет клиент, который знает содержимое.
//
// Граница нужна не для красоты: открытый текст на сервере без предела превращается в
// бесплатный хостинг.
const maxPlainPayloadBytes = 16 << 10

// defaultLevel — уровень, если клиент его не прислал.
//
// В приватной группе всё, кроме описания, зашифровано — значит шифр. В публичной шифра
// нет вовсе, а прежняя выдача отдавала сообщения только участникам: это ровно «вступившим».
// Так старый клиент, не знающий про уровни, продолжает вести себя как раньше.
func defaultLevel(groupKind string) int16 {
	if groupKind == "private" {
		return levelSecret
	}
	return levelMembers
}

// maxLevelFor — граница выдачи по роли в группе.
//
// Владелец, админ и модератор видят всё: они распоряжаются содержимым и отвечают за него.
// Участник видит до «вступившим»; третий уровень открывается ему поимённо — это Г4, и до
// тех пор граница участника не двигается.
//
// Пустая роль здесь не встречается: не-участника обработчики отсекают раньше (404).
// Значение для неё возвращается на случай будущей карточки (Г2), где посторонний из
// аудитории получит уровни 0 и 1.
func maxLevelFor(role string) int16 {
	if roleRank[role] >= rankModerator {
		return levelByGrant
	}
	if role == "" {
		return levelEveryone
	}
	return levelMembers
}

// patchGroupMessageLevel — PATCH /groups/{groupID}/messages/{messageID}.
//
// Единственное действие — сузить круг. Расширять нельзя никому: понизить уровень значит
// выложить наружу написанное для узкого круга, а согласия автора на это никто не давал
// (ADR-0019 §6). Модерация обязана уметь прятать и не должна уметь публиковать.
func patchGroupMessageLevel(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		g, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if role == "" {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		}
		groupID := r.PathValue("groupID")
		messageID, err := strconv.ParseInt(r.PathValue("messageID"), 10, 64)
		if err != nil || messageID <= 0 {
			writeErr(w, http.StatusBadRequest, "bad_message_id", "message_id — целое число")
			return
		}

		var req struct {
			Level *int16 `json:"level"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.Level == nil {
			writeErr(w, http.StatusBadRequest, "no_level", "нужен level")
			return
		}
		level := *req.Level
		if level < levelSecret || level > levelByGrant {
			writeErr(w, http.StatusBadRequest, "bad_level", "уровень вне диапазона -1…3")
			return
		}
		// Сузить до шифра нельзя: сообщение уже лежит открытым, и зашифровать его
		// задним числом сервер не может — ключа он не видит по построению.
		if level == levelSecret {
			writeErr(w, http.StatusBadRequest, "cannot_encrypt_later", "нельзя сделать открытое сообщение зашифрованным")
			return
		}
		if g.Kind == "private" && level > levelPublicShowcase {
			writeErr(w, http.StatusBadRequest, "level_in_private", "в личной группе бывают только уровни -1 и 0")
			return
		}

		// Своё сообщение сужает автор; чужое — админ и владелец. Модератор удалять
		// умеет, а менять уровень чужому — нет: это распоряжение содержимым, и таблица
		// прав отдаёт его администрации (ADR-0019, роли).
		id, _ := auth.FromContext(r.Context())
		senderID, err := deps.store.NarrowGroupMessageLevel(
			r.Context(), groupID, messageID, level, id.UserID, roleRank[role] >= rankAdmin)
		switch {
		case errors.Is(err, store.ErrGroupMessageNotFound):
			writeErr(w, http.StatusNotFound, "message_not_found", "сообщение не найдено в группе")
			return
		case errors.Is(err, store.ErrGroupMessageForeign):
			writeErr(w, http.StatusForbidden, "not_allowed", "уровень чужого сообщения меняет админ")
			return
		case errors.Is(err, store.ErrGroupMessageNotNarrowed):
			writeErr(w, http.StatusConflict, "cannot_widen", "уровень можно только сузить")
			return
		case err != nil:
			log.Printf("patchGroupMessageLevel: narrow: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		// Автору говорят словами, что его сообщение сузили. Оповещение приходит туда же,
		// где живёт сообщение, — в саму группу: там же админ может объяснить причину.
		// Это работает, потому что админ СУЗИЛ: вред предотвращён, а не нанесён.
		if senderID != id.UserID {
			devices, err := deps.store.ActiveMemberDevices(r.Context(), groupID, "")
			if err != nil {
				log.Printf("patchGroupMessageLevel: member devices: %v", err)
			}
			for _, dev := range devices {
				deps.notifier.Device(r.Context(), dev, "message.level_narrowed", map[string]any{
					"group_id":   groupID,
					"message_id": messageID,
					"level":      level,
					"by":         id.UserID,
				})
			}
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": messageID, "level": level})
	}
}
