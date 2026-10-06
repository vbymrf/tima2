// Смена SIM — смена номера аккаунта (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ9, Р34, Р40; порядок —
// ответ заказчика 2026-10-06).
//
// Заявка: фраза + код из SMS на прежний номер. Через срок ожидания (как у перерегистрации)
// открывается окно подтверждения: фраза той же личности + код из SMS на новый номер.
// Подтвердили в окне — номер аккаунта меняется; не подтвердили — заявка гаснет. Перерегистрация,
// запущенная во время заявки, её отменяет; новую заявку можно подать, когда спор кончится.
// Личность смена не меняет (Р40).
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// PhoneChangeStore — что ручкам смены номера нужно от хранилища.
type PhoneChangeStore interface {
	ReregStore
	StartPhoneChange(ctx context.Context, userID, newPhone string, wait, window time.Duration) (store.PhoneChange, error)
	PhoneChangeOfUser(ctx context.Context, userID string) (store.PhoneChange, error)
	ConfirmPhoneChange(ctx context.Context, id string) (store.PhoneChange, error)
}

var _ PhoneChangeStore = (*store.Store)(nil)

type phoneChangeDeps struct {
	store    PhoneChangeStore
	tokens   func() IdentityTokens
	notifier *Notifier
	times    func() ReregTimes
}

// RegisterPhoneChange — состояние, заявка и подтверждение смены номера.
func RegisterPhoneChange(mux *http.ServeMux, st PhoneChangeStore, tokens func() IdentityTokens, n *Notifier,
	times func() ReregTimes, requireDevice Middleware) {
	deps := phoneChangeDeps{store: st, tokens: tokens, notifier: n, times: times}
	mux.HandleFunc("GET /api/v1/users/me/phone-change", requireDevice(phoneChangeState(deps)))
	mux.HandleFunc("POST /api/v1/users/me/phone-change", requireDevice(phoneChangeStart(deps)))
	mux.HandleFunc("POST /api/v1/users/me/phone-change/confirm", requireDevice(phoneChangeConfirm(deps)))
}

// phoneTail — номер для показа: «+7 ••• •• 34». Целиком номер в извещениях не ходит.
func phoneTail(phone string) string {
	if len(phone) < 4 {
		return phone
	}
	return phone[:2] + " ••• •• " + phone[len(phone)-2:]
}

// phoneChangeView — заявка глазами устройства: что показать в «Секретная фраза и устройства».
func phoneChangeView(p store.PhoneChange, userID string) map[string]any {
	v := map[string]any{
		"active": true, "new_phone": phoneTail(p.NewPhone), "mine": p.UserID == userID,
		"started_at": p.StartedAt.UTC(), "window_from": p.WindowFrom.UTC(), "window_to": p.WindowTo.UTC(),
	}
	// Подавшей заявку личности — номер целиком: на него в окне просят код подтверждения.
	if p.UserID == userID {
		v["new_phone_full"] = p.NewPhone
	}
	return v
}

func phoneChangePayload(p store.PhoneChange) map[string]any {
	return map[string]any{
		"phone_change_id": p.ID, "user_id": p.UserID, "new_phone": phoneTail(p.NewPhone),
		"window_from": p.WindowFrom.UTC(), "window_to": p.WindowTo.UTC(),
	}
}

func phoneChangeState(deps phoneChangeDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		p, err := deps.store.PhoneChangeOfUser(r.Context(), id.UserID)
		w.Header().Set("Content-Type", "application/json")
		if errors.Is(err, store.ErrNoPhoneChange) {
			_ = json.NewEncoder(w).Encode(map[string]any{"active": false})
			return
		} else if err != nil {
			log.Printf("phoneChangeState: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		_ = json.NewEncoder(w).Encode(phoneChangeView(p, id.UserID))
	}
}

// phoneChangeBody — тело заявки и подтверждения: код из SMS (на прежний номер при заявке, на
// новый при подтверждении) и подпись вызова `/users/me/reidentify/challenge` ключом личности.
type phoneChangeBody struct {
	NewPhone          string `json:"new_phone,omitempty"`
	RegistrationToken string `json:"registration_token"`
	ChallengeToken    string `json:"challenge_token"`
	Signature         string `json:"signature"`
}

func readPhoneChangeBody(w http.ResponseWriter, r *http.Request, deps phoneChangeDeps, userID string) (phoneChangeBody, bool) {
	var req phoneChangeBody
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return req, false
	}
	challenge, err := deps.tokens().Parse(req.ChallengeToken, auth.ScopeReidentify)
	if err != nil || challenge.Subject != userID {
		writeErr(w, http.StatusForbidden, "bad_challenge", "вызов просрочен или выдан не этой сессии — запросите новый")
		return req, false
	}
	rd := reregDeps{store: deps.store, tokens: deps.tokens}
	if !signedBy(r.Context(), rd, userID, req.ChallengeToken, req.Signature) {
		writeErr(w, http.StatusForbidden, "bad_signature", "Фраза не та.")
		return req, false
	}
	return req, true
}

