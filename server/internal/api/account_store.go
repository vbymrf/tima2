// Зашифрованная копия личных данных аккаунта — книга и разделы (ПЛАН-РАЗДЕЛОВ Р2а,
// решение заказчика 2026-09-18).
//
// Сервер здесь — почтовый ящик с одной ячейкой на (аккаунт, вид): принять блоб, отдать
// блоб, не дать двум устройствам затереть друг друга. Содержимое он не читает: оно под
// ключом служебной группы аккаунта, а ключ заворачивается на устройства человека теми же
// ручками, что у любой группы (`/groups/{id}/keys`).
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// AccountStoreStore — что копии нужно от хранилища.
type AccountStoreStore interface {
	AccountStore(ctx context.Context, userID, kind string) (store.AccountBlob, error)
	PutAccountStore(ctx context.Context, userID string, in store.AccountBlob) error
	StoreGroup(ctx context.Context, userID string) (string, error)
}

var _ AccountStoreStore = (*store.Store)(nil)

// maxStoreBlob — предел блоба. Книга на тысячу контактов с разделами — десятки килобайт;
// мегабайт оставляет запас на порядок и закрывает случайную отправку не того.
const maxStoreBlob = 1 << 20

// storeKinds — виды копий, которые сервер принимает. Перечень, а не любая строка: строку
// не с чем сверить, и опечатка в клиенте завела бы вторую ячейку рядом с первой.
var storeKinds = map[string]bool{"book": true}

func RegisterAccountStore(mux *http.ServeMux, st AccountStoreStore, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/store/group", requireDevice(storeGroup(st)))
	mux.HandleFunc("GET /api/v1/users/me/store/{kind}", requireDevice(getAccountStore(st)))
	mux.HandleFunc("PUT /api/v1/users/me/store/{kind}", requireDevice(putAccountStore(st)))
}

// storeGroup — служебная группа аккаунта: её ключом шифруется копия. Заводится при первом
// вопросе; дальше — та же.
func storeGroup(st AccountStoreStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		gid, err := st.StoreGroup(r.Context(), id.UserID)
		if err != nil {
			log.Printf("storeGroup: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"group_id": gid})
	}
}

func getAccountStore(st AccountStoreStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		kind := r.PathValue("kind")
		if !storeKinds[kind] {
			writeErr(w, http.StatusNotFound, "unknown_kind", "такого вида копии нет")
			return
		}
		id, _ := auth.FromContext(r.Context())
		blob, err := st.AccountStore(r.Context(), id.UserID, kind)
		switch {
		case errors.Is(err, store.ErrStoreEmpty):
			// 204, а не 404: вид известен, копии просто ещё не сохраняли. Клиент на 204
			// начинает с ревизии 1.
			w.WriteHeader(http.StatusNoContent)
			return
		case err != nil:
			log.Printf("getAccountStore: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"kind":      blob.Kind,
			"revision":  blob.Revision,
			"device_id": blob.DeviceID,
			"blob":      base64.RawURLEncoding.EncodeToString(blob.Blob),
		})
	}
}

func putAccountStore(st AccountStoreStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		kind := r.PathValue("kind")
		if !storeKinds[kind] {
			writeErr(w, http.StatusNotFound, "unknown_kind", "такого вида копии нет")
			return
		}
		var req struct {
			Revision int64  `json:"revision"`
			Blob     string `json:"blob"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, maxStoreBlob*2)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		raw, err := base64.RawURLEncoding.DecodeString(req.Blob)
		if err != nil || len(raw) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_blob", "блоб пуст или не base64url")
			return
		}
		if len(raw) > maxStoreBlob {
			writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "блоб больше предела")
			return
		}
		if req.Revision < 1 {
			writeErr(w, http.StatusBadRequest, "bad_revision", "ревизия начинается с 1")
			return
		}
		// Устройство — из токена, а не из тела: подделать подпись «сохранил ПК» с телефона
		// нельзя, и разбор «кто последний» опирается на факт.
		id, _ := auth.FromContext(r.Context())
		err = st.PutAccountStore(r.Context(), id.UserID, store.AccountBlob{
			Kind: kind, Revision: req.Revision, DeviceID: id.DeviceID, Blob: raw,
		})
		switch {
		case errors.Is(err, store.ErrStoreRevision):
			// Кто-то сохранил раньше. Отдаём текущее сразу, чтобы клиент не ходил дважды.
			current, gerr := st.AccountStore(r.Context(), id.UserID, kind)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusConflict)
			body := map[string]any{"code": "revision_conflict", "message": "ревизия не следующая: заберите свежее и повторите"}
			if gerr == nil {
				body["revision"] = current.Revision
				body["device_id"] = current.DeviceID
				body["blob"] = base64.RawURLEncoding.EncodeToString(current.Blob)
			}
			_ = json.NewEncoder(w).Encode(body)
			return
		case err != nil:
			log.Printf("putAccountStore: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"kind": kind, "revision": req.Revision, "device_id": id.DeviceID})
	}
}
