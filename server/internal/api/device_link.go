// Привязка нового устройства по QR (key-lifecycle.md §2, ФАЗА-1-СТАТУС.md).
// Раньше в этой сборке было осознанно отложено: не хватало прототипа протокола,
// проверенного на практике. Схема ниже адаптирована из doc_add/timaNC/server/internal/phase1
// (device_link.go) — там она устроена под другую модель устройств (Windows как
// отдельная платформа, «завёрнутый секрет устройства»), здесь упрощена под то,
// что уже есть: устройство — просто X25519+Ed25519 пара в devices, без деления
// по платформам.
//
// Роли:
//   - НОВОЕ устройство (аккаунта ещё нет) вызывает /link/start, получает QR и
//     claim_token, показывает QR, опрашивает /link/claim.
//   - Уже авторизованное доверенное устройство сканирует QR и одним запросом
//     /link/confirm добавляет владельца QR как своё новое устройство.
//
// Зачем подпись, а не просто «раз Bearer-токен есть — значит, доверенное
// устройство разрешило»: signature подтверждающего устройства над данными ИЗ
// QR (session_id, secret, ключи нового устройства) проверяется по его же
// signing_pub, уже сохранённому в devices при регистрации. Расхождение — в
// том числе случайное, не обязательно злонамеренное — само проявляется как
// bad_signature, а не тихо потеряется где-то в вызовах хранилища. Тот же
// принцип, что и подпись анклава в Р2 (ПЛАН-РЕФАКТОРИНГА.md).
//
// Байтовая раскладка linkSigningBytes нормативна: Kotlin обязана воспроизводить
// её байт-в-байт (messenger-crypto DeviceLinkSignature.kt).
package api

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
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

const (
	linkSessionTTL = 5 * time.Minute
	// Обе ручки без авторизации, поэтому лимиты обязательны: /link/start создаёт
	// строку в базе на каждый вызов, /link/claim опрашивается в цикле. Значения с
	// запасом под нормальную работу: клиент опрашивает claim раз в 2 секунды
	// в течение 5 минут — это 150 попыток на сессию, лимит на 10-минутное окно
	// оставляет место второй попытке привязки с того же адреса.
	rlLinkStartPerIP = 20
	rlLinkClaimPerIP = 400
)

