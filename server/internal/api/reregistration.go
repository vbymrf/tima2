// Перерегистрация при компрометации и удаление личности (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ9,
// ДУ11; Р34, Р37, Р39, Р50–Р51; устройство — §2г).
//
// С — прежняя личность, Н — заведённая перерегистрацией. Запуск — регистрация с
// force_new_identity и доказательством фразы С (auth.go). Дальше: встречная заявка «Аккаунт
// украден» от С, подтверждения сторон в окне, исход по таблице Р34. Проигравшая личность
// удаляется (ДУ11): устройства — сразу, сама — через срок. Обе заявки и подтверждения требуют
// SMS на номер аккаунта: SIM в каждый момент у одного, он и решает.
package api

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// ReregTimes — сроки перерегистрации (Р51): ожидание, окно подтверждения, жизнь удаляемой
// личности и как часто сервер смотрит на сроки. Нули — умолчания.
type ReregTimes struct {
	Wait, Window, DeleteAfter, Tick time.Duration
}

const day = 24 * time.Hour

func (t ReregTimes) orDefault() ReregTimes {
	if t.Wait <= 0 {
		t.Wait = 90 * day
	}
	if t.Window <= 0 {
		t.Window = 30 * day
	}
	if t.DeleteAfter <= 0 {
		t.DeleteAfter = 30 * day
	}
	if t.Tick <= 0 {
		t.Tick = time.Minute
	}
	return t
}

// ReregStore — что ручкам перерегистрации нужно от хранилища.
type ReregStore interface {
	IdentityPub(ctx context.Context, userID string) ([]byte, error)
	PersonOfUser(ctx context.Context, userID string) (string, error)
	FindUserByPhone(ctx context.Context, phone string) (string, error)
	ReregOfUser(ctx context.Context, userID string) (store.Rereg, error)
	ClaimRereg(ctx context.Context, r store.Rereg) (store.Rereg, error)
	ConfirmRereg(ctx context.Context, id string, newSide bool) (store.Rereg, error)
}

var _ ReregStore = (*store.Store)(nil)

type reregDeps struct {
	store    ReregStore
	tokens   func() IdentityTokens
	notifier *Notifier
}

// RegisterReregistration — состояние, встречная заявка и подтверждение.
func RegisterReregistration(mux *http.ServeMux, st ReregStore, tokens func() IdentityTokens, n *Notifier, requireDevice Middleware) {
	deps := reregDeps{store: st, tokens: tokens, notifier: n}
	mux.HandleFunc("GET /api/v1/users/me/rereg", requireDevice(reregState(deps)))
	mux.HandleFunc("POST /api/v1/users/me/rereg/claim", requireDevice(reregClaim(deps)))
	mux.HandleFunc("POST /api/v1/users/me/rereg/confirm", requireDevice(reregConfirm(deps)))
}

// reregView — процесс глазами стороны: что показать в «Секретная фраза и устройства».
func reregView(r store.Rereg, userID string) map[string]any {
	role := "old"
	if r.NewUserID == userID {
		role = "new"
	}
	confirmed := r.OldConfirmedAt != nil
	if role == "new" {
		confirmed = r.NewConfirmedAt != nil
	}
	return map[string]any{
		"active": true, "role": role, "round": r.Round,
		"started_at": r.StartedAt.UTC(), "window_from": r.WindowFrom.UTC(), "window_to": r.WindowTo.UTC(),
		"disputed": r.Disputed(), "confirmed": confirmed,
	}
}

