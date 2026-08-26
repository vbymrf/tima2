// Воссоединение личности (Р4, хвост; ДОКУМЕНТАЦИЯ/02-приложение/перенос-аккаунта-на-новое-устройство.md
// §5) — «Присоединить прежний аккаунт»: доказательство владения прежним ключом
// личности секретной фразой, без QR и второго живого устройства.
//
// Криптографически подпись ключом, выведенным из фразы, неотличима от подписи
// «живым старым телефоном» из §2 — тот же ключ, тот же алгоритм проверки. QR как
// способ добыть эту же подпись физическим сканированием — отдельная функция,
// заведомо не реализуемая и не проверяемая в этой среде (нет камеры, нет второго
// устройства; см. ФАЗА-1-СТАТУС.md §«Привязка устройства по QR»).
//
// Два запроса вместо одного намеренно: сначала челлендж (короткоживущий токен,
// привязанный к конкретной сессии), потом подпись над ним. Без челленджа подпись
// можно было бы получить один раз и предъявлять многократно.
package api

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// reidentifyChallenge — POST /api/v1/users/me/reidentify/challenge.
func reidentifyChallenge(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		token, err := deps.tokens().IssueReidentifyChallenge(id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"challenge_token": token})
	}
}

// reidentify — POST /api/v1/users/me/reidentify: доказательство подписью прежнего
// ключа личности → воссоединение. StartNewIdentity получает подпись как proof,
// поэтому связка проверяемая (LinkProven), а не административная — собеседники не
// увидят предупреждение о смене личности.
func reidentify(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			ChallengeToken string `json:"challenge_token"`
			IdentityPub    string `json:"identity_pub"` // base64url, Ed25519 32 B — из секретной фразы
			Signature      string `json:"signature"`    // base64url, 64 B — подпись challenge_token этим ключом
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		claims, err := deps.tokens().Parse(req.ChallengeToken, auth.ScopeReidentify)
		if err != nil || claims.Subject != id.UserID {
			writeErr(w, http.StatusForbidden, "bad_challenge",
				"challenge_token просрочен, подделан или выдан не этой сессии — запросите новый")
			return
		}
		identityPub, err1 := base64.RawURLEncoding.DecodeString(req.IdentityPub)
		sig, err2 := base64.RawURLEncoding.DecodeString(req.Signature)
		if err1 != nil || err2 != nil || len(identityPub) != ed25519.PublicKeySize || len(sig) != ed25519.SignatureSize {
			writeErr(w, http.StatusBadRequest, "bad_proof", "identity_pub — 32 байта, signature — 64 байта (base64url)")
			return
		}
		if !ed25519.Verify(identityPub, []byte(req.ChallengeToken), sig) {
			writeErr(w, http.StatusForbidden, "bad_signature", "подпись не сходится с присланным ключом")
			return
		}
		ctx := r.Context()
		personID, err := deps.store.PersonOfUser(ctx, id.UserID)
		if err != nil {
			log.Printf("reidentify: person: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		priorUserID, err := deps.store.FindPriorIdentity(ctx, personID, identityPub)
		if errors.Is(err, store.ErrIdentityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found",
				"Эта фраза не подходит ни одной прежней личности этого аккаунта.")
			return
		} else if err != nil {
			log.Printf("reidentify: find prior: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if priorUserID == id.UserID {
			writeErr(w, http.StatusBadRequest, "already_current", "Вы уже под этой личностью — присоединять нечего.")
			return
		}
		newUserID, err := deps.store.StartNewIdentity(ctx, personID, priorUserID, sig)
		if err != nil {
			log.Printf("reidentify: start new identity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Тот же ключ доказан — новая голова цепочки продолжает им пользоваться, иначе
		// следующий вход снова упёрся бы в identity_mismatch на пустом месте.
		if err := deps.store.SetOrCheckIdentity(ctx, newUserID, identityPub); err != nil {
			log.Printf("reidentify: set identity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if err := deps.store.MoveDeviceToUser(ctx, id.DeviceID, newUserID); err != nil {
			log.Printf("reidentify: move device: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		access, err := deps.tokens().IssueAccess(newUserID, id.DeviceID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		log.Printf("reidentify: %s → %s (proven)", id.UserID, newUserID)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"user_id": newUserID, "access_token": access})
	}
}
