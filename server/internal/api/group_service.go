// Group Service (api-overview.md §Группы (переписка)): CRUD групп и участников
// поверх подсистемы membership (data-model.md §3). Здесь права и составы;
// криптография групп — groups.go (API групповых ключей).
//
// MVP-границы: участников добавляет админ напрямую (инвайты/QR — подсистема
// invites, позже); сообщения групп — итерация Message Service для групп;
// передача владения и привязка к сообществу — с модулем communities.
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

// roleRank — owner > admin > moderator > member. Действия над участником
// разрешены только при строгом превосходстве ранга действующего.
var roleRank = map[string]int{"member": 0, "moderator": 1, "admin": 2, "owner": 3}

const (
	rankModerator = 1
	rankAdmin     = 2
	rankOwner     = 3
)

// groupAndRole достаёт группу и роль запрашивающего ("" — не участник).
// При false ответ (404/500) уже записан.
func (s *Server) groupAndRole(w http.ResponseWriter, r *http.Request) (store.Group, string, bool) {
	groupID := r.PathValue("groupID")
	g, err := s.Store.GetGroup(r.Context(), groupID)
	if errors.Is(err, store.ErrGroupNotFound) {
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return g, "", false
	} else if err != nil {
		log.Printf("group %s: %v", groupID, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return g, "", false
	}
	id, _ := auth.FromContext(r.Context())
	role, err := s.Store.GroupRole(r.Context(), groupID, id.UserID)
	if err != nil && !errors.Is(err, store.ErrNotMember) {
		log.Printf("group role %s: %v", groupID, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return g, "", false
	}
	return g, role, true
}

// createGroup — POST /groups.
func (s *Server) createGroup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Kind          string `json:"kind"`
		Title         string `json:"title"`
		Description   string `json:"description"`
		SlowModeSec   int32  `json:"slow_mode_sec"`
		Premoderation bool   `json:"premoderation"`
		ThreadsOnly   bool   `json:"threads_only"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	if req.Kind == "" {
		req.Kind = "private"
	}
	if req.Kind != "private" && req.Kind != "public" {
		writeErr(w, http.StatusBadRequest, "bad_kind", "kind — private или public")
		return
	}
	if req.Title == "" || len(req.Title) > 200 {
		writeErr(w, http.StatusBadRequest, "bad_title", "title обязателен, до 200 байт")
		return
	}
	id, _ := auth.FromContext(r.Context())
	groupID, err := s.Store.CreateGroup(r.Context(), store.Group{
		Kind: req.Kind, Title: req.Title, Description: req.Description, OwnerID: id.UserID,
		SlowModeSec: req.SlowModeSec, Premoderation: req.Premoderation, ThreadsOnly: req.ThreadsOnly,
	})
	if err != nil {
		log.Printf("createGroup: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"group_id": groupID})
}

func groupJSON(g store.Group, myRole string) map[string]any {
	return map[string]any{
		"group_id": g.GroupID, "kind": g.Kind, "title": g.Title, "description": g.Description,
		"owner_id": g.OwnerID, "slow_mode_sec": g.SlowModeSec,
		"premoderation": g.Premoderation, "threads_only": g.ThreadsOnly, "my_role": myRole,
	}
}

// getGroup — GET /groups/{groupID}. Private-группа для не-участника
// неотличима от несуществующей; public видна любому аутентифицированному.
func (s *Server) getGroup(w http.ResponseWriter, r *http.Request) {
	g, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	if role == "" && g.Kind == "private" {
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(groupJSON(g, role))
}

// patchGroup — PATCH /groups/{groupID}: настройки, owner|admin.
func (s *Server) patchGroup(w http.ResponseWriter, r *http.Request) {
	g, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	if roleRank[role] < rankAdmin {
		writeErr(w, http.StatusForbidden, "forbidden", "настройки меняют owner и admin")
		return
	}
	var req struct {
		Title         *string `json:"title"`
		Description   *string `json:"description"`
		SlowModeSec   *int32  `json:"slow_mode_sec"`
		Premoderation *bool   `json:"premoderation"`
		ThreadsOnly   *bool   `json:"threads_only"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	if req.Title != nil {
		if *req.Title == "" || len(*req.Title) > 200 {
			writeErr(w, http.StatusBadRequest, "bad_title", "title непустой, до 200 байт")
			return
		}
		g.Title = *req.Title
	}
	if req.Description != nil {
		g.Description = *req.Description
	}
	if req.SlowModeSec != nil {
		g.SlowModeSec = *req.SlowModeSec
	}
	if req.Premoderation != nil {
		g.Premoderation = *req.Premoderation
	}
	if req.ThreadsOnly != nil {
		g.ThreadsOnly = *req.ThreadsOnly
	}
	if err := s.Store.UpdateGroup(r.Context(), g); err != nil {
		log.Printf("patchGroup: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(groupJSON(g, role))
}

// deleteGroup — DELETE /groups/{groupID}: soft delete, только owner.
func (s *Server) deleteGroup(w http.ResponseWriter, r *http.Request) {
	_, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	if role != "owner" {
		writeErr(w, http.StatusForbidden, "forbidden", "группу удаляет только owner")
		return
	}
	if err := s.Store.SoftDeleteGroup(r.Context(), r.PathValue("groupID")); err != nil {
		log.Printf("deleteGroup: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// listGroupMembers — GET /groups/{groupID}/members: только активным участникам.
func (s *Server) listGroupMembers(w http.ResponseWriter, r *http.Request) {
	_, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	if role == "" {
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return
	}
	members, err := s.Store.ListGroupMembers(r.Context(), r.PathValue("groupID"))
	if err != nil {
		log.Printf("listGroupMembers: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	type item struct {
		UserID      string  `json:"user_id"`
		Role        string  `json:"role"`
		JoinedAt    string  `json:"joined_at"`
		BannedUntil *string `json:"banned_until,omitempty"`
	}
	out := make([]item, 0, len(members))
	for _, m := range members {
		it := item{UserID: m.UserID, Role: m.Role, JoinedAt: m.JoinedAt.UTC().Format("2006-01-02T15:04:05Z")}
		if m.BannedUntil != nil {
			v := m.BannedUntil.UTC().Format("2006-01-02T15:04:05Z")
			it.BannedUntil = &v
		}
		out = append(out, it)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"members": out})
}

// addGroupMember — POST /groups/{groupID}/members {user_id, role?}.
// Добавляют owner|admin; назначаемая роль строго ниже роли действующего.
// После добавления клиент-админ обязан ротировать GK (crypto-protocol §4.2).
func (s *Server) addGroupMember(w http.ResponseWriter, r *http.Request) {
	_, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	var req struct {
		UserID string `json:"user_id"`
		Role   string `json:"role"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil || req.UserID == "" {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
		return
	}
	if req.Role == "" {
		req.Role = "member"
	}
	newRank, known := roleRank[req.Role]
	if !known || req.Role == "owner" {
		writeErr(w, http.StatusBadRequest, "bad_role", "роль — admin, moderator или member")
		return
	}
	if roleRank[role] < rankAdmin || newRank >= roleRank[role] {
		writeErr(w, http.StatusForbidden, "forbidden", "добавляют owner|admin, роль — строго ниже своей")
		return
	}
	err := s.Store.AddGroupMember(r.Context(), r.PathValue("groupID"), req.UserID, req.Role)
	if errors.Is(err, store.ErrUserUnknown) {
		writeErr(w, http.StatusNotFound, "user_not_found", "пользователь не существует")
		return
	} else if err != nil {
		log.Printf("addGroupMember: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"user_id": req.UserID, "role": req.Role})
}

// removeGroupMember — DELETE /groups/{groupID}/members/{userID}: сам (выход)
// или owner|admin над участником строго ниже рангом. Owner не выходит —
// передача владения появится с модулем communities.
// После исключения клиент-админ обязан ротировать GK (crypto-protocol §4.2).
func (s *Server) removeGroupMember(w http.ResponseWriter, r *http.Request) {
	_, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	targetID := r.PathValue("userID")
	targetRole, err := s.Store.GroupRole(r.Context(), r.PathValue("groupID"), targetID)
	if errors.Is(err, store.ErrNotMember) {
		writeErr(w, http.StatusNotFound, "member_not_found", "участник не найден")
		return
	} else if err != nil {
		log.Printf("removeGroupMember: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if targetRole == "owner" {
		writeErr(w, http.StatusConflict, "owner_locked", "owner не покидает группу: сначала передача владения")
		return
	}
	id, _ := auth.FromContext(r.Context())
	self := id.UserID == targetID
	if !self && (roleRank[role] < rankAdmin || roleRank[targetRole] >= roleRank[role]) {
		writeErr(w, http.StatusForbidden, "forbidden", "исключают owner|admin участника ниже рангом")
		return
	}
	if err := s.Store.RemoveGroupMember(r.Context(), r.PathValue("groupID"), targetID); err != nil {
		log.Printf("removeGroupMember: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// setGroupRole — PUT /groups/{groupID}/members/{userID}/role {role}.
// Меняют owner|admin; и текущая, и новая роль цели — строго ниже своей.
func (s *Server) setGroupRole(w http.ResponseWriter, r *http.Request) {
	_, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	var req struct {
		Role string `json:"role"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	newRank, known := roleRank[req.Role]
	if !known || req.Role == "owner" {
		writeErr(w, http.StatusBadRequest, "bad_role", "роль — admin, moderator или member")
		return
	}
	targetID := r.PathValue("userID")
	targetRole, err := s.Store.GroupRole(r.Context(), r.PathValue("groupID"), targetID)
	if errors.Is(err, store.ErrNotMember) {
		writeErr(w, http.StatusNotFound, "member_not_found", "участник не найден")
		return
	} else if err != nil {
		log.Printf("setGroupRole: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if roleRank[role] < rankAdmin || roleRank[targetRole] >= roleRank[role] || newRank >= roleRank[role] {
		writeErr(w, http.StatusForbidden, "forbidden", "роль меняют owner|admin, обе роли — строго ниже своей")
		return
	}
	if err := s.Store.SetGroupRole(r.Context(), r.PathValue("groupID"), targetID, req.Role); err != nil {
		log.Printf("setGroupRole: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"user_id": targetID, "role": req.Role})
}

// banGroupMember — POST /groups/{groupID}/members/{userID}/ban {seconds}.
// Банят moderator и выше, цель — строго ниже рангом; членство сохраняется.
func (s *Server) banGroupMember(w http.ResponseWriter, r *http.Request) {
	_, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	var req struct {
		Seconds int64 `json:"seconds"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Seconds <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_seconds", "нужен seconds > 0")
		return
	}
	targetID := r.PathValue("userID")
	targetRole, err := s.Store.GroupRole(r.Context(), r.PathValue("groupID"), targetID)
	if errors.Is(err, store.ErrNotMember) {
		writeErr(w, http.StatusNotFound, "member_not_found", "участник не найден")
		return
	} else if err != nil {
		log.Printf("banGroupMember: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if roleRank[role] < rankModerator || roleRank[targetRole] >= roleRank[role] {
		writeErr(w, http.StatusForbidden, "forbidden", "банят moderator и выше участника ниже рангом")
		return
	}
	if err := s.Store.BanGroupMember(r.Context(), r.PathValue("groupID"), targetID, req.Seconds); err != nil {
		log.Printf("banGroupMember: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