func reregState(deps reregDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		rr, err := deps.store.ReregOfUser(r.Context(), id.UserID)
		w.Header().Set("Content-Type", "application/json")
		if errors.Is(err, store.ErrNoRereg) {
			_ = json.NewEncoder(w).Encode(map[string]any{"active": false})
			return
		} else if err != nil {
			log.Printf("reregState: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		_ = json.NewEncoder(w).Encode(reregView(rr, id.UserID))
	}
}

// phraseSMS — тело заявки и подтверждения: код из SMS на номер аккаунта и подпись вызова
// `/users/me/reidentify/challenge` ключом личности; у Н при подтверждении — ещё и ключом С.
type phraseSMS struct {
	RegistrationToken string `json:"registration_token"`
	ChallengeToken    string `json:"challenge_token"`
	Signature         string `json:"signature"`
	OldSignature      string `json:"old_signature,omitempty"`
}

// checkSMS — код пришёл на номер именно этого аккаунта. Номер принадлежит аккаунту, а не
// личности: сравниваются аккаунты, иначе у С, не текущей, совпадения не было бы никогда.
func checkSMS(ctx context.Context, deps reregDeps, userID, token string) bool {
	sms, err := deps.tokens().Parse(token, auth.ScopeRegister)
	if err != nil {
		return false
	}
	owner, err := deps.store.FindUserByPhone(ctx, sms.Subject)
	if err != nil {
		return false
	}
	a, err1 := deps.store.PersonOfUser(ctx, owner)
	b, err2 := deps.store.PersonOfUser(ctx, userID)
	return err1 == nil && err2 == nil && a == b
}

// signedBy — подпись вызова ключом личности whose (её фраза).
func signedBy(ctx context.Context, deps reregDeps, whose, challenge, signature string) bool {
	pub, err := deps.store.IdentityPub(ctx, whose)
	sig, sigErr := base64.RawURLEncoding.DecodeString(signature)
	return err == nil && len(pub) == ed25519.PublicKeySize && sigErr == nil &&
		len(sig) == ed25519.SignatureSize && ed25519.Verify(pub, []byte(challenge), sig)
}

// readPhraseSMS — разобрать тело, сверить код SMS и принадлежность вызова сессии.
func readPhraseSMS(w http.ResponseWriter, r *http.Request, deps reregDeps, userID string) (phraseSMS, bool) {
	var req phraseSMS
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return req, false
	}
	if !checkSMS(r.Context(), deps, userID, req.RegistrationToken) {
		writeErr(w, http.StatusForbidden, "phone_mismatch", "код из SMS просрочен или пришёл не на номер этого аккаунта")
		return req, false
	}
	challenge, err := deps.tokens().Parse(req.ChallengeToken, auth.ScopeReidentify)
	if err != nil || challenge.Subject != userID {
		writeErr(w, http.StatusForbidden, "bad_challenge", "вызов просрочен или выдан не этой сессии — запросите новый")
		return req, false
	}
	return req, true
}

// reregClaim — POST /users/me/rereg/claim: «Аккаунт украден — заблокируйте доступ к моей
// личности». Только от С, только встречной заявкой (Р34), фраза С и SMS.
func reregClaim(deps reregDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		ctx := r.Context()
		rr, err := deps.store.ReregOfUser(ctx, id.UserID)
		if err != nil || rr.OldUserID != id.UserID {
			writeErr(w, http.StatusConflict, "no_rereg", "Перерегистрации на вашу личность нет — заявлять не о чем.")
			return
		}
		if rr.Disputed() {
			writeErr(w, http.StatusConflict, "already_claimed", "Заявка уже подана.")
			return
		}
		req, ok := readPhraseSMS(w, r, deps, id.UserID)
		if !ok {
			return
		}
		if !signedBy(ctx, deps, id.UserID, req.ChallengeToken, req.Signature) {
			writeErr(w, http.StatusForbidden, "bad_signature", "Фраза не та.")
			return
		}
		rr, err = deps.store.ClaimRereg(ctx, rr)
		if err != nil {
			log.Printf("reregClaim: %v", err)
			writeErr(w, http.StatusConflict, "no_rereg", "Заявку принять нельзя: перерегистрация кончилась или уже оспорена.")
			return
		}
		log.Printf("перерегистрация %s: встречная заявка от %s", rr.ID, id.UserID)
		if deps.notifier != nil {
			deps.notifier.Users(ctx, []string{rr.OldUserID, rr.NewUserID}, "rereg.disputed", reregPayload(rr))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(reregView(rr, id.UserID))
	}
}

