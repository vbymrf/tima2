// Копия ключей по модели Matrix (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ §3а, Р43–Р47, М1–М7).
//
// Ключи сообщений по-прежнему заворачиваются на каждое устройство. Копия — ещё одна обёртка
// каждого ключа, под ОТКРЫТЫЙ ключ копии личности: пара выводится из фразы, открытая часть
// публикуется здесь с подписью ключом личности. Пополняют копию все свои устройства без
// секрета, открывает — тот, у кого фраза. Сервер видит только шифртекст и не может подсунуть
// свой ключ: подпись проверяет и он, и каждое устройство.
package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"

	"google.golang.org/protobuf/proto"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	pb "tima/server/internal/proto"
	"tima/server/internal/store"
)

// keyCopyRecipient — адресат обёртки копии в конверте, который сервер отдаёт для
// восстановления. Не устройство: клиент разворачивает её ключом копии, а не устройства.
const keyCopyRecipient = "key-copy"

// keyCopySigned — байты, которые подписывает ключ личности: эпоха и открытый ключ копии.
func keyCopySigned(epoch int, pub []byte) []byte {
	return []byte("tima.key-copy.v1|" + strconv.Itoa(epoch) + "|" + base64.RawURLEncoding.EncodeToString(pub))
}

// getKeyCopy — GET /users/me/key-copy: действующий открытый ключ копии своей личности.
func getKeyCopy(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		k, err := deps.store.KeyCopy(r.Context(), id.UserID)
		if errors.Is(err, store.ErrKeyCopyMissing) {
			writeErr(w, http.StatusNotFound, "no_key_copy", "ключ копии ещё не опубликован")
			return
		} else if err != nil {
			log.Printf("getKeyCopy: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		b64 := base64.RawURLEncoding
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"epoch": k.Epoch, "pub": b64.EncodeToString(k.Pub), "sig": b64.EncodeToString(k.Sig)})
	}
}

// putKeyCopy — PUT /users/me/key-copy {epoch, pub, sig}: опубликовать или сменить пару (М1, М5).
// Подпись — ключом текущей личности; эпоха только растёт.
func putKeyCopy(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			Epoch int    `json:"epoch"`
			Pub   string `json:"pub"`
			Sig   string `json:"sig"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		b64 := base64.RawURLEncoding
		pub, err1 := b64.DecodeString(req.Pub)
		sig, err2 := b64.DecodeString(req.Sig)
		if err1 != nil || err2 != nil || len(pub) != 32 || len(sig) != 64 || req.Epoch < 1 {
			writeErr(w, http.StatusBadRequest, "bad_key_copy", "epoch ≥ 1, pub — 32 байта, sig — 64 байта (base64url)")
			return
		}
		identity, err := deps.store.IdentityPub(r.Context(), id.UserID)
		if err != nil || len(identity) != 32 {
			writeErr(w, http.StatusForbidden, "phrase_required", "копия ключей заводится только у личности с секретной фразой")
			return
		}
		if !timacrypto.VerifyEnvelopeSignature(identity, keyCopySigned(req.Epoch, pub), sig) {
			writeErr(w, http.StatusForbidden, "bad_signature", "ключ копии не подписан ключом личности")
			return
		}
		switch err := deps.store.SetKeyCopy(r.Context(), id.UserID, store.KeyCopyKey{Epoch: req.Epoch, Pub: pub, Sig: sig}); {
		case errors.Is(err, store.ErrKeyCopyStale):
			writeErr(w, http.StatusConflict, "stale_epoch", "эпоха копии не новее действующей")
			return
		case err != nil:
			log.Printf("putKeyCopy: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// currentCopyEpoch — эпоха действующего ключа копии; ноль — копии нет.
func currentCopyEpoch(deps chatsDeps, r *http.Request, userID string) (int, error) {
	k, err := deps.store.KeyCopy(r.Context(), userID)
	if errors.Is(err, store.ErrKeyCopyMissing) {
		return 0, nil
	}
	return k.Epoch, err
}

// saveGroupKeyCopies — POST /users/me/key-copy/groups {epoch, items:[{group_id, gk_version, wrapped}]}.
func saveGroupKeyCopies(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		var req struct {
			Epoch int `json:"epoch"`
			Items []struct {
				GroupID   string `json:"group_id"`
				GKVersion int32  `json:"gk_version"`
				Wrapped   string `json:"wrapped"`
			} `json:"items"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil || len(req.Items) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны items")
			return
		}
		epoch, err := currentCopyEpoch(deps, r, id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if epoch == 0 || req.Epoch != epoch {
			writeErr(w, http.StatusConflict, "stale_epoch", "копия под другой эпохой — перечитайте ключ копии")
			return
		}
		items := make([]store.GroupKeyCopy, 0, len(req.Items))
		for _, it := range req.Items {
			wrapped, derr := base64.RawURLEncoding.DecodeString(it.Wrapped)
			if derr != nil || len(wrapped) < 32+24+16 || it.GroupID == "" || it.GKVersion < 1 {
				writeErr(w, http.StatusBadRequest, "bad_wrapped", "некорректная обёртка ключа группы")
				return
			}
			items = append(items, store.GroupKeyCopy{GroupID: it.GroupID, GKVersion: it.GKVersion, Wrapped: wrapped})
		}
		if err := deps.store.SaveGroupKeyCopies(r.Context(), id.UserID, epoch, items); err != nil {
			log.Printf("saveGroupKeyCopies: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"saved": len(items)})
	}
}

// listGroupKeyCopies — GET /users/me/key-copy/groups: версии ключей групп в копии (М3).
func listGroupKeyCopies(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		epoch, err := currentCopyEpoch(deps, r, id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		items, err := deps.store.ListGroupKeyCopies(r.Context(), id.UserID, epoch)
		if err != nil {
			log.Printf("listGroupKeyCopies: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		b64 := base64.RawURLEncoding
		type item struct {
			GroupID   string `json:"group_id"`
			GKVersion int32  `json:"gk_version"`
			Wrapped   string `json:"wrapped"`
		}
		out := make([]item, 0, len(items))
		for _, it := range items {
			out = append(out, item{GroupID: it.GroupID, GKVersion: it.GKVersion, Wrapped: b64.EncodeToString(it.Wrapped)})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"epoch": epoch, "items": out})
	}
}

// storedEnvelope — конверт сообщения, как его отдаёт сервер, с одной обёрткой для [recipient].
func storedEnvelope(m store.StoredMessage, recipient string) ([]byte, error) {
	env := &pb.Envelope{
		FormatVersion: uint32(m.FormatVersion),
		Meta: &pb.Metadata{
			MessageId:       m.MessageID,
			ChatId:          m.ChatID,
			SenderId:        m.SenderID,
			SenderDevice:    m.SenderDevice,
			Kind:            pb.ContentKind(m.Kind),
			CreatedAtUnixMs: m.CreatedAtUnixMs,
			ReplyTo:         m.ReplyTo,
		},
		EncryptedPayload: m.EncryptedPayload,
		KeyCommitment:    m.KeyCommitment,
		Escrow: &pb.EscrowBlob{
			MlkemCt:           m.EscrowMlkemCt,
			WrappedMessageKey: m.EscrowWrappedKey,
			EscrowKeyVersion:  uint32(m.EscrowKeyVersion),
		},
		SenderEphemeralPub: m.SenderEphemeralPub,
		RatchetEnvelope:    m.RatchetEnvelope,
		Signature:          m.Signature,
		WrappedKeys:        []*pb.WrappedKey{{Recipient: recipient, Wrapped: m.WrappedKeyForDevice}},
	}
	return proto.Marshal(env)
}
