// Страна и язык человека (ПЛАН-ЯЗЫКА Я5…Я7).
//
// **Две задачи, и других на этом этапе нет** (решение заказчика 2026-09-08): клиенту
// отсеивать ненужное, серверу готовить региональные и языковые ленты. Ни перевода, ни
// определения языка по тексту здесь нет и не появится: перевод сообщений не делается
// совсем.
package api

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// LocaleStore — что настройке нужно от хранилища.
type LocaleStore interface {
	LocaleOf(ctx context.Context, userID string) (store.Locale, error)
	SetLocale(ctx context.Context, userID string, l store.Locale) error
}

var _ LocaleStore = (*store.Store)(nil)

// maxLocaleLen — предел длины тега языка и кода страны.
//
// Справочника стран и языков сервер не держит: он не переводит и не отображает, ему
// достаточно короткой сравнимой строки. Полный справочник пришлось бы обновлять вместе с
// миром, а ошибка в нём закрывала бы людям выдачу.
const maxLocaleLen = 16

func RegisterLocale(mux *http.ServeMux, st LocaleStore, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/locale", requireDevice(myLocale(st)))
	mux.HandleFunc("PUT /api/v1/users/me/locale", requireDevice(setMyLocale(st)))
}

func myLocale(st LocaleStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		l, err := st.LocaleOf(r.Context(), id.UserID)
		if err != nil {
			log.Printf("myLocale: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"lang": l.Lang, "country": l.Country})
	}
}

func setMyLocale(st LocaleStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Lang    string `json:"lang"`
			Country string `json:"country"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if len(req.Lang) > maxLocaleLen || len(req.Country) > maxLocaleLen {
			writeErr(w, http.StatusBadRequest, "bad_locale", "язык и страна — короткие коды")
			return
		}
		if req.Lang == "" {
			// Язык обязателен: пустой означал бы «никакой», а такого не бывает — человек
			// на чём-то пишет. Страна пустая законна: «не указана» значит «видит всё».
			writeErr(w, http.StatusBadRequest, "bad_locale", "язык обязателен")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if err := st.SetLocale(r.Context(), id.UserID, store.Locale{Lang: req.Lang, Country: req.Country}); err != nil {
			log.Printf("setMyLocale: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"lang": req.Lang, "country": req.Country})
	}
}