// reregConfirm — POST /users/me/rereg/confirm: подтверждение в окне. Н — обе фразы и SMS;
// С — своя фраза и SMS, и только если подавала встречную заявку.
func reregConfirm(deps reregDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		ctx := r.Context()
		rr, err := deps.store.ReregOfUser(ctx, id.UserID)
		if err != nil || (rr.NewUserID != id.UserID && rr.OldUserID != id.UserID) {
			writeErr(w, http.StatusConflict, "no_rereg", "Подтверждать нечего: перерегистрации нет.")
			return
		}
		newSide := rr.NewUserID == id.UserID
		if !newSide && !rr.Disputed() {
			writeErr(w, http.StatusConflict, "no_claim", "Подтверждать нечего: заявка «Аккаунт украден» не подавалась.")
			return
		}
		req, ok := readPhraseSMS(w, r, deps, id.UserID)
		if !ok {
			return
		}
		if !signedBy(ctx, deps, id.UserID, req.ChallengeToken, req.Signature) ||
			(newSide && !signedBy(ctx, deps, rr.OldUserID, req.ChallengeToken, req.OldSignature)) {
			writeErr(w, http.StatusForbidden, "bad_signature", "Фраза не та.")
			return
		}
		rr, err = deps.store.ConfirmRereg(ctx, rr.ID, newSide)
		if errors.Is(err, store.ErrNoRereg) {
			writeErr(w, http.StatusConflict, "not_in_window", "Подтверждение принимается только в окне подтверждения.")
			return
		} else if err != nil {
			log.Printf("reregConfirm: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("перерегистрация %s: подтвердила %s (новая=%v)", rr.ID, id.UserID, newSide)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(reregView(rr, id.UserID))
	}
}

func reregPayload(r store.Rereg) map[string]any {
	return map[string]any{
		"rereg_id": r.ID, "old_user_id": r.OldUserID, "new_user_id": r.NewUserID,
		"window_from": r.WindowFrom.UTC(), "window_to": r.WindowTo.UTC(), "disputed": r.Disputed(), "round": r.Round,
	}
}

// startRereg — после регистрации Н с доказательством фразы С: процесс, отключение устройств С
// без ключа личности, событие устройствам С и заявки Н в группы — как у «начать заново».
func startRereg(ctx context.Context, st *store.Store, n *Notifier, times ReregTimes, oldUserID, newUserID string) error {
	personID, err := st.PersonOfUser(ctx, oldUserID)
	if err != nil {
		return err
	}
	t := times.orDefault()
	rr, err := st.StartRereg(ctx, personID, oldUserID, newUserID, t.Wait, t.Window)
	if err != nil {
		return err
	}
	log.Printf("перерегистрация %s: %s → %s, окно %s…%s", rr.ID, oldUserID, newUserID,
		rr.WindowFrom.UTC().Format(time.RFC3339), rr.WindowTo.UTC().Format(time.RFC3339))
	if n != nil {
		n.Users(ctx, []string{oldUserID}, "rereg.started", reregPayload(rr))
	}
	announceGroupClaims(ctx, st, n, oldUserID, newUserID)
	return nil
}

// runRereg — проход по срокам сразу и далее раз в Tick до отмены ctx.
func runRereg(ctx context.Context, st *store.Store, n *Notifier, times func() ReregTimes) {
	for {
		t := times().orDefault()
		stepRereg(ctx, st, n, t, time.Now())
		select {
		case <-ctx.Done():
			return
		case <-time.After(t.Tick):
		}
	}
}

// stepRereg — один проход: извещения «окно открылось», исходы кончившихся окон (Р34) и
// окончательное удаление личностей, чей срок вышел (ДУ11).
func stepRereg(ctx context.Context, st *store.Store, n *Notifier, t ReregTimes, now time.Time) {
	due, err := st.ReregsDue(ctx, now)
	if err != nil {
		log.Printf("перерегистрация: сроки: %v", err)
	}
	for _, r := range due {
		if !now.Before(r.WindowTo) {
			outcome, err := st.ResolveRereg(ctx, r, t.Wait, t.Window, t.DeleteAfter)
			if err != nil {
				log.Printf("перерегистрация %s: исход: %v", r.ID, err)
				continue
			}
			log.Printf("перерегистрация %s: исход %s (Н подтвердила=%v, С подтвердила=%v)", r.ID, outcome,
				r.NewConfirmedAt != nil, r.OldConfirmedAt != nil)
			if n != nil {
				payload := reregPayload(r)
				payload["outcome"] = outcome
				if outcome == store.ReregExtended {
					if next, err := st.ReregOfUser(ctx, r.NewUserID); err == nil {
						payload = reregPayload(next)
						payload["outcome"] = outcome
					}
				}
				// Проигравшей стороне событие не дойдёт — её устройства уже отключены; о
				// причине им скажет отказ `device_revoked` с `reason`.
				n.Users(ctx, []string{r.OldUserID, r.NewUserID}, "rereg.done", payload)
			}
			continue
		}
		if r.WindowNoticed < r.Round {
			if n != nil {
				to := []string{r.NewUserID}
				if r.Disputed() {
					to = append(to, r.OldUserID)
				}
				n.Users(ctx, to, "rereg.window", reregPayload(r))
			}
			if err := st.MarkReregWindowNoticed(ctx, r.ID, r.Round); err != nil {
				log.Printf("перерегистрация %s: отметка окна: %v", r.ID, err)
			}
		}
	}
	deleted, err := st.FinishIdentityDeletes(ctx, now)
	if err != nil {
		log.Printf("удаление личностей: %v", err)
		return
	}
	for _, d := range deleted {
		log.Printf("личность %s удалена, вышла из групп: %d", d.UserID, len(d.Groups))
		if n == nil {
			continue
		}
		// Состав группы сменился — ключ обязан смениться (Р14): просим оставшихся.
		for _, g := range d.Groups {
			members, err := st.ListGroupMembers(ctx, g)
			if err != nil {
				continue
			}
			ids := make([]string, 0, len(members))
			for _, m := range members {
				ids = append(ids, m.UserID)
			}
			n.Users(ctx, ids, "group.rotation_needed", map[string]any{"group_id": g, "reason": reasonLeave})
		}
	}
}

// reregProven — доказательство фразы текущей личности при запуске перерегистрации: вызов
// выдан её сессии и подписан её ключом.
func reregProven(tokens IdentityTokens, st *store.Store, r *http.Request, userID, challengeToken, signature string) bool {
	challenge, err := tokens.Parse(challengeToken, auth.ScopeReidentify)
	if err != nil || challenge.Subject != userID {
		return false
	}
	pub, err := st.IdentityPub(r.Context(), userID)
	sig, sigErr := base64.RawURLEncoding.DecodeString(signature)
	return err == nil && len(pub) == ed25519.PublicKeySize && sigErr == nil &&
		len(sig) == ed25519.SignatureSize && ed25519.Verify(pub, []byte(challengeToken), sig)
}

// reregBlocksTrust — идёт спор за аккаунт: заверять до исхода нельзя никому (Р34).
func reregBlocksTrust(ctx context.Context, st interface {
	ReregOfUser(ctx context.Context, userID string) (store.Rereg, error)
}, userID string) bool {
	r, err := st.ReregOfUser(ctx, userID)
	return err == nil && r.Disputed()
}

const reregDisputedText = "Идёт спор за аккаунт: заверять устройства до его конца нельзя никому."

// revokedText — экран отключения по причине (тексты §2б).
func revokedText(reason string) string {
	switch reason {
	case store.RevokedReregistered:
		return "Это устройство отключено: аккаунт перерегистрирован. Заверьте его заново с телефона по QR."
	case store.RevokedDisputed:
		return "Это устройство отключено: подана заявка «Аккаунт украден». Заверять устройства до конца спора нельзя."
	case store.RevokedReregNotConfirmed:
		return "Перерегистрация не подтверждена. Ваша личность будет удалена, аккаунт остаётся за прежней. Можно повторить процедуру или завести новый аккаунт на другой номер."
	case store.RevokedReregConfirmed:
		return "Ваша личность удалена: перерегистрация подтверждена. Можно бороться за аккаунт — подать заявку заново — или завести новый аккаунт на другой номер."
	}
	return "устройство отозвано"
}
