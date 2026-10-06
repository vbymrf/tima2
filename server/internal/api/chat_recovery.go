// Восстановление истории личного чата (ADR-0010 §этап 2). Устройство без ключей
// старых сообщений запрашивает их у помощников — своих устройств (авто) или у
// собеседника (с согласия на клиенте). Помощник перезаворачивает message_key под
// новое устройство; сервер кладёт обёртки в personal_message_keys и уведомляет.
// Аутентификация запроса — device JWT + подпись ключом личности (если установлен).
package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strconv"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/store"
)

// chatBackupSave — POST /chats/{chatID}/backup: владелец кладёт резервные обёртки
// ключей сообщений под свой backup_key (ADR-0010 §этап 4, «сообщения себе»).
func chatBackupSave(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		chatID := r.PathValue("chatID")
		id, _ := auth.FromContext(r.Context())
		// Участник — аккаунт любой своей личностью (М6): новая кладёт копию в переписки прежней.
		participant, err := accountParticipant(deps, r, chatID, id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !participant {
			writeErr(w, http.StatusForbidden, "not_participant", "бэкап доступен только участнику чата")
			return
		}
		var req struct {
			// Эпоха ключа копии, под который завёрнуты обёртки (§3а): обязана быть действующей.
			Epoch int `json:"epoch"`
			Items []struct {
				MessageID uint64 `json:"message_id"`
				Wrapped   string `json:"wrapped"`
			} `json:"items"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&req); err != nil || len(req.Items) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны items")
			return
		}
		b64 := base64.RawURLEncoding
		items := make([]store.MessageBackup, 0, len(req.Items))
		for _, it := range req.Items {
			wrapped, derr := b64.DecodeString(it.Wrapped)
			if derr != nil || len(wrapped) < 24+16 {
				writeErr(w, http.StatusBadRequest, "bad_wrapped", "некорректная обёртка бэкапа")
				return
			}
			items = append(items, store.MessageBackup{MessageID: it.MessageID, Wrapped: wrapped})
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
		if err := deps.store.SaveMessageBackups(r.Context(), chatID, id.UserID, epoch, items); err != nil {
			log.Printf("chatBackupSave: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"saved": len(items)})
	}
}

// chatBackupList — GET /chats/{chatID}/backup: резервные обёртки владельца
// (новое устройство разворачивает их backup_key из фразы и переносит историю).
func chatBackupList(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		chatID := r.PathValue("chatID")
		id, _ := auth.FromContext(r.Context())
		// Участник — аккаунт любой своей личностью (М6): новая кладёт копию в переписки прежней.
		participant, err := accountParticipant(deps, r, chatID, id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !participant {
			writeErr(w, http.StatusForbidden, "not_participant", "бэкап доступен только участнику чата")
			return
		}
		who, ok := copyOwner(deps, r, id.UserID)
		if !ok {
			writeErr(w, http.StatusForbidden, "not_your_identity", "копия чужой личности не выдаётся")
			return
		}
		var before uint64
		if v := r.URL.Query().Get("before"); v != "" {
			before, _ = strconv.ParseUint(v, 10, 64)
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		epoch, err := currentCopyEpoch(deps, r, who)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		items, err := deps.store.ListMessageBackups(r.Context(), chatID, who, epoch, before, limit)
		if err != nil {
			log.Printf("chatBackupList: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Страница копии — с конвертами (М3): новое устройство открывает их тем же разбором,
		// что живые. Обёртка копии — `эфемерал (32) || обёртка`, отдаётся раздельно, как у
		// истории: конверт с адресатом `key-copy` и эфемерал рядом.
		b64 := base64.RawURLEncoding
		type item struct {
			MessageID uint64 `json:"message_id"`
			Envelope  string `json:"envelope"`
			WrapEph   string `json:"wrap_ephemeral"`
		}
		out := make([]item, 0, len(items))
		for _, it := range items {
			if len(it.WrappedKeyForDevice) <= 32 {
				continue
			}
			blob := it.WrappedKeyForDevice
			it.WrappedKeyForDevice = blob[32:]
			raw, err := storedEnvelope(it, keyCopyRecipient)
			if err != nil {
				log.Printf("chatBackupList: marshal: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка сериализации")
				return
			}
			out = append(out, item{MessageID: it.MessageID, Envelope: b64.EncodeToString(raw), WrapEph: b64.EncodeToString(blob[:32])})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"items": out})
	}
}

// chatRecover — POST /chats/{chatID}/recover: запрос восстановления истории личного чата.
func chatRecover(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		chatID := r.PathValue("chatID")
		id, _ := auth.FromContext(r.Context())

		participant, err := deps.store.IsChatParticipant(r.Context(), chatID, id.UserID)
		if err != nil {
			log.Printf("chatRecover: participant: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !participant {
			writeErr(w, http.StatusForbidden, "not_participant", "восстановление доступно только участнику чата")
			return
		}

		// Подпись ключом личности (этап 3), если он установлен у аккаунта, и номера сообщений,
		// которые устройство само видит недоступными (2026-10-06). Сколько номеров — решает
		// размер тела, а не счёт: клиент кладёт столько, сколько влезает в [recoverBodyLimit].
		var req struct {
			Signature string  `json:"signature"`
			Missing   []int64 `json:"missing"`
		}
		body, _ := io.ReadAll(io.LimitReader(r.Body, recoverBodyLimit+1))
		if len(body) > recoverBodyLimit {
			writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "просьба больше предела")
			return
		}
		if len(bytes.TrimSpace(body)) > 0 && json.Unmarshal(body, &req) != nil {
			writeErr(w, http.StatusBadRequest, "bad_request", "тело просьбы не разбирается")
			return
		}
		identityPub, err := deps.store.IdentityPub(r.Context(), id.UserID)
		if err != nil {
			log.Printf("chatRecover: identity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Заверенное устройство просит без фразы (заказчик 2026-10-06: «сообщение недоступно,
		// запросить» и запрос сам по себе): заверить его без фразы было нельзя, и подпись
		// фразой на каждый запрос ничего к этому не добавляет. Незаверенное — как прежде.
		certified, err := deps.store.DeviceCertified(r.Context(), id.UserID, id.DeviceID)
		if err != nil {
			log.Printf("chatRecover: certified: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if len(identityPub) == 32 && !certified {
			sig, derr := base64.RawURLEncoding.DecodeString(req.Signature)
			if derr != nil || !timacrypto.VerifyEnvelopeSignature(identityPub, recoverCanonical(chatID, id.DeviceID), sig) {
				writeErr(w, http.StatusForbidden, "bad_identity_sig", "запрос не подписан ключом личности аккаунта")
				return
			}
		}

		// Названное устройством — разобрать: уже есть, вернёт помощник, потеряно; добрать то,
		// чего оно не видело. Список помощнику — в тех же байтах, что и просьба.
		plan, err := deps.store.ChatRecovery(r.Context(), chatID, id.DeviceID, id.UserID, req.Missing, recoverIDsBytes)
		if err != nil {
			log.Printf("chatRecover: plan: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		encPub, err := deps.store.DeviceEncryptionPub(r.Context(), id.DeviceID)
		if err != nil {
			log.Printf("chatRecover: enc pub: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Ключ на сервере уже есть — помощник не нужен: устройство заберёт историю само.
		if len(plan.Ready) > 0 {
			deps.notifier.Device(r.Context(), id.DeviceID, "recovery.msg_ready", map[string]any{
				"chat_id": chatID, "count": len(plan.Ready),
			})
		}
		lost := plan.Lost
		if lost == nil {
			lost = []int64{}
		}
		b64 := base64.RawURLEncoding
		own := 0
		for _, h := range plan.Helpers {
			if h.Own {
				own++
			}
			deps.notifier.Device(r.Context(), h.DeviceID, "recovery.msg_request", map[string]any{
				"chat_id":           chatID,
				"requester_device":  id.DeviceID,
				"requester_enc_pub": b64.EncodeToString(encPub),
				"own":               h.Own, // свои устройства помогают без согласия
				// Чьё устройство просит и подпись фразой, если она была: помощник отдаёт только
				// заверенному или подписавшему фразой и проверяет это сам (2026-10-06).
				"requester_user": id.UserID,
				"signature":      req.Signature,
				"missing":        plan.Recoverable,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"helpers": len(plan.Helpers), "own_helpers": own,
			"missing": len(plan.Recoverable), "ready": len(plan.Ready), "lost": lost,
		})
	}
}

// chatRecoverProvide — POST /chats/{chatID}/recover/provide: помощник отдаёт обёртки
// message_key под устройство-запросившее.
func chatRecoverProvide(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		chatID := r.PathValue("chatID")
		id, _ := auth.FromContext(r.Context())

		participant, err := deps.store.IsChatParticipant(r.Context(), chatID, id.UserID)
		if err != nil {
			log.Printf("chatRecoverProvide: participant: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !participant {
			writeErr(w, http.StatusForbidden, "not_participant", "делиться ключами может только участник чата")
			return
		}
		var req struct {
			RequesterDevice string `json:"requester_device"`
			Keys            []struct {
				MessageID          uint64 `json:"message_id"`
				SenderEphemeralPub string `json:"sender_ephemeral_pub"`
				Wrapped            string `json:"wrapped"`
			} `json:"keys"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&req); err != nil || req.RequesterDevice == "" || len(req.Keys) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны requester_device и keys")
			return
		}
		ok, err := deps.store.IsChatParticipantDevice(r.Context(), chatID, req.RequesterDevice)
		if err != nil {
			log.Printf("chatRecoverProvide: requester check: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !ok {
			writeErr(w, http.StatusBadRequest, "not_participant_device", "получатель — не устройство участника чата")
			return
		}
		b64 := base64.RawURLEncoding
		keys := make([]store.RecoveryMessageKey, 0, len(req.Keys))
		for _, k := range req.Keys {
			eph, err1 := b64.DecodeString(k.SenderEphemeralPub)
			wrapped, err2 := b64.DecodeString(k.Wrapped)
			if err1 != nil || err2 != nil || len(eph) != 32 || len(wrapped) < 24+16+32 {
				writeErr(w, http.StatusBadRequest, "bad_key", "некорректная обёртка восстановления")
				return
			}
			keys = append(keys, store.RecoveryMessageKey{MessageID: k.MessageID, SenderEphemeralPub: eph, Wrapped: wrapped})
		}
		if err := deps.store.SaveRecoveryMessageKeys(r.Context(), chatID, req.RequesterDevice, keys); err != nil {
			log.Printf("chatRecoverProvide: save: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		deps.notifier.Device(r.Context(), req.RequesterDevice, "recovery.msg_ready", map[string]any{
			"chat_id": chatID, "count": len(keys),
		})
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"saved": len(keys)})
	}
}

