// Auth-эндпоинты (api-overview.md §Auth и устройства) — ядро MVP:
// sms/request → sms/verify → register (устройство с ключами → device JWT).
// Guest, recovery, link, attestation — следующие итерации фазы Auth.
package api

import (
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
func (s *Server) rateLimit(w http.ResponseWriter, r *http.Request, key string, limit int64) bool {
	if s.Limit == nil {
		return true
	}
	ok, retryAfter, err := s.Limit.Allow(r.Context(), key, limit, rlWindow)
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
	userID, err := s.Store.UpsertUserByPhone(r.Context(), claims.Subject)
	if err != nil {
		log.Printf("register: upsert user: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// Ключ личности: первое устройство устанавливает, последующие обязаны совпасть
	// (устройство, знающее фразу, выведет тот же ключ). Расхождение → отказ.
	if err := s.Store.SetOrCheckIdentity(r.Context(), userID, identityPub); errors.Is(err, store.ErrIdentityMismatch) {
		writeErr(w, http.StatusForbidden, "identity_mismatch", "ключ личности не совпадает с аккаунтом")
		return
	} else if err != nil {
		log.Printf("register: identity: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	deviceID, err := s.Store.NewDevice(r.Context(), userID, enc, sig)
	if err != nil {
		log.Printf("register: new device: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
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

// lookupUser — GET /users/lookup?phone=: user_id по телефону (contact discovery MVP).
// Только под Bearer; отвечает 404 без деталей. Приватность справочника (rate limit
// на перебор, скрытие по настройке) — итерация Privacy вместе с контактами.
func (s *Server) lookupUser(w http.ResponseWriter, r *http.Request) {
	phone := r.URL.Query().Get("phone")
	if !phoneRe.MatchString(phone) {
		writeErr(w, http.StatusBadRequest, "bad_phone", "нужен телефон в формате E.164")
		return
	}
	userID, err := s.Store.FindUserByPhone(r.Context(), phone)
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

// discoverContacts — POST /users/discover {phones:[...]}: какие из телефонов в TIMA.
// Возвращает {matches:{phone:user_id}}. Приватность справочника — итерация Privacy.
func (s *Server) discoverContacts(w http.ResponseWriter, r *http.Request) {
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
	matches, err := s.Store.FindUsersByPhones(r.Context(), valid)
	if err != nil {
		log.Printf("discoverContacts: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"matches": matches})
}

// setDisplayName — PATCH /users/me/name {display_name}: своё публичное имя.
func (s *Server) setDisplayName(w http.ResponseWriter, r *http.Request) {
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
	if err := s.Store.SetDisplayName(r.Context(), id.UserID, name); err != nil {
		log.Printf("setDisplayName: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"display_name": name})
}

// resolveNames — POST /users/names {ids}: публичные имена по user_id (batch для UI).
func (s *Server) resolveNames(w http.ResponseWriter, r *http.Request) {
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
	names, err := s.Store.DisplayNames(r.Context(), req.IDs)
	if err != nil {
		log.Printf("resolveNames: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"names": names})
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
	b64 := base64.RawURLEncoding
	type item struct {
		DeviceID      string `json:"device_id"`
		EncryptionPub string `json:"encryption_pub"`
		SigningPub    string `json:"signing_pub"`
	}
	out := make([]item, 0, len(devices))
	for _, d := range devices {
		out = append(out, item{d.DeviceID, b64.EncodeToString(d.EncryptionPub), b64.EncodeToString(d.SigningPub)})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"user_id": userID, "devices": out})
}
