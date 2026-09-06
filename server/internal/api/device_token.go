package api

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"
	"time"

	"tima/server/internal/store"
)

// Обновление токена доступа устройством — ПЛАН-ОТЛАДКИ.md, находка от 2026-09-06.
//
// **Зачем.** Токен доступа живёт сутки (`auth.AccessTTL`), а получить новый было неоткуда:
// единственным способом оставался вход по SMS заново. Через сутки после входа приложение
// молча упиралось в `401` на каждой ручке под токеном — сообщения не уходили, устройства
// не читались, — и человек видел не «войдите снова», а просто неработающее приложение.
//
// **Чем устройство доказывает, что оно своё.** Ключом, который у него уже есть: при
// регистрации сервер сохранил `signing_pub`, а закрытая часть лежит в хранилище платформы
// (Keystore, DPAPI). Подпись этим ключом и есть доказательство — то же самое, чем
// подтверждается привязка нового устройства (`device_link.go`).
//
// **Почему не refresh-токен.** Refresh — это второй секрет, который надо хранить, вовремя
// отзывать и не потерять. Ключ устройства уже есть, уже хранится правильно и уже
// отзывается вместе с устройством: `SigningKey` не отдаёт ключ отозванного. Заводить
// рядом второй секрет с той же ролью значит удваивать место, где можно ошибиться.

// DeviceTokenWindow — насколько метка времени в подписи может расходиться с часами
// сервера.
//
// Две минуты в обе стороны. Меньше — и подпись не пройдёт у телефона со слегка сбитыми часами;
// больше — и перехваченная подпись дольше годится для повтора.
const DeviceTokenWindow = 2 * time.Minute

// DeviceTokenStore — что обновлению токена нужно от хранилища.
type DeviceTokenStore interface {
	// SigningKey отдаёт ключ ТОЛЬКО неотозванного устройства — на этом и держится отзыв.
	SigningKey(ctx context.Context, deviceID, userID string) ([]byte, error)
}

// deviceTokenRequest — что присылает устройство.
type deviceTokenRequest struct {
	UserID    string `json:"user_id"`
	DeviceID  string `json:"device_id"`
	IssuedAt  int64  `json:"issued_at"`
	Signature string `json:"signature"`
}

// deviceTokenSigningBytes — что именно подписывает устройство.
//
// **Домен-разделитель обязателен и стоит первым.** Без него подпись, снятая здесь, годится
// везде, где подписывается похожий набор полей: подпись — это утверждение, и утверждение
// обязано называть, о чём оно.
//
// Раскладка нормативна: клиент собирает ровно эти байты, и расхождение означает не «наша
// подпись другая», а «наша подпись не проходит».
func deviceTokenSigningBytes(userID, deviceID string, issuedAt int64) []byte {
	out := make([]byte, 0, 128)
	out = append(out, "tima:device-token:v1\n"...)
	out = append(out, userID...)
	out = append(out, '\n')
	out = append(out, deviceID...)
	out = append(out, '\n')
	out = append(out, strconv.FormatInt(issuedAt, 10)...)
	return out
}

// RegisterDeviceToken подключает обновление токена.
//
// Ручка публичная по необходимости: её зовут ровно тогда, когда прежний токен уже не
// принимается. Требовать для неё токен значило бы требовать то, за чем сюда и пришли.
func RegisterDeviceToken(mux *http.ServeMux, st DeviceTokenStore, tokens func() TokenIssuer) {
	mux.HandleFunc("POST /api/v1/auth/device/token", func(w http.ResponseWriter, r *http.Request) {
		var req deviceTokenRequest
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8*1024)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_request", "не разобрали тело запроса")
			return
		}
		if req.UserID == "" || req.DeviceID == "" || req.Signature == "" {
			writeErr(w, http.StatusBadRequest, "bad_request", "нужны user_id, device_id и signature")
			return
		}

		// Свежесть метки проверяется ДО обращения к базе: просроченную подпись незачем
		// сверять, а лишний запрос в хранилище на каждый повтор — это способ сделать из
		// ручки насос нагрузки.
		age := time.Since(time.Unix(req.IssuedAt, 0))
		if age > DeviceTokenWindow || age < -DeviceTokenWindow {
			writeErr(w, http.StatusUnauthorized, "stale_signature", "подпись просрочена — проверьте часы устройства")
			return
		}

		signature, err := base64.RawURLEncoding.DecodeString(req.Signature)
		if err != nil || len(signature) != ed25519.SignatureSize {
			writeErr(w, http.StatusBadRequest, "bad_signature", "подпись не разобрана")
			return
		}

		signingPub, err := st.SigningKey(r.Context(), req.DeviceID, req.UserID)
		if errors.Is(err, store.ErrDeviceUnknown) {
			// Отозванное и несуществующее устройство отвечают одинаково: различать их
			// значило бы сообщать предъявителю, существует ли такое устройство вообще.
			writeErr(w, http.StatusUnauthorized, "device_revoked", "устройство не зарегистрировано или отозвано")
			return
		}
		if err != nil {
			log.Printf("device/token: ключ устройства: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		if !ed25519.Verify(signingPub, deviceTokenSigningBytes(req.UserID, req.DeviceID, req.IssuedAt), signature) {
			writeErr(w, http.StatusUnauthorized, "bad_signature", "подпись не сходится")
			return
		}

		access, err := tokens().IssueAccess(req.UserID, req.DeviceID)
		if err != nil {
			log.Printf("device/token: выдача токена: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "не удалось выдать токен")
			return
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"access_token": access})
	})
}