// listPersonalChats — GET /chats/personal: мои личные переписки и собеседник каждой
// (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ИУ1). Новое устройство по нему заводит строки списка и забирает
// историю, которую ему перезавернуло своё доверенное устройство.
//
// Список собеседников — это сведения о человеке, а не только о переписке. В строгом режиме
// доверия его получает только заверенное устройство: укравший SIM иначе узнал бы, с кем
// человек переписывается, даже не читая сообщений.
func listPersonalChats(deps chatsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if deps.trust != nil && deps.trust() == trustRequire {
			ok, err := deps.store.DeviceCertified(r.Context(), id.UserID, id.DeviceID)
			if err != nil {
				log.Printf("listPersonalChats: certified: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if !ok {
				writeErr(w, http.StatusForbidden, "device_unproven", "устройство не заверено — список переписок не выдаётся")
				return
			}
		}
		// Чьи переписки: свои; `owner` — другой личности своего аккаунта; `all=1` — всех его
		// личностей с отметкой, чья (М6, Р55): новая личность поднимает и копию прежней.
		owners := []string{id.UserID}
		if r.URL.Query().Get("all") == "1" {
			ids, err := deps.store.IdentitiesOfAccount(r.Context(), id.UserID)
			if err != nil {
				log.Printf("listPersonalChats: identities: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			owners = ids
		} else if who, ok := copyOwner(deps, r, id.UserID); !ok {
			writeErr(w, http.StatusForbidden, "not_your_identity", "переписки чужой личности не выдаются")
			return
		} else {
			owners = []string{who}
		}
		type item struct {
			ChatID        string `json:"chat_id"`
			PeerID        string `json:"peer_id"`
			LastMessageID uint64 `json:"last_message_id"`
			OwnerID       string `json:"owner_id"`
		}
		out := []item{}
		for _, owner := range owners {
			chats, err := deps.store.PersonalChatsOf(r.Context(), owner)
			if err != nil {
				log.Printf("listPersonalChats: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			for _, c := range chats {
				out = append(out, item{ChatID: c.ChatID, PeerID: c.PeerID, LastMessageID: c.LastMessageID, OwnerID: owner})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"chats": out})
	}
}

// recoverBodyLimit — предел тела просьбы о ключах переписки. Сколько сообщений в ней назвать,
// решает он, а не счёт (заказчик 2026-10-06): клиент кладёт номера, пока тело влезает.
// recoverIDsBytes — столько же байт списка сервер называет помощнику; запас — на подпись и поля.
const (
	recoverBodyLimit = 4096
	recoverIDsBytes  = recoverBodyLimit - 256
)
