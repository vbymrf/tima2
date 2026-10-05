// Auth-эндпоинты (api-overview.md §Auth и устройства) — ядро MVP:
// sms/request → sms/verify → register (устройство с ключами → device JWT).
// Guest, recovery, link, attestation — следующие итерации фазы Auth.
package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/ratelimit"
	"tima/server/internal/store"
)

var phoneRe = regexp.MustCompile(`^\+[1-9][0-9]{7,14}$`) // E.164

// Лимиты auth-контура (api-overview: rate limiting): значения консервативные,
// пересматриваются по метрикам. Прод-дефолты; Server.SMS* переопределяют их
// (dev/тесты, где с одного IP регистрируется много устройств) — см. main.go.
const (
	rlWindow        = 10 * time.Minute
	rlSmsPerPhone   = 3  // SMS на телефон: защита от спама SMS-провайдером
	rlSmsPerIP      = 10 // SMS с одного IP: перебор чужих телефонов
	rlVerifyPerCode = 5  // попыток verify на request_id: перебор 6-значного кода

	// Суточный предел кодов на номер — решение заказчика 2026-09-05.
	//
	// Предел ОБЩИЙ: в него входят вход, восстановление и передача аккаунта. Отдельные
	// счётчики обходятся сменой повода — упёршись в предел передачи, следующий код
	// просят под видом входа, и предел перестаёт существовать. Счёт за SMS платим мы,
	// и платим по номеру, а не по поводу.
	//
	// Десять — это девять неверных фраз за сутки при трёх заведённых передачах: для
	// человека, которому фразу продиктовали, с запасом; для перебора — ничто.
	rlSmsPerDay   = 10
	rlWindowDay   = 24 * time.Hour
)

func (s *Server) limSmsPerPhone() int64   { return orDefault(s.SMSPerPhone, rlSmsPerPhone) }
func (s *Server) limSmsPerIP() int64      { return orDefault(s.SMSPerIP, rlSmsPerIP) }
func (s *Server) limVerifyPerCode() int64 { return orDefault(s.VerifyPerCode, rlVerifyPerCode) }

func orDefault(v, def int) int64 {
	if v > 0 {
		return int64(v)
	}
	return int64(def)
}

// rateLimit — попытка по ключу; false = ответ 429 уже записан. Без Redis
// (Limit == nil, dev) лимитов нет. Ошибка Redis = fail-open с логом:
// недоступность шины не должна класть вход целиком.
// rateLimit — обёртка для handler-ов, оставшихся на Server (вход и SMS).
func (s *Server) rateLimit(w http.ResponseWriter, r *http.Request, key string, limit int64) bool {
	return rateLimit(s.Limit, w, r, key, limit)
}

// rateLimit — свободная функция: ею пользуются и Server, и registrar-ы.
// Ограничителя нет (dev без Redis) — пропускаем: это осознанный режим, а не отказ.
func rateLimit(lim *ratelimit.Limiter, w http.ResponseWriter, r *http.Request, key string, limit int64) bool {
	return rateLimitFor(lim, w, r, key, limit, rlWindow)
}

// rateLimitFor — то же, но со своим окном: суточному пределу десятиминутное не годится.
func rateLimitFor(
	lim *ratelimit.Limiter, w http.ResponseWriter, r *http.Request,
	key string, limit int64, window time.Duration,
) bool {
	if lim == nil {
		return true
	}
	ok, retryAfter, err := lim.Allow(r.Context(), key, limit, window)
	if err != nil {
		log.Printf("ratelimit %s: %v", key, err)
		return true
	}
	if !ok {
		w.Header().Set("Retry-After", strconv.FormatInt(int64(retryAfter/time.Second)+1, 10))
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "слишком часто — попробуйте позже")
		return false
	}
	return true
}

// clientIP — адрес клиента; за Caddy — первый X-Forwarded-For (Caddy его
// перезаписывает; прямое соединение мимо прокси в проде закрыто фаерволом).
func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if i := strings.IndexByte(xff, ','); i > 0 {
			xff = xff[:i]
		}
		return strings.TrimSpace(xff)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err) // CSPRNG недоступен — продолжать бессмысленно
	}
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func hashCode(requestID, code string) []byte {
	h := sha256.Sum256([]byte(requestID + "|" + code))
	return h[:]
}

