// Выдача одного сообщения по номеру — один механизм доступа по ссылке (ПЛАН-(РС) РС3,
// doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §11.3, ADR-0019 §7 в уточнении 2026-10-18).
//
// Ответ, ответ `@Ник`, вставленное из подборки, подкладка и «Перенести себе» несут ссылку на
// сообщение, а не копию. Содержимое клиент берёт здесь, и сервер применяет то же правило, что у
// истории: `уровень ≤ право читателя`. Отдельной «проверки ссылок» нет — есть обычная выдача.
//
// Ответ — либо само сообщение (`message`), либо заглушка (`unavailable`): клиент рисует
// «Недоступно — что сделать». Подложенное судится по СВОЕМУ уровню, а не по уровню сообщения,
// в которое его вставили.
//
// **Что заглушка говорит, а что нет.** В открытом контуре (публичная группа, канал) — уровень и
// причину: существование открытой записи не секрет, а «что сделать» без уровня не составить. В
// закрытом (личная переписка, чужая личная группа) — ничего, одинаково на любой номер: иначе
// перебором номеров можно было бы выведать, что переписка или группа существует (то же правило,
// что «не найдено вместо нельзя» у комментариев).
//
// Новые маршруты — расширение API; прежние ничего не меняют.
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

// Причины заглушки.
const (
	unavailableLevel   = "level"   // уровень выше права читателя
	unavailableMissing = "missing" // нет: удалено или не было
	unavailableClosed  = "closed"  // закрытый контур: подробностей нет
)

// writeUnavailable — заглушка вместо сообщения. level == nil — уровень не называется.
func writeUnavailable(w http.ResponseWriter, number uint64, reason string, level *int16) {
	stub := map[string]any{"message_id": number, "reason": reason}
	if level != nil {
		stub["level"] = *level
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"unavailable": stub})
}

func pathNumber(w http.ResponseWriter, r *http.Request, name string) (uint64, bool) {
	n, err := strconv.ParseUint(r.PathValue(name), 10, 64)
	if err != nil || n == 0 || n >= 1<<63 {
		writeErr(w, http.StatusBadRequest, "bad_message_id", "номер сообщения — целое положительное")
		return 0, false
	}
	return n, true
}

// getPersonalMessage — GET /chats/{chatID}/messages/{messageID}.
//
// Отдаётся только сообщение, у которого есть обёртка для устройства просящего, — тем же
// соединением, что история (`ListMessages`). Нет обёртки — заглушка без подробностей.
func getPersonalMessage(deps messagesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		number, ok := pathNumber(w, r, "messageID")
		if !ok {
			return
		}
		id, _ := auth.FromContext(r.Context())
		msgs, err := deps.store.ListMessages(r.Context(), r.PathValue("chatID"), id.DeviceID, number+1, 1)
		if err != nil {
			log.Printf("getPersonalMessage: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if len(msgs) == 0 || msgs[0].MessageID != number {
			writeUnavailable(w, number, unavailableClosed, nil)
			return
		}
		it, err := personalMessageItem(msgs[0], id.DeviceID)
		if err != nil {
			log.Printf("getPersonalMessage: marshal: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка сериализации")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"message": it})
	}
}

// getGroupMessage — GET /groups/{groupID}/messages/{messageID}.
func getGroupMessage(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		number, ok := pathNumber(w, r, "messageID")
		if !ok {
			return
		}
		g, role, ok := groupAndRoleQuiet(deps, w, r)
		if !ok {
			return
		}
		if g.GroupID == "" { // группы нет — как у закрытой: подробностей не даём
			writeUnavailable(w, number, unavailableClosed, nil)
			return
		}
		if role == "" {
			id, _ := auth.FromContext(r.Context())
			open, err := cardOpenTo(deps, r, g.Kind, g.GroupID, id.UserID)
			if err != nil {
				log.Printf("getGroupMessage: card audience: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if !open || g.Kind == "private" {
				writeUnavailable(w, number, unavailableClosed, nil)
				return
			}
		}
		max := readerMaxLevel(deps, r, role)
		// Одним запросом с верхней границей: так известен и сам уровень сообщения, если оно
		// читателю не положено. Сравнение с правом — здесь, а не в SQL.
		msgs, err := deps.store.ListGroupMessages(r.Context(), g.GroupID, 0, int64(number)+1, 1, levelByGrant, false)
		if err != nil {
			log.Printf("getGroupMessage: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if len(msgs) == 0 || msgs[0].MessageID != int64(number) {
			if g.Kind == "private" {
				writeUnavailable(w, number, unavailableClosed, nil)
			} else {
				writeUnavailable(w, number, unavailableMissing, nil)
			}
			return
		}
		m := msgs[0]
		if m.Level > max {
			level := m.Level
			writeUnavailable(w, number, unavailableLevel, &level)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"message": groupMessageJSON(m)})
	}
}

// groupAndRoleQuiet — как groupAndRole, но отсутствие группы — не 404, а пустая группа: для
// выдачи по номеру «нет группы» неотличимо от «закрыто», иначе перебором выведывалась бы личная
// группа.
func groupAndRoleQuiet(deps groupsDeps, w http.ResponseWriter, r *http.Request) (store.Group, string, bool) {
	g, err := deps.store.GetGroup(r.Context(), r.PathValue("groupID"))
	if err != nil {
		if errors.Is(err, store.ErrGroupNotFound) {
			return store.Group{}, "", true
		}
		log.Printf("getGroupMessage: group: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return g, "", false
	}
	id, _ := auth.FromContext(r.Context())
	role, err := deps.store.GroupRole(r.Context(), g.GroupID, id.UserID)
	if err != nil && !errors.Is(err, store.ErrNotMember) {
		log.Printf("getGroupMessage: role: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return g, "", false
	}
	return g, role, true
}

// getChannelPost — GET /channels/{channelID}/posts/{postID}.
func getChannelPost(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		number, ok := pathNumber(w, r, "postID")
		if !ok {
			return
		}
		channelID := r.PathValue("channelID")
		ch, err := st.GetChannel(r.Context(), channelID)
		if errors.Is(err, store.ErrChannelNotFound) {
			writeUnavailable(w, number, unavailableClosed, nil)
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if !ch.IsPublic {
			if sub, _ := st.IsSubscribed(r.Context(), channelID, id.UserID); !sub && ch.OwnerID != id.UserID {
				writeUnavailable(w, number, unavailableClosed, nil)
				return
			}
		}
		// Сначала — по праву читателя (с поимённым разрешением, его проверяет ListPosts);
		// не нашлось — с верхней границей, чтобы назвать уровень.
		posts, err := st.ListPosts(r.Context(), channelID, number+1, 1, maxLevelInChannel(r, st, ch, id.UserID), id.UserID, false)
		if err == nil && len(posts) == 1 && posts[0].PostID == number {
			counts, cerr := st.CommentCounts(r.Context(), channelID, []uint64{number})
			if cerr != nil {
				log.Printf("getChannelPost: счётчик: %v", cerr)
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"message": channelPostItem(posts[0], counts[number])})
			return
		}
		if err != nil {
			log.Printf("getChannelPost: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		all, err := st.ListPosts(r.Context(), channelID, number+1, 1, levelByGrant, id.UserID, false)
		if err != nil {
			log.Printf("getChannelPost: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if len(all) == 1 && all[0].PostID == number {
			level := all[0].Level
			writeUnavailable(w, number, unavailableLevel, &level)
			return
		}
		writeUnavailable(w, number, unavailableMissing, nil)
	}
}
