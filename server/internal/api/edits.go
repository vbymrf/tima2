// Правка сообщения (ADR-0045; doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §8): общие для личной переписки и
// группы правила приёма. Подпись проверена раньше — здесь только то, что знает сервер.
package api

import (
	"context"
	"errors"
	"log"
	"net/http"

	"tima/server/internal/store"
)

// maxEdits — сколько правок принимает одно сообщение; четвёртую сервер не примет (Решения §8).
const maxEdits = 3

// editTargets — что ручке правки нужно от хранилища.
type editTargets interface {
	EditTarget(ctx context.Context, group bool, container string, messageID int64) (store.EditTarget, error)
}

// checkEdit — можно ли принять правку editOf от userID. Пустой code — можно; тогда t — исходное.
// gkVersion — версия ключа группы у самой правки; у личной переписки не смотрится.
func checkEdit(ctx context.Context, st editTargets, group bool, container string, editOf int64, userID string, gkVersion int32) (t store.EditTarget, code, msg string, status int) {
	t, err := st.EditTarget(ctx, group, container, editOf)
	if errors.Is(err, store.ErrEditTargetMissing) {
		return t, "edit_missing", "исправляемого сообщения нет в этой переписке", http.StatusConflict
	} else if err != nil {
		log.Printf("checkEdit: %v", err)
		return t, "internal", "ошибка хранилища", http.StatusInternalServerError
	}
	code, msg, status = editRefusal(t, group, userID, gkVersion)
	return t, code, msg, status
}

func editRefusal(t store.EditTarget, group bool, userID string, gkVersion int32) (code, msg string, status int) {
	switch {
	case t.SenderID != userID:
		return "edit_not_author", "исправить сообщение может только автор", http.StatusForbidden
	case t.EditOf != 0:
		return "edit_of_edit", "правку не правят — правится исходное сообщение", http.StatusConflict
	case t.Edits >= maxEdits:
		return "edit_limit", "у сообщения уже три правки", http.StatusConflict
	// До первой смены ключа: в личной переписке — эпоха, месяц UTC (ADR-0036); в группе — та же
	// версия ключа группы, что у исходного.
	case !group && !t.SameMonth:
		return "edit_closed", "ключ переписки сменился — исправить сообщение уже нельзя", http.StatusConflict
	case group && t.GKVersion != gkVersion:
		return "edit_closed", "ключ группы сменился — исправить сообщение уже нельзя", http.StatusConflict
	}
	return "", "", 0
}
