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
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/store"
)

// ── «НАЧАТЬ ЗАНОВО» И ЕЁ ОТМЕНА (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ6, Р27–Р31) ───────────
//
// «Начать заново» по одному номеру заводит новую личность в том же аккаунте — так может сделать
// и хозяин, потерявший всё, и вор с перевыпущенной SIM. Различает их прежнее устройство: если
// оно живо, хозяин отменяет новую личность фразой. Отменённая выходит из цепочки, её устройства
// отзываются, прежняя снова текущая; вор остаётся только с тем, что успел написать.
//
// В группах новая личность не участник сразу: она подаёт заявку на место прежней, и владелец
// или модератор подтверждает (Р9). Подтверждение меняет ключ группы (Р14).

// IdentityCancelStore — что отмене и заявкам нужно от хранилища.
type IdentityCancelStore interface {
	IdentityPub(ctx context.Context, userID string) ([]byte, error)
	PersonOfUser(ctx context.Context, userID string) (string, error)
	DeviceCertified(ctx context.Context, userID, deviceID string) (bool, error)
	CancelNewerIdentities(ctx context.Context, personID, keepUserID string) ([]string, error)
	CopyGroupClaims(ctx context.Context, fromUserID, toUserID string) ([]string, error)
	ListGroupClaims(ctx context.Context, groupID string) ([]store.GroupClaim, error)
	ConfirmGroupClaim(ctx context.Context, groupID, userID string) (string, error)
	GroupRole(ctx context.Context, groupID, userID string) (string, error)
	ListGroupMembers(ctx context.Context, groupID string) ([]store.Member, error)
}

var _ IdentityCancelStore = (*store.Store)(nil)

type identityCancelDeps struct {
	store    IdentityCancelStore
	tokens   func() IdentityTokens
	notifier *Notifier
}

