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

// Виртуальные аккаунты — ПЛАН-КОНТАКТОВ.md, Д10.
//
// **Виртуальный аккаунт полноценен во всём**: состоит в группах, владеет ими и каналами,
// модерирует, банит, ротирует ключи — теми же ручками и с теми же проверками. Отдельного
// «урезанного» вида пользователя не заводится: он был бы вторым набором правил на каждую
// проверку прав, и однажды они разошлись бы. Поэтому здесь только две ручки — завести и
// перечислить; всё остальное уже работает.
//
// **Регистрируется не по SMS.** Своего номера у него нет, значит нет и кода на него.
// Заводит его основной аккаунт своей подписью — единственное административное действие в
// системе, которое подписью заверено. Причина не в аккуратности: код из SMS сюда прислать
// неоткуда, и без подписи создание опиралось бы на один токен устройства.

// VirtualStore — что виртуальным аккаунтам нужно от хранилища.
type VirtualStore interface {
	CreateVirtual(ctx context.Context, ownerUserID, nickname string, identityPub []byte) (string, error)
	VirtualsOf(ctx context.Context, ownerUserID string) ([]string, error)
	HasPhone(ctx context.Context, ids []string) (map[string]bool, error)
	IdentityPub(ctx context.Context, userID string) ([]byte, error)
	Nicknames(ctx context.Context, ids []string) (map[string]string, error)

	// Устройство виртуального аккаунта: без него в него не войти вовсе.
	NewDevice(ctx context.Context, userID string, encryptionPub, signingPub []byte, platform string) (string, error)
}

// VirtualTokens — что виртуальным аккаунтам нужно от подсистемы входа.
//
// Только выдача токена: код из SMS сюда прислать неоткуда, номера у аккаунта нет.
type VirtualTokens interface {
	IssueAccess(userID, deviceID string) (string, error)
}

var _ VirtualStore = (*store.Store)(nil)

// RegisterVirtuals — две ручки.
func RegisterVirtuals(mux *http.ServeMux, st VirtualStore, tokens func() VirtualTokens, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/virtuals", requireDevice(listVirtuals(st)))
	mux.HandleFunc("POST /api/v1/users/me/virtuals", requireDevice(createVirtual(st, tokens)))
}

// virtualSigned — что именно подписывает владелец.
//
// Ник и ключ личности вместе: подпись только над ником позволила бы подменить ключ и
// завести аккаунт, которым владелец не управляет, а подпись только над ключом — занять
// чужим ключом любой свободный ник.
func virtualSigned(nickname string, identityPub []byte) []byte {
	out := make([]byte, 0, len(nickname)+1+len(identityPub))
	out = append(out, nickname...)
	out = append(out, '\n')
	return append(out, identityPub...)
}