// linkStart — POST /api/v1/link/start, без авторизации: у нового устройства
// аккаунта ещё нет. Тело — те же ключи, что при обычной регистрации, но без
// SMS/фразы: доверие принесёт confirm с другого устройства.
func linkStart(deps devicesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !rateLimit(deps.limiter(), w, r, "link:start:"+clientIP(r), rlLinkStartPerIP) {
			return
		}
		var req struct {
			EncryptionPub string `json:"encryption_pub"` // base64url, X25519 32 B
			SigningPub    string `json:"signing_pub"`    // base64url, Ed25519 32 B
			DeviceName    string `json:"device_name"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		enc, err1 := base64.RawURLEncoding.DecodeString(req.EncryptionPub)
		sig, err2 := base64.RawURLEncoding.DecodeString(req.SigningPub)
		if err1 != nil || err2 != nil || len(enc) != 32 || len(sig) != ed25519.PublicKeySize ||
			req.DeviceName == "" || len(req.DeviceName) > 100 {
			writeErr(w, http.StatusBadRequest, "bad_request",
				"нужны encryption_pub/signing_pub (32 байта base64url) и device_name (1..100 символов)")
			return
		}
		secret, err := randomLinkToken()
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "нет энтропии")
			return
		}
		claimToken, err := randomLinkToken()
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "нет энтропии")
			return
		}
		expires := time.Now().UTC().Add(linkSessionTTL)
		sessionID, err := deps.store.CreateLinkSession(r.Context(), enc, sig, req.DeviceName,
			hashLinkToken(secret), hashLinkToken(claimToken), expires)
		if err != nil {
			log.Printf("linkStart: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Формат QR-payload нормативен наравне с linkSigningBytes — подтверждающее
		// устройство разбирает его руками (без общей proto-схемы, значения короткие).
		qrPayload := "tima://link/v1?session_id=" + sessionID + "&secret=" + secret +
			"&encryption_key=" + req.EncryptionPub + "&signing_key=" + req.SigningPub +
			"&name=" + base64.RawURLEncoding.EncodeToString([]byte(req.DeviceName))
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"session_id":  sessionID,
			"qr_payload":  qrPayload,
			"claim_token": claimToken,
			"expires_at":  expires.Format(time.RFC3339),
		})
	}
}

// linkConfirm — POST /api/v1/link/confirm, авторизовано: вызывает уже доверенное
// устройство, отсканировавшее QR.
func linkConfirm(deps devicesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			SessionID string `json:"session_id"`
			Secret    string `json:"secret"`
			Signature string `json:"signature"` // base64url, 64 B
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		sig, err := base64.RawURLEncoding.DecodeString(req.Signature)
		if err != nil || len(sig) != ed25519.SignatureSize || req.SessionID == "" || req.Secret == "" {
			writeErr(w, http.StatusBadRequest, "bad_request",
				"нужны session_id, secret и signature (64 байта base64url)")
			return
		}
		ctx := r.Context()
		ls, err := deps.store.GetLinkSessionForConfirm(ctx, req.SessionID)
		if errors.Is(err, store.ErrLinkSessionInvalid) {
			writeErr(w, http.StatusForbidden, "bad_session", "сессия привязки не найдена, просрочена или уже подтверждена")
			return
		} else if err != nil {
			log.Printf("linkConfirm: get session: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Подтверждать вправе только телефон (key-lifecycle.md §2): якорь доверия —
		// аттестуемое устройство, десктоп своё доверие наследует, а не раздаёт дальше.
		// Платформа самообъявленная и до аттестации непроверяема (миграция 0029) —
		// правило порядка, а не граница безопасности. Пустая платформа (регистрация
		// до 0029) тоже отказ: клиент объявляет её при запуске и чинится сам.
		platform, err := deps.store.DevicePlatform(ctx, id.DeviceID)
		if err != nil {
			log.Printf("linkConfirm: platform: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !store.PlatformPhone[platform] {
			writeErr(w, http.StatusForbidden, "not_a_phone",
				"Подтвердить подключение может только телефон — на нём откройте «Устройства» и отсканируйте код.")
			return
		}
		signingPub, err := deps.store.SigningKey(ctx, id.DeviceID, id.UserID)
		if err != nil {
			log.Printf("linkConfirm: signing key: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !ed25519.Verify(signingPub, linkSigningBytes(req.SessionID, req.Secret, ls.EncryptionPub, ls.SigningPub), sig) {
			writeErr(w, http.StatusForbidden, "bad_signature", "подпись не сходится")
			return
		}
		newDeviceID, err := deps.store.ConfirmLinkSession(ctx, req.SessionID, hashLinkToken(req.Secret), id.UserID)
		if errors.Is(err, store.ErrLinkSessionInvalid) {
			writeErr(w, http.StatusForbidden, "bad_session", "сессия привязки не найдена, просрочена или уже подтверждена")
			return
		} else if err != nil {
			log.Printf("linkConfirm: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		log.Printf("linkConfirm: устройство %s подтвердило %q (%s) для %s",
			id.DeviceID, ls.DeviceName, newDeviceID, id.UserID)
		// device_id возвращается не для отчётности: подтвердившее устройство сразу
		// перезаворачивает на него ключи истории (ADR-0010 §этап 2, тот же путь, что
		// у восстановления), а для этого нужен адрес получателя обёрток. Само новое
		// устройство запросить историю не может — у него нет ключа личности из фразы,
		// которым chatRecover требует подписать запрос.
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"status": "confirmed", "device_id": newDeviceID,
		})
	}
}

// linkClaim — POST /api/v1/link/claim, без авторизации: у нового устройства
// аккаунта ещё нет, claim_token и есть его пропуск (как registration_token при
// обычной регистрации).
func linkClaim(deps devicesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !rateLimit(deps.limiter(), w, r, "link:claim:"+clientIP(r), rlLinkClaimPerIP) {
			return
		}
		var req struct {
			SessionID  string `json:"session_id"`
			ClaimToken string `json:"claim_token"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil ||
			req.SessionID == "" || req.ClaimToken == "" {
			writeErr(w, http.StatusBadRequest, "bad_request", "нужны session_id и claim_token")
			return
		}
		userID, deviceID, err := deps.store.ClaimLinkSession(r.Context(), req.SessionID, hashLinkToken(req.ClaimToken))
		if errors.Is(err, store.ErrLinkSessionInvalid) {
			writeErr(w, http.StatusForbidden, "not_ready", "устройство ещё не подтверждено — попробуйте ещё раз через пару секунд")
			return
		} else if err != nil {
			log.Printf("linkClaim: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		access, err := deps.tokens().IssueAccess(userID, deviceID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"user_id": userID, "device_id": deviceID, "access_token": access,
		})
	}
}

func randomLinkToken() (string, error) {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b[:]), nil
}

func hashLinkToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// linkSigningBytes — канонические байты подписи confirm (schema/proto/README.md).
// Домен-разделитель + session_id + secret (проверяет, что подтверждающий видел
// именно этот QR) + ключи нового устройства, которые сервер сам хранит для этой
// сессии — если подтверждающее устройство подписало другие ключи (разбор QR
// разошёлся с тем, что реально лежит в сессии), verify не пройдёт сам собой.
func linkSigningBytes(sessionID, secret string, encryptionPub, signingPub []byte) []byte {
	h := sha256.New()
	h.Write([]byte("TIMA-DEVICE-LINK-v1"))
	h.Write([]byte{0})
	h.Write([]byte(sessionID))
	h.Write([]byte{0})
	h.Write([]byte(secret))
	h.Write([]byte{0})
	h.Write(encryptionPub)
	h.Write(signingPub)
	return h.Sum(nil)
}