// phoneChangeStart — POST /users/me/phone-change: заявка. Фраза текущей личности и код из SMS
// на прежний номер аккаунта. Во время перерегистрации заявку не подать.
func phoneChangeStart(deps phoneChangeDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		ctx := r.Context()
		req, ok := readPhoneChangeBody(w, r, deps, id.UserID)
		if !ok {
			return
		}
		if !phoneRe.MatchString(req.NewPhone) {
			writeErr(w, http.StatusBadRequest, "bad_phone", "нужен телефон в формате E.164 (+79991234567)")
			return
		}
		rd := reregDeps{store: deps.store, tokens: deps.tokens}
		if !checkSMS(ctx, rd, id.UserID, req.RegistrationToken) {
			writeErr(w, http.StatusForbidden, "phone_mismatch", "код из SMS просрочен или пришёл не на номер этого аккаунта")
			return
		}
		if _, err := deps.store.ReregOfUser(ctx, id.UserID); err == nil {
			writeErr(w, http.StatusConflict, "rereg_open", "Идёт перерегистрация аккаунта: сменить номер можно, когда спор кончится.")
			return
		}
		t := deps.times().orDefault()
		p, err := deps.store.StartPhoneChange(ctx, id.UserID, req.NewPhone, t.Wait, t.Window)
		switch {
		case errors.Is(err, store.ErrPhoneTaken):
			writeErr(w, http.StatusConflict, "phone_taken", "Этот номер уже привязан к аккаунту.")
			return
		case errors.Is(err, store.ErrPhoneChangeOpen):
			writeErr(w, http.StatusConflict, "phone_change_open", "Заявка на смену номера уже подана.")
			return
		case err != nil:
			log.Printf("phoneChangeStart: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("смена номера %s: заявка от %s, окно %s…%s", p.ID, id.UserID,
			p.WindowFrom.UTC().Format(time.RFC3339), p.WindowTo.UTC().Format(time.RFC3339))
		if deps.notifier != nil {
			deps.notifier.Users(ctx, []string{id.UserID}, "phone_change.started", phoneChangePayload(p))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(phoneChangeView(p, id.UserID))
	}
}

// phoneChangeConfirm — POST /users/me/phone-change/confirm: подтверждение в окне. Фраза той
// же личности, что подала заявку, и код из SMS на новый номер; прежний номер уже не нужен.
func phoneChangeConfirm(deps phoneChangeDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		ctx := r.Context()
		p, err := deps.store.PhoneChangeOfUser(ctx, id.UserID)
		if err != nil {
			writeErr(w, http.StatusConflict, "no_phone_change", "Подтверждать нечего: заявки на смену номера нет.")
			return
		}
		if p.UserID != id.UserID {
			writeErr(w, http.StatusForbidden, "not_your_request", "Подтвердить смену номера может только ключ личности, подавший заявку.")
			return
		}
		req, ok := readPhoneChangeBody(w, r, deps, id.UserID)
		if !ok {
			return
		}
		sms, err := deps.tokens().Parse(req.RegistrationToken, auth.ScopeRegister)
		if err != nil || sms.Subject != p.NewPhone {
			writeErr(w, http.StatusForbidden, "phone_mismatch", "код из SMS просрочен или пришёл не на новый номер")
			return
		}
		p, err = deps.store.ConfirmPhoneChange(ctx, p.ID)
		switch {
		case errors.Is(err, store.ErrNoPhoneChange):
			writeErr(w, http.StatusConflict, "not_in_window", "Подтверждение принимается только в окне подтверждения.")
			return
		case errors.Is(err, store.ErrPhoneTaken):
			writeErr(w, http.StatusConflict, "phone_taken", "Новый номер успели привязать к другому аккаунту.")
			return
		case err != nil:
			log.Printf("phoneChangeConfirm: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("смена номера %s: подтверждена, номер аккаунта сменён", p.ID)
		if deps.notifier != nil {
			payload := phoneChangePayload(p)
			payload["outcome"] = store.PhoneChanged
			deps.notifier.Users(ctx, []string{id.UserID}, "phone_change.done", payload)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"active": false, "outcome": store.PhoneChanged, "new_phone": phoneTail(p.NewPhone)})
	}
}

// cancelPhoneChange — перерегистрация отменяет открытую заявку на смену номера (ответ
// заказчика 2026-10-06): подать заново можно, когда спор кончится.
func cancelPhoneChange(ctx context.Context, st *store.Store, n *Notifier, userID string) {
	p, err := st.PhoneChangeOfUser(ctx, userID)
	if err != nil {
		return
	}
	if err := st.ClosePhoneChange(ctx, p.ID, store.PhoneChangeCancelled); err != nil {
		log.Printf("смена номера %s: отмена: %v", p.ID, err)
		return
	}
	log.Printf("смена номера %s: отменена перерегистрацией", p.ID)
	if n != nil {
		payload := phoneChangePayload(p)
		payload["outcome"] = store.PhoneChangeCancelled
		n.Users(ctx, []string{p.UserID}, "phone_change.done", payload)
	}
}

// stepPhoneChanges — извещения «окно открылось» и гашение заявок, чьё окно кончилось.
func stepPhoneChanges(ctx context.Context, st *store.Store, n *Notifier, now time.Time) {
	due, err := st.PhoneChangesDue(ctx, now)
	if err != nil {
		log.Printf("смена номера: сроки: %v", err)
		return
	}
	for _, p := range due {
		if !now.Before(p.WindowTo) {
			if err := st.ClosePhoneChange(ctx, p.ID, store.PhoneChangeExpired); err != nil {
				log.Printf("смена номера %s: гашение: %v", p.ID, err)
				continue
			}
			log.Printf("смена номера %s: не подтверждена за окно — номер прежний", p.ID)
			if n != nil {
				payload := phoneChangePayload(p)
				payload["outcome"] = store.PhoneChangeExpired
				n.Users(ctx, []string{p.UserID}, "phone_change.done", payload)
			}
			continue
		}
		if !p.WindowNoticed {
			if n != nil {
				n.Users(ctx, []string{p.UserID}, "phone_change.window", phoneChangePayload(p))
			}
			if err := st.MarkPhoneChangeWindowNoticed(ctx, p.ID); err != nil {
				log.Printf("смена номера %s: отметка окна: %v", p.ID, err)
			}
		}
	}
}
