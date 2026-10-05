// Запрет упрощённой перерегистрации (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ10, Р36, Р41).
//
// «Начать заново» заводит новую личность по одной SMS, без фразы. Владелец может закрыть этот
// путь на своём аккаунте — тогда укравший SIM не начнёт заново на его номере, а вернуть личность
// можно будет только фразой. Ставится фразой и SMS: фраза доказывает личность, SMS — что номер
// сейчас у того же человека. Снять запрет нельзя — ни этой ручкой, ни в обход (триггер 0064).
package api

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
)

// banStartAnew — POST /api/v1/users/me/start-anew-ban
// {registration_token, challenge_token, signature}.
//
// registration_token — из обычного sms/verify на номер аккаунта; challenge_token — из
// /users/me/reidentify/challenge, подписан ключом текущей личности, выведенным из фразы.
func banStartAnew(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			RegistrationToken string `json:"registration_token"`
			ChallengeToken    string `json:"challenge_token"`
			Signature         string `json:"signature"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		ctx := r.Context()

		// SMS: код пришёл на номер именно этого аккаунта.
		sms, err := deps.tokens().Parse(req.RegistrationToken, auth.ScopeRegister)
		if err != nil {
			writeErr(w, http.StatusForbidden, "bad_token", "код из SMS просрочен — запросите новый")
			return
		}
		owner, err := deps.store.FindUserByPhone(ctx, sms.Subject)
		if err != nil || owner != id.UserID {
			writeErr(w, http.StatusForbidden, "phone_mismatch", "код пришёл не на номер этого аккаунта")
			return
		}

		// Фраза: подпись вызова ключом текущей личности.
		challenge, err := deps.tokens().Parse(req.ChallengeToken, auth.ScopeReidentify)
		if err != nil || challenge.Subject != id.UserID {
			writeErr(w, http.StatusForbidden, "bad_challenge", "вызов просрочен или выдан не этой сессии — запросите новый")
			return
		}
		pub, err := deps.store.IdentityPub(ctx, id.UserID)
		sig, sigErr := base64.RawURLEncoding.DecodeString(req.Signature)
		if err != nil || len(pub) != ed25519.PublicKeySize || sigErr != nil || len(sig) != ed25519.SignatureSize ||
			!ed25519.Verify(pub, []byte(req.ChallengeToken), sig) {
			writeErr(w, http.StatusForbidden, "bad_signature", "фраза не подходит к этому аккаунту")
			return
		}

		if err := deps.store.BanStartAnew(ctx, id.UserID); err != nil {
			log.Printf("banStartAnew: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]bool{"start_anew_banned": true})
	}
}