// RegisterIdentityCancel — отмена новой личности и заявки в группы.
func RegisterIdentityCancel(mux *http.ServeMux, st IdentityCancelStore, tokens func() IdentityTokens, n *Notifier, requireDevice Middleware) {
	deps := identityCancelDeps{store: st, tokens: tokens, notifier: n}
	mux.HandleFunc("POST /api/v1/users/me/identity/cancel", requireDevice(cancelIdentity(deps)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/identity-claims", requireDevice(listIdentityClaims(deps)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/identity-claims/{userID}/confirm", requireDevice(confirmIdentityClaim(deps)))
}

// announceNewIdentity — после «начать заново»: прежней личности говорим «с вашего номера
// начали заново — отменить?», новая подаёт заявки в группы прежней, их владельцам и
// модераторам — «подтвердите личность».
func announceNewIdentity(ctx context.Context, st IdentityCancelStore, n *Notifier, oldUserID, newUserID string) {
	if n != nil {
		n.Users(ctx, []string{oldUserID}, "identity.replaced", map[string]any{"new_user_id": newUserID})
	}
	groups, err := st.CopyGroupClaims(ctx, oldUserID, newUserID)
	if err != nil {
		log.Printf("начать заново: заявки в группы не заведены: %v", err)
		return
	}
	if n == nil {
		return
	}
	for _, g := range groups {
		members, err := st.ListGroupMembers(ctx, g)
		if err != nil {
			continue
		}
		var deciders []string
		for _, m := range members {
			if roleRank[m.Role] >= roleRank["moderator"] && m.UserID != oldUserID {
				deciders = append(deciders, m.UserID)
			}
		}
		n.Users(ctx, deciders, "group.identity_claim", map[string]any{"group_id": g, "user_id": newUserID, "from_user_id": oldUserID})
	}
}

// cancelIdentity — POST /users/me/identity/cancel: прежнее устройство хозяина отменяет новые
// личности своего аккаунта. Подтверждение — фраза (Р31): подпись ключом личности над вызовом
// `/users/me/reidentify/challenge`; устройство обязано быть заверенным.
func cancelIdentity(deps identityCancelDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			ChallengeToken string `json:"challenge_token"`
			Signature      string `json:"signature"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		claims, err := deps.tokens().Parse(req.ChallengeToken, auth.ScopeReidentify)
		if err != nil || claims.Subject != id.UserID {
			writeErr(w, http.StatusForbidden, "bad_challenge", "вызов просрочен или выдан не этой сессии — запросите новый")
			return
		}
		ctx := r.Context()
		certified, err := deps.store.DeviceCertified(ctx, id.UserID, id.DeviceID)
		if err != nil {
			log.Printf("cancelIdentity: cert: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !certified {
			writeErr(w, http.StatusForbidden, "device_unproven", "Это устройство не заверено — сначала «Подтвердить фразой».")
			return
		}
		identityPub, err := deps.store.IdentityPub(ctx, id.UserID)
		if err != nil {
			log.Printf("cancelIdentity: identity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		sig, derr := base64.RawURLEncoding.DecodeString(req.Signature)
		if derr != nil || !timacrypto.VerifyEnvelopeSignature(identityPub, []byte(req.ChallengeToken), sig) {
			writeErr(w, http.StatusForbidden, "bad_signature", "Фраза не та.")
			return
		}
		personID, err := deps.store.PersonOfUser(ctx, id.UserID)
		if err != nil {
			log.Printf("cancelIdentity: person: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		cancelled, err := deps.store.CancelNewerIdentities(ctx, personID, id.UserID)
		if errors.Is(err, store.ErrNothingToCancel) {
			writeErr(w, http.StatusConflict, "nothing_to_cancel", "Отменять нечего: с вашего номера заново не начинали.")
			return
		} else if err != nil {
			log.Printf("cancelIdentity: cancel: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("личность: %s отменил новые личности %v", id.UserID, cancelled)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"cancelled": cancelled})
	}
}

// listIdentityClaims — GET /groups/{id}/identity-claims: заявки новых личностей; видят
// владелец, админ и модератор — им решать.
func listIdentityClaims(deps identityCancelDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		groupID := r.PathValue("groupID")
		role, err := deps.store.GroupRole(r.Context(), groupID, id.UserID)
		if err != nil || roleRank[role] < roleRank["moderator"] {
			writeErr(w, http.StatusForbidden, "not_moderator", "заявки видят владелец и модераторы")
			return
		}
		claims, err := deps.store.ListGroupClaims(r.Context(), groupID)
		if err != nil {
			log.Printf("listIdentityClaims: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		type item struct {
			UserID     string `json:"user_id"`
			FromUserID string `json:"from_user_id"`
			Role       string `json:"role"`
			CreatedAt  string `json:"created_at"`
		}
		out := make([]item, 0, len(claims))
		for _, c := range claims {
			out = append(out, item{c.UserID, c.FromUserID, c.Role, c.CreatedAt.UTC().Format("2006-01-02T15:04:05Z")})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"claims": out})
	}
}

// confirmIdentityClaim — POST /groups/{id}/identity-claims/{user}/confirm: владелец или
// модератор подтверждает новую личность. Прежняя выходит, новая становится участником, ключ
// группы обязан смениться (Р14) — просим смену у подтвердившего.
func confirmIdentityClaim(deps identityCancelDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		groupID, userID := r.PathValue("groupID"), r.PathValue("userID")
		role, err := deps.store.GroupRole(r.Context(), groupID, id.UserID)
		if err != nil || roleRank[role] < roleRank["moderator"] {
			writeErr(w, http.StatusForbidden, "not_moderator", "подтверждают владелец и модераторы")
			return
		}
		from, err := deps.store.ConfirmGroupClaim(r.Context(), groupID, userID)
		if errors.Is(err, store.ErrNoClaim) {
			writeErr(w, http.StatusNotFound, "no_claim", "заявки нет — её уже подтвердили или отменили")
			return
		} else if err != nil {
			log.Printf("confirmIdentityClaim: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("личность: %s подтвердил %s вместо %s в группе %s", id.UserID, userID, from, groupID)
		if deps.notifier != nil {
			deps.notifier.Users(r.Context(), []string{id.UserID}, "group.rotation_needed",
				map[string]any{"group_id": groupID, "reason": reasonLeave})
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// announceNewDevice — к личности добавилось устройство (Р48): всем её устройствам событие
// `device.added`. Само новое устройство своё событие узнает по `device_id` и не покажет. Это
// не тревога о краже — обычно это смена телефона; человек видит событие и, если это не он,
// отключает устройство в «Секретная фраза и устройства».
func announceNewDevice(ctx context.Context, n *Notifier, userID, deviceID, platform string) {
	if n == nil {
		return
	}
	n.Users(ctx, []string{userID}, "device.added", map[string]any{"device_id": deviceID, "platform": platform})
}

