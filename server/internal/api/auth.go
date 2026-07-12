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
	"net/http"
	"regexp"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

var phoneRe = regexp.MustCompile(`^\+[1-9][0-9]{7,14}$`) // E.164

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
	// TODO(фаза 2+): rate limiting per phone/IP через Redis (api-overview: rate limiting per device)
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
		EncryptionPub     string `json:"encryption_pub"` // base64url, X25519 32 B
		SigningPub        string `json:"signing_pub"`    // base64url, Ed25519 32 B
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
	userID, err := s.Store.UpsertUserByPhone(r.Context(), claims.Subject)
	if err != nil {
		log.Printf("register: upsert user: %v", err)
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
