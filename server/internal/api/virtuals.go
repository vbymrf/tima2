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
}

var _ VirtualStore = (*store.Store)(nil)

// RegisterVirtuals — две ручки.
func RegisterVirtuals(mux *http.ServeMux, st VirtualStore, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/virtuals", requireDevice(listVirtuals(st)))
	mux.HandleFunc("POST /api/v1/users/me/virtuals", requireDevice(createVirtual(st)))
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
func createVirtual(st VirtualStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Nickname    string `json:"nickname"`
			IdentityPub string `json:"identity_pub"` // base64url, 32 байта
			Signature   string `json:"signature"`    // base64url, 64 байта, ключом владельца
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		b64 := base64.RawURLEncoding
		identityPub, err1 := b64.DecodeString(req.IdentityPub)
		signature, err2 := b64.DecodeString(req.Signature)
		if err1 != nil || err2 != nil || len(identityPub) != 32 || len(signature) != 64 {
			writeErr(w, http.StatusBadRequest, "bad_encoding",
				"identity_pub и signature — base64url, 32 и 64 байта")
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

		// Фразы восстановления в ответе нет и быть не может: её придумал клиент, из неё
		// он вывел ключ, и сервер её не видел. Сказать «сохраните фразу» — дело клиента.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"user_id": userID, "nickname": req.Nickname})
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