// smsRequest — выдача одноразового кода. SMS-провайдера в MVP нет:
// в dev-режиме (TIMA_DEV_SMS=1) код возвращается в ответе, иначе пишется в лог.
func (s *Server) smsRequest(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Phone string `json:"phone"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil || !phoneRe.MatchString(req.Phone) {
		writeErr(w, http.StatusBadRequest, "bad_phone", "нужен телефон в формате E.164 (+79991234567)")
		return
	}
	if !s.rateLimit(w, r, "sms:phone:"+req.Phone, s.limSmsPerPhone()) ||
		!s.rateLimit(w, r, "sms:ip:"+clientIP(r), s.limSmsPerIP()) {
		return
	}
	// Второй предел — суточный и на тот же номер. Первый бережёт от очереди из трёх
	// SMS подряд, этот — от сотни за день по разным поводам.
	if !rateLimitFor(s.Limit, w, r, "sms:phone:day:"+req.Phone, rlSmsPerDay, rlWindowDay) {
		return
	}
	requestID := newUUID()
	var digits [4]byte
	if _, err := rand.Read(digits[:]); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "нет энтропии")
		return
	}
	code := fmt.Sprintf("%06d", (uint32(digits[0])|uint32(digits[1])<<8|uint32(digits[2])<<16)%1_000_000)
	if err := s.Store.SaveSmsCode(r.Context(), requestID, req.Phone, hashCode(requestID, code), auth.RegisterTTL); err != nil {
		log.Printf("smsRequest: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	resp := map[string]any{"request_id": requestID}
	if s.DevSMS {
		resp["dev_code"] = code // только dev: TIMA_DEV_SMS=1
	} else {
		log.Printf("SMS-провайдер не подключён: код для %s… — %s", req.Phone[:5], code)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

// smsVerify — код → короткий registration-токен.
func (s *Server) smsVerify(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RequestID string `json:"request_id"`
		Code      string `json:"code"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil || req.RequestID == "" || req.Code == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "нужны request_id и code")
		return
	}
	// Перебор 6-значного кода: лимит попыток на request_id
	if !s.rateLimit(w, r, "verify:"+req.RequestID, s.limVerifyPerCode()) {
		return
	}
	phone, err := s.Store.ConsumeSmsCode(r.Context(), req.RequestID, hashCode(req.RequestID, req.Code))
	if errors.Is(err, store.ErrCodeInvalid) {
		writeErr(w, http.StatusForbidden, "bad_code", "код неверен, просрочен или уже использован")
		return
	} else if err != nil {
		log.Printf("smsVerify: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	token, err := s.Auth.IssueRegister(phone)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"registration_token": token})
}

// register — регистрация устройства: публичные ключи → device_id + access-токен.
// Повторный вход с тем же телефоном добавляет НОВОЕ устройство тому же пользователю
// (мультиустройство); привязка через QR (/link/*) — следующая итерация.
func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RegistrationToken string `json:"registration_token"`
		EncryptionPub     string `json:"encryption_pub"`         // base64url, X25519 32 B
		SigningPub        string `json:"signing_pub"`            // base64url, Ed25519 32 B
		IdentityPub       string `json:"identity_pub,omitempty"` // base64url, Ed25519 32 B — ключ личности из фразы
		// ForceNewIdentity — «Начать заново» на экране занятого номера (ДОКУМЕНТАЦИЯ/02
		// §8): человек явно подтвердил, что прежней фразы у него нет, и согласен, что
		// прежняя переписка станет недоступна. Форкает цепочку административно (без
		// подписи) — ровно то же самое право, что уже даёт обращение в поддержку
		// (ДОКУМЕНТАЦИЯ/02 §7), просто без участия человека на той стороне.
		ForceNewIdentity bool `json:"force_new_identity,omitempty"`
		// Platform — самообъявление клиента ('android'/'ios'/'desktop'). Нужна для
		// правила «подтверждать привязку по QR может только телефон»
		// (key-lifecycle.md §2). До аттестации непроверяема — см. миграцию 0029.
		Platform string `json:"platform,omitempty"`
		// Доверие к устройству (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ2). Необязательные: телефон, вошедший
		// с фразой, заводит свой ключ подписи устройств (ask_*) и заверяет им себя; ПК с фразой
		// заверяет себя ключом личности (device_cert_by=identity).
		AskPub        string `json:"ask_pub,omitempty"`
		AskSig        string `json:"ask_sig,omitempty"`
		DeviceCertBy  string `json:"device_cert_by,omitempty"`
		DeviceCertSig string `json:"device_cert_sig,omitempty"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	claims, err := s.Auth.Parse(req.RegistrationToken, auth.ScopeRegister)
	if err != nil {
		writeErr(w, http.StatusForbidden, "bad_token", "registration_token просрочен или подделан")
		return
	}
	enc, err1 := base64.RawURLEncoding.DecodeString(req.EncryptionPub)
	sig, err2 := base64.RawURLEncoding.DecodeString(req.SigningPub)
	if err1 != nil || err2 != nil || len(enc) != 32 || len(sig) != 32 {
		writeErr(w, http.StatusBadRequest, "bad_keys", "ключи должны быть по 32 байта (base64url)")
		return
	}
	var identityPub []byte
	if req.IdentityPub != "" {
		identityPub, err = base64.RawURLEncoding.DecodeString(req.IdentityPub)
		if err != nil || len(identityPub) != 32 {
			writeErr(w, http.StatusBadRequest, "bad_identity", "identity_pub — Ed25519 32 байта (base64url)")
			return
		}
	}
	proof, err := decodeProof(req.AskPub, req.AskSig, req.DeviceCertBy, req.DeviceCertSig)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_proof", err.Error())
		return
	}
	userID, err := s.Store.UpsertUserByPhone(r.Context(), claims.Subject)
	if err != nil {
		log.Printf("register: upsert user: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// ── ДОКАЗАТЕЛЬСТВО ВЛАДЕНИЯ (ДУ2, беда «вор SIM читает новые сообщения») ──────────
	//
	// Код SMS доказывает номер, а номера перевыпускают. Сверка присланного identity_pub с
	// заведённым ничего не доказывает: ключ открытый, сервер сам отдаёт его любому. Доказывает
	// только подпись: устройство заверено ключом личности или ключом подписи устройств,
	// который заверен ключом личности. Ключ личности для проверки — заведённый у аккаунта,
	// а у нового аккаунта (или «начать заново») — присланный.
	existing, err := s.Store.IdentityPub(r.Context(), userID)
	if err != nil {
		log.Printf("register: identity: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	effective := existing
	if len(effective) == 0 || (req.ForceNewIdentity && len(identityPub) > 0) {
		// Новый аккаунт или «начать заново»: заверять устройство будет новая личность.
		effective = identityPub
	}
	askOK, certBy := proofHolds(effective, enc, sig, proof)
	mode := NormalizeDeviceTrust(s.DeviceTrust)
	if mode != trustOff {
		switch {
		case len(effective) == 0:
			log.Printf("доверие: регистрация в аккаунт %s без фразы (режим %s)", userID, mode)
			if mode == trustRequire {
				writeErr(w, http.StatusForbidden, "phrase_required",
					"Аккаунт без секретной фразы недоступен. Обновите приложение и войдите заново.")
				return
			}
		case certBy == "":
			log.Printf("доверие: устройство в аккаунт %s без доказательства фразы (режим %s, ask=%v)", userID, mode, askOK)
			if mode == trustRequire {
				writeErr(w, http.StatusForbidden, "device_unproven",
					"Этот номер привязан к аккаунту с секретной фразой. Введите фразу или подключите устройство по QR с телефона.")
				return
			}
		}
	}
	if req.ForceNewIdentity {
		// Владелец запретил «Начать заново» на этом аккаунте (ДУ10, Р41).
		if startAnewBanned(r.Context(), s.Store, userID) {
			writeErr(w, http.StatusForbidden, "start_anew_banned", startAnewBannedText)
			return
		}
		before := userID
		userID, err = s.forceNewIdentityIfConflict(r.Context(), userID, identityPub)
		if err != nil {
			log.Printf("register: force new identity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Новая личность заведена (ДУ6): прежней — «отменить?», группам — заявки.
		if userID != before {
			announceNewIdentity(r.Context(), s.Store, s.notifier(), before, userID)
		}
	}
	// Ключ личности: первое устройство устанавливает, последующие обязаны совпасть
	// (устройство, знающее фразу, выведет тот же ключ). Расхождение → отказ.
	if err := s.Store.SetOrCheckIdentity(r.Context(), userID, identityPub); errors.Is(err, store.ErrIdentityMismatch) {
		// Фраза прежней личности этого же аккаунта (с номера начали заново) — не «чужая
		// фраза»: человеку нужно знать, как вернуть свою, а не что фраза неверна (Р38).
		if isClosedIdentity(r.Context(), s.Store, userID, identityPub) {
			writeErr(w, http.StatusForbidden, "identity_closed", identityClosedText)
			return
		}
		// Формулировка не техническая намеренно: сюда попадает обычный случай
		// «этот номер уже зарегистрирован, а секретную фразу не ввели». Человеку
		// нужно знать, что делать, а не что не сошлось внутри.
		// К отказу — можно ли здесь «Начать заново» (ДУ10): экран фразы прячет кнопку, если
		// владелец этот путь закрыл. Новое поле ответа — API расширяется.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"code":       "identity_mismatch",
			"message":    "Этот номер уже зарегистрирован. Введите секретную фразу того аккаунта — без неё войти нельзя.",
			"start_anew": !startAnewBanned(r.Context(), s.Store, userID),
		})
		return
	} else if err != nil {
		log.Printf("register: identity: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	platform := normalizePlatform(req.Platform)
	deviceID, err := s.Store.NewDevice(r.Context(), userID, enc, sig, platform)
	if err != nil {
		log.Printf("register: new device: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// Новое своё устройство — событие остальным устройствам личности (Р48).
	announceNewDevice(r.Context(), s.notifier(), userID, deviceID, platform)
	// Свидетельство — после заведения: устройству нужен device_id. Сбой здесь не отменяет
	// регистрацию: устройство заведено и годно, свидетельство телефон пришлёт снова
	// («Устройства» → «Подтвердить фразой»).
	if certBy != "" {
		askID := ""
		if certBy == certByAsk {
			// КПУ держит только телефон (Р12): ПК, приславший КПУ, заверяется только сам.
			if store.PlatformPhone[platform] && askOK {
				askID, err = s.Store.AddSigningKey(r.Context(), userID, deviceID, proof.AskPub, proof.AskSig)
			} else {
				err = errors.New("ключ подписи устройств прислало не-телефонное устройство")
			}
		}
		if err == nil {
			err = s.Store.SetDeviceCertificate(r.Context(), userID, deviceID, certBy, askID, proof.CertSig)
		}
		if err != nil {
			log.Printf("доверие: свидетельство устройства %s не записано: %v", deviceID, err)
		}
	}
	access, err := s.Auth.IssueAccess(userID, deviceID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"user_id": userID, "device_id": deviceID, "access_token": access,
	})
}

// forceNewIdentityIfConflict — «Начать заново» форкает цепочку, только если
// конфликт настоящий: у аккаунта уже установлен identity_pub и он не совпадает с
// присланным. Без этой проверки кнопка форкала бы аккаунт и в тех случаях, где
// register() и так прошёл бы штатно (фраза не задана вовсе, или уже совпала) —
// человек лишился бы своей же истории без всякой причины.
func (s *Server) forceNewIdentityIfConflict(ctx context.Context, userID string, identityPub []byte) (string, error) {
	existing, err := s.Store.IdentityPub(ctx, userID)
	if err != nil {
		return "", err
	}
	if len(existing) == 0 || bytes.Equal(existing, identityPub) {
		return userID, nil // конфликта нет — форкать нечего
	}
	personID, err := s.Store.PersonOfUser(ctx, userID)
	if err != nil {
		return "", err
	}
	// proof = nil: административная связка (ДОКУМЕНТАЦИЯ/02 §7) — доказательства
	// владения прежним ключом нет, собеседники обязаны увидеть предупреждение о
	// смене личности (ADR-0014 §3).
	return s.Store.StartNewIdentity(ctx, personID, userID, nil)
}

// lookupUser — GET /users/lookup?phone=: user_id по телефону (contact discovery MVP).
// Только под Bearer; отвечает 404 без деталей. Приватность справочника (rate limit
// на перебор, скрытие по настройке) — итерация Privacy вместе с контактами.
func lookupUser(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		phone := r.URL.Query().Get("phone")
		if !phoneRe.MatchString(phone) {
			writeErr(w, http.StatusBadRequest, "bad_phone", "нужен телефон в формате E.164")
			return
		}
		userID, err := deps.store.FindUserByPhone(r.Context(), phone)
		if errors.Is(err, store.ErrUserUnknown) {
			writeErr(w, http.StatusNotFound, "user_not_found", "пользователь не найден")
			return
		} else if err != nil {
			log.Printf("lookupUser: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"user_id": userID})
	}
}

// discoverContacts — POST /users/discover {phones:[...]}: какие из телефонов в TIMA.
// Возвращает {matches:{phone:user_id}}. Приватность справочника — итерация Privacy.
func discoverContacts(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Phones []string `json:"phones"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		seen := make(map[string]bool, len(req.Phones))
		valid := make([]string, 0, len(req.Phones))
		for _, p := range req.Phones {
			if phoneRe.MatchString(p) && !seen[p] {
				seen[p] = true
				valid = append(valid, p)
				if len(valid) >= 2000 { // предохранитель от перебора справочника
					break
				}
			}
		}
		matches, err := deps.store.FindUsersByPhones(r.Context(), valid)
		if err != nil {
			log.Printf("discoverContacts: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"matches": matches})
	}
}

// setDisplayName — PATCH /users/me/name {display_name}: своё публичное имя.
func setDisplayName(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			DisplayName string `json:"display_name"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		name := strings.TrimSpace(req.DisplayName)
		if len(name) > 100 {
			writeErr(w, http.StatusBadRequest, "bad_name", "имя до 100 символов")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if err := deps.store.SetDisplayName(r.Context(), id.UserID, name); err != nil {
			log.Printf("setDisplayName: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"display_name": name})
	}
}

// resolveNames — POST /users/names {ids}: публичные имена по user_id (batch для UI).
// Плюс phones — но только собеседников по личным чатам: UI показывает «Имя +7999…»
// для того, кто написал первым, а чужой номер по чужому id остаётся недоступен.
func resolveNames(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			IDs []string `json:"ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil || len(req.IDs) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен ids")
			return
		}
		if len(req.IDs) > 500 {
			req.IDs = req.IDs[:500]
		}
		names, err := deps.store.DisplayNames(r.Context(), req.IDs)
		if err != nil {
			log.Printf("resolveNames: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		phones, err := deps.store.PhonesOfChatPeers(r.Context(), id.UserID, req.IDs)
		if err != nil {
			log.Printf("resolveNames phones: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Ники отдаются всем и без условий, в отличие от телефонов: телефон виден
		// только собеседнику по переписке, а ник человек назначил себе сам, и по
		// нему его и так находят.
		nicks, err := deps.store.Nicknames(r.Context(), req.IDs)
		if err != nil {
			log.Printf("resolveNames nicknames: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Есть ли телефон — вычисляет сервер: клиент не отличит «у него нет телефона»
		// от «я его телефона не знаю». По этому полю показывается предупреждение при
		// добавлении анонимного участника в личную группу (Д10).
		hasPhone, err := deps.store.HasPhone(r.Context(), req.IDs)
		if err != nil {
			log.Printf("resolveNames has_phone: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Аватары — как ники: публичны, кто поставил — того и видно. Нужны подписи у
		// реплик в группе: до 2026-09-18 у чужой реплики стояла буква, потому что чужой
		// аватар сервер не отдавал ничем.
		avatars, err := deps.store.AvatarsOf(r.Context(), req.IDs)
		if err != nil {
			log.Printf("resolveNames avatars: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Счётчик профиля — чтобы клиент запомнил, какую карточку он видел, и переспросил
		// только при разнице с тем, что придёт с сообщением (миграция 0052).
		revs, err := deps.store.ProfileRevs(r.Context(), req.IDs)
		if err != nil {
			log.Printf("resolveNames revs: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"names": names, "phones": phones, "nicknames": nicks, "has_phone": hasPhone,
			"avatars": avatars, "profile_revs": revs,
		})
	}
}

// resolveIdentities — POST /users/identities {ids}: к какому аккаунту относится
// каждый из user_id и чем это подтверждено.
//
// Зачем клиенту: аккаунт — цепочка идентификаторов (миграция 0019), и в одной
// переписке сообщения одного человека могут стоять под разными id. Клиент
// группирует их в один контакт — но только если связка доказана подписью прежнего
// ключа. Для административной связки он ОБЯЗАН показать смену личности: иначе
// владение номером начинает подменять владение ключами.
func resolveIdentities(deps usersDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			IDs []string `json:"ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil || len(req.IDs) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен ids")
			return
		}
		if len(req.IDs) > 500 {
			req.IDs = req.IDs[:500]
		}
		ids, err := deps.store.IdentitiesOf(r.Context(), req.IDs)
		if err != nil {
			log.Printf("resolveIdentities: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"identities": ids})
	}
}

// listDeviceKeys — GET /keys/devices?user_id=: публичные ключи устройств собеседника
// (отправителю — адресаты wrapped keys; получателю — проверка подписи).
func (s *Server) listDeviceKeys(w http.ResponseWriter, r *http.Request) {
	userID := r.URL.Query().Get("user_id")
	if userID == "" {
		if id, ok := auth.FromContext(r.Context()); ok {
			userID = id.UserID // свои устройства по умолчанию
		}
	}
	devices, err := s.Store.ListDevices(r.Context(), userID)
	if err != nil {
		log.Printf("listDeviceKeys: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// Цепочка доверия (ДУ3): ключ личности, действующие КПУ со свидетельствами, у каждого
	// устройства — его свидетельство. Проверяет клиент: сервер — не якорь доверия.
	identityPub, err := s.Store.IdentityPub(r.Context(), userID)
	if err != nil {
		log.Printf("listDeviceKeys: identity: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	asks, err := s.Store.ActiveSigningKeys(r.Context(), userID)
	if err != nil {
		log.Printf("listDeviceKeys: asks: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	b64 := base64.RawURLEncoding
	type item struct {
		DeviceID      string `json:"device_id"`
		EncryptionPub string `json:"encryption_pub"`
		SigningPub    string `json:"signing_pub"`
		CertBy        string `json:"cert_by,omitempty"`
		CertAskID     string `json:"cert_ask_id,omitempty"`
		CertSig       string `json:"cert_sig,omitempty"`
	}
	type askItem struct {
		AskID  string `json:"ask_id"`
		AskPub string `json:"ask_pub"`
		AskSig string `json:"ask_sig"`
	}
	out := make([]item, 0, len(devices))
	for _, d := range devices {
		it := item{DeviceID: d.DeviceID, EncryptionPub: b64.EncodeToString(d.EncryptionPub), SigningPub: b64.EncodeToString(d.SigningPub)}
		if d.CertBy != "" && len(d.CertSig) > 0 {
			it.CertBy, it.CertAskID, it.CertSig = d.CertBy, d.CertAskID, b64.EncodeToString(d.CertSig)
		}
		out = append(out, it)
	}
	askOut := make([]askItem, 0, len(asks))
	for _, k := range asks {
		askOut = append(askOut, askItem{k.AskID, b64.EncodeToString(k.Pub), b64.EncodeToString(k.Sig)})
	}
	resp := map[string]any{
		"user_id": userID, "devices": out,
		"signing_keys": askOut,
		"trust_mode":   NormalizeDeviceTrust(s.DeviceTrust),
	}
	if len(identityPub) == 32 {
		resp["identity_pub"] = b64.EncodeToString(identityPub)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

// identityClosedText — отказ войти фразой прежней личности (Р38).
const identityClosedText = "Это фраза прежней личности: с этого номера начали заново. Вернуть её можно " +
	"отменой новой личности с прежнего устройства или перерегистрацией."

// isClosedIdentity — ключ принадлежит прежней, уже не текущей личности того же аккаунта.
func isClosedIdentity(ctx context.Context, st *store.Store, userID string, identityPub []byte) bool {
	if len(identityPub) == 0 {
		return false
	}
	personID, err := st.PersonOfUser(ctx, userID)
	if err != nil {
		return false
	}
	prior, err := st.FindPriorIdentity(ctx, personID, identityPub)
	return err == nil && prior != userID
}

// startAnewBannedText — отказ «Начать заново» на аккаунте с запретом (ДУ10, Р41).
const startAnewBannedText = "Владелец запретил «Начать заново» на этом аккаунте. Войти можно только по секретной фразе."

// startAnewBanned — закрыт ли на аккаунте этой личности путь «Начать заново». Ошибка чтения —
// «не закрыт»: регистрацию это не открывает шире прежнего, а ложный отказ запер бы человека.
func startAnewBanned(ctx context.Context, st *store.Store, userID string) bool {
	m, err := st.Me(ctx, userID)
	return err == nil && m.StartAnewBanned
}
