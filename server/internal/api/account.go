package api

// Управление собственным аккаунтом и архивом чатов (Р4, ADR-0015).
//
// Хранилище и воркер это умели с самого начала, а вот дёрнуть их человеку было
// нечем: удаление аккаунта существовало только как состояние, в которое аккаунт
// переводил воркер по неактивности. Здесь появляются ручки.

import (
	"encoding/json"
	"log"
	"net/http"

	"tima/server/internal/auth"
)

// deleteAccount — DELETE /users/me: пометить аккаунт удалённым.
//
// Удаление в два шага (ADR-0015): пометка, потом физическое стирание по сроку.
// Помеченный аккаунт уже недоступен, но данные могут понадобиться по юридически
// обязывающему запросу в пределах срока хранения.
func (s *Server) deleteAccount(w http.ResponseWriter, r *http.Request) {
	id, _ := auth.FromContext(r.Context())
	personID, err := s.Store.PersonOfUser(r.Context(), id.UserID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "no_account", "аккаунт не найден")
		return
	}
	// Срок берётся из настроек, а не из константы: требование поменяется — поменяем
	// строку в таблице, а не соберём приложение заново.
	days, err := s.Store.RetentionDays(r.Context(), "account_purge_days")
	if err != nil {
		log.Printf("deleteAccount: срок: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if err := s.Store.MarkAccountDeleted(r.Context(), personID, days); err != nil {
		log.Printf("deleteAccount: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	log.Printf("аккаунт %s помечен удалённым, стирание через %d дней", personID, days)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"deleted": true, "purge_after_days": days})
}

// setChatArchived — PUT/DELETE /chats/{chatID}/archive.
//
// Архив ЛИЧНЫЙ: «убрать с глаз». Чат становится готов к удалению только когда его
// убрали все участники — уборка одного человека не назначает удаление переписки
// другому (миграция 0024).
func (s *Server) setChatArchived(w http.ResponseWriter, r *http.Request) {
	chatID := r.PathValue("chatID")
	id, _ := auth.FromContext(r.Context())
	archived := r.Method == http.MethodPut
	if err := s.Store.SetChatArchived(r.Context(), chatID, id.UserID, archived); err != nil {
		log.Printf("setChatArchived: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"chat_id": chatID, "archived": archived})
}

// listArchivedChats — GET /chats/archived: что человек убрал у себя.
func (s *Server) listArchivedChats(w http.ResponseWriter, r *http.Request) {
	id, _ := auth.FromContext(r.Context())
	ids, err := s.Store.ArchivedChatsFor(r.Context(), id.UserID)
	if err != nil {
		log.Printf("listArchivedChats: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if ids == nil {
		ids = []string{}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"chats": ids})
}