// createVirtual — POST /users/me/virtuals {nickname, identity_pub, signature}.
func createVirtual(st VirtualStore, tokens func() VirtualTokens) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Nickname    string `json:"nickname"`
			IdentityPub string `json:"identity_pub"` // base64url, 32 байта — ключ из новой фразы
			Signature   string `json:"signature"`    // base64url, 64 байта, ключом владельца
			// Ключи УСТРОЙСТВА для нового аккаунта. Свои, а не те же, что у владельца:
			// одно устройство — один набор ключей на аккаунт, иначе конверт, посланный
			// виртуальному, открывался бы ключом основного, и «отдельный пользователь»
			// оставался бы словами.
			EncryptionPub string `json:"encryption_pub"`
			SigningPub    string `json:"signing_pub"`
			Platform      string `json:"platform,omitempty"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		b64 := base64.RawURLEncoding
		identityPub, err1 := b64.DecodeString(req.IdentityPub)
		signature, err2 := b64.DecodeString(req.Signature)
		enc, err3 := b64.DecodeString(req.EncryptionPub)
		sig, err4 := b64.DecodeString(req.SigningPub)
		if err1 != nil || err2 != nil || len(identityPub) != 32 || len(signature) != 64 {
			writeErr(w, http.StatusBadRequest, "bad_encoding",
				"identity_pub и signature — base64url, 32 и 64 байта")
			return
		}
		if err3 != nil || err4 != nil || len(enc) != 32 || len(sig) != 32 {
			writeErr(w, http.StatusBadRequest, "bad_keys",
				"encryption_pub и signing_pub — base64url, по 32 байта")
			return
		}

		id, _ := auth.FromContext(r.Context())
		ownerPub, err := st.IdentityPub(r.Context(), id.UserID)
		if err != nil || len(ownerPub) != 32 {
			// Ключ личности ставится при регистрации. Нет его — заводить виртуальный
			// нечем: подпись проверить не по чему, и создание опиралось бы на токен.
			writeErr(w, http.StatusConflict, "no_identity",
				"у аккаунта нет ключа личности — сначала войдите по фразе")
			return
		}
		if !timacrypto.VerifyEnvelopeSignature(ownerPub, virtualSigned(req.Nickname, identityPub), signature) {
			writeErr(w, http.StatusForbidden, "bad_signature", "подпись владельца не прошла проверку")
			return
		}

		userID, err := st.CreateVirtual(r.Context(), id.UserID, req.Nickname, identityPub)
		switch {
		case errors.Is(err, store.ErrNicknameBad):
			writeErr(w, http.StatusBadRequest, "bad_nickname",
				"ник — от 10 до 20 знаков: латиница, цифры, подчёркивание")
			return
		case errors.Is(err, store.ErrNicknameTaken):
			writeErr(w, http.StatusConflict, "nickname_taken", "этот ник уже занят")
			return
		case errors.Is(err, store.ErrTooManyVirtuals):
			// 409, а не 400: запрос правильный, кончилось место.
			writeErr(w, http.StatusConflict, "too_many_virtuals",
				"больше пяти виртуальных аккаунтов на номер нельзя")
			return
		case errors.Is(err, store.ErrOwnerIsVirtual):
			writeErr(w, http.StatusForbidden, "owner_is_virtual",
				"виртуальный аккаунт не заводит виртуальных")
			return
		case err != nil:
			log.Printf("createVirtual: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}

		// Устройство и токен выдаются сразу. Без них аккаунт был бы заведён и
		// недоступен: войти в него нечем — кода из SMS не будет никогда, номера у него
		// нет. Заводит его то же устройство, что и просило, — оно им и пользуется.
		deviceID, err := st.NewDevice(r.Context(), userID, enc, sig, normalizePlatform(req.Platform))
		if err != nil {
			log.Printf("createVirtual: устройство: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		access, err := tokens().IssueAccess(userID, deviceID)
		if err != nil {
			log.Printf("createVirtual: токен: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}

		// Фразы восстановления в ответе нет и быть не может: её придумал клиент, из неё
		// он вывел ключ, и сервер её не видел. Сказать «сохраните фразу» — дело клиента.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"user_id":      userID,
			"nickname":     req.Nickname,
			"device_id":    deviceID,
			"access_token": access,
		})
	}
}

// listVirtuals — GET /users/me/virtuals: свои виртуальные аккаунты с их никами.
//
// Чужих не показывает никто и никогда: связь виртуального аккаунта с владельцем лежит на
// сервере, и раздавать её наружу значило бы раскрывать ровно то, ради чего этот аккаунт
// и заводится.
func listVirtuals(st VirtualStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		ids, err := st.VirtualsOf(r.Context(), id.UserID)
		if err != nil {
			log.Printf("listVirtuals: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		nicks, err := st.Nicknames(r.Context(), ids)
		if err != nil {
			log.Printf("listVirtuals: ники: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]string, 0, len(ids))
		for _, userID := range ids {
			out = append(out, map[string]string{"user_id": userID, "nickname": nicks[userID]})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"virtuals": out,
			"limit":    store.VirtualLimit,
		})
	}
}
