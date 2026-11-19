// Журнал ролей группы (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ6): «Управление → Журнал ролей» у владельца и админа.
package api

import (
	"encoding/json"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// RegisterEntityRoleLogs — журнал ролей канала и сообщества (у группы — в RegisterGroups): видит владелец.
func RegisterEntityRoleLogs(mux *http.ServeMux, st *store.Store, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/channels/{entityID}/role-log", requireDevice(entityRoleLog(st, "channel")))
	mux.HandleFunc("GET /api/v1/communities/{entityID}/role-log", requireDevice(entityRoleLog(st, "community")))
}

func entityRoleLog(st *store.Store, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		owner, err := entityOwner(r.Context(), st, kind, r.PathValue("entityID"))
		if entityMissing(err) {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if owner != id.UserID {
			writeErr(w, http.StatusForbidden, "forbidden", "журнал ролей видит владелец")
			return
		}
		entries, err := store.RoleLogOf(st).Recent(r.Context(), kind, r.PathValue("entityID"), 200)
		if err != nil {
			log.Printf("entityRoleLog: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"entries": entries})
	}
}

// noteEntityRole — роль у канала (модератор) или сообщества (админ) выдана или снята: запись «role» в журнал.
// Хранилище приходит интерфейсом; журнал пишется, когда за ним настоящее хранилище.
func noteEntityRole(r *http.Request, st any, kind, entityID, targetID, role string) {
	s, ok := st.(*store.Store)
	if !ok {
		return
	}
	id, _ := auth.FromContext(r.Context())
	if err := store.RoleLogOf(s).Note(r.Context(), kind, entityID, id.UserID, targetID, "role", role); err != nil {
		log.Printf("журнал ролей: %v", err)
	}
}

// noteRole — записать действие над участником; сбой журнала печатается, действие уже сделано.
func noteRole(r *http.Request, deps groupsDeps, action, targetID, detail string) {
	id, _ := auth.FromContext(r.Context())
	if err := deps.roles.Note(r.Context(), "group", r.PathValue("groupID"), id.UserID, targetID, action, detail); err != nil {
		log.Printf("журнал ролей: %v", err)
	}
}

// groupRoleLog — GET /groups/{groupID}/role-log: последние 200 записей, новые первыми. Видят owner и admin (§3).
func groupRoleLog(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if roleRank[role] < rankAdmin {
			writeErr(w, http.StatusForbidden, "forbidden", "журнал ролей видят owner|admin")
			return
		}
		entries, err := deps.roles.Recent(r.Context(), "group", r.PathValue("groupID"), 200)
		if err != nil {
			log.Printf("groupRoleLog: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"entries": entries})
	}
}
