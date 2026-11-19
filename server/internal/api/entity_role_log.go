// Журнал ролей группы (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ6): «Управление → Журнал ролей» у владельца и админа.
package api

import (
	"encoding/json"
	"log"
	"net/http"

	"tima/server/internal/auth"
)

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
