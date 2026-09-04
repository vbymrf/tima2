// Сообщения групп (api-overview.md §Группы (переписка)): POST/GET
// /groups/{id}/messages. Private-группа: payload = SecretBox(zstd(MessageBody), GK),
// gk_version обязателен; публичная: plaintext protobuf, gk_version отсутствует.
// Подпись Ed25519 устройства по group_message_canonical_bytes
// (schema/proto/README.md; KAT group_message_canonical) проверяется при приёме.
//
// Премодерация (pending) — когда data-model §4 получит колонку статуса;
// сообщения от имени сущности (sender_type='entity', боты) — вместе с Bot API.
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/escrow"
	"tima/server/internal/store"
)

// postGroupMessage — POST /groups/{groupID}/messages.
func postGroupMessage(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		g, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		if role == "" {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		}
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())

		// Бан и slow mode (модераторам и выше slow mode не действует)
		_, bannedUntil, err := deps.store.GroupMemberInfo(r.Context(), groupID, id.UserID)
		if err != nil {
			log.Printf("postGroupMessage: member info: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if bannedUntil != nil && bannedUntil.After(time.Now()) {
			writeErr(w, http.StatusForbidden, "banned", "участник заблокирован до "+bannedUntil.UTC().Format(time.RFC3339))
			return
		}
		if g.SlowModeSec > 0 && roleRank[role] < rankModerator {
			recent, err := deps.store.SenderPostedWithin(r.Context(), groupID, id.UserID, g.SlowModeSec)
			if err != nil {
				log.Printf("postGroupMessage: slow mode: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if recent {
				w.Header().Set("Retry-After", strconv.Itoa(int(g.SlowModeSec)))
				writeErr(w, http.StatusTooManyRequests, "slow_mode", "подождите slow mode")
				return
			}
		}

		var req struct {
			ClientMsgID     string `json:"client_msg_id"`
			Kind            uint32 `json:"kind"`
			Level           *int16 `json:"level"` // nil = умолчание по виду группы
			GKVersion       int32  `json:"gk_version"`
			Payload         string `json:"payload"` // base64url
			ThreadRoot      int64  `json:"thread_root"`
			ReplyTo         int64  `json:"reply_to"`
			CreatedAtUnixMs int64  `json:"created_at_unix_ms"`
			Signature       string `json:"signature"` // base64url, Ed25519
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, maxEnvelopeBytes+64<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.ClientMsgID == "" {
			writeErr(w, http.StatusBadRequest, "no_client_msg_id", "нужен client_msg_id (UUID)")
			return
		}
		b64 := base64.RawURLEncoding
		payload, err1 := b64.DecodeString(req.Payload)
		signature, err2 := b64.DecodeString(req.Signature)
		if err1 != nil || err2 != nil || len(signature) != 64 {
			writeErr(w, http.StatusBadRequest, "bad_encoding", "payload/signature — base64url, подпись 64 байта")
			return
		}
		if len(payload) == 0 || len(payload) > maxEnvelopeBytes {
			writeErr(w, http.StatusBadRequest, "bad_payload", "payload пуст или больше 4 MiB")
			return
		}
		if req.ThreadRoot < 0 || req.ReplyTo < 0 || req.GKVersion < 0 {
			writeErr(w, http.StatusBadRequest, "bad_refs", "thread_root/reply_to/gk_version не могут быть отрицательными")
			return
		}

		// Уровень: кому сервер отдаст сообщение (ADR-0019). В подпись не входит —
		// он меняется после отправки, а подпись неизменна.
		level := defaultLevel(g.Kind)
		if req.Level != nil {
			level = *req.Level
		}
		if level < levelSecret || level > levelByGrant {
			writeErr(w, http.StatusBadRequest, "bad_level", "уровень вне диапазона -1…3")
			return
		}
		// Личная группа знает только -1 и 0 (ADR-0019 §2): всё, кроме описания,
		// зашифровано, а градаций внутри шифра нет.
		if g.Kind == "private" && level > levelPublicShowcase {
			writeErr(w, http.StatusBadRequest, "level_in_private", "в личной группе бывают только уровни -1 и 0")
			return
		}
		// В публичной группе шифра нет вовсе, значит и -1 неоткуда взяться.
		if g.Kind == "public" && level == levelSecret {
			writeErr(w, http.StatusBadRequest, "secret_in_public", "публичная группа не шифруется: уровень -1 недоступен")
			return
		}
		// Открытый текст на сервере ограничен по размеру: без предела он превращается
		// в бесплатный хостинг. Точный предел — 4096 знаков UTF-16 — проверяет клиент,
		// потому что знает содержимое; сервер видит байты и держит грубую границу.
		if level >= levelPublicShowcase && len(payload) > maxPlainPayloadBytes {
			writeErr(w, http.StatusBadRequest, "payload_too_large", "открытое сообщение больше 16 KiB")
			return
		}

		// Крипто-инварианты по типу группы
		if g.Kind == "private" && level == levelSecret {
			if req.GKVersion == 0 {
				writeErr(w, http.StatusBadRequest, "no_gk_version", "private-группа: нужен gk_version")
				return
			}
			if len(payload) < 24+16 {
				writeErr(w, http.StatusBadRequest, "bad_payload", "payload короче минимума SecretBox")
				return
			}
			known, err := deps.store.GroupKeyVersionExists(r.Context(), groupID, req.GKVersion)
			if err != nil {
				log.Printf("postGroupMessage: gk version: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if !known {
				writeErr(w, http.StatusBadRequest, "unknown_gk_version", "такой версии GK у группы нет")
				return
			}
		} else if req.GKVersion != 0 {
			// Незашифрованное сообщение с версией ключа — противоречие: версия говорит,
			// что payload закрыт, а уровень — что открыт.
			writeErr(w, http.StatusBadRequest, "gk_without_secret", "gk_version бывает только у уровня -1")
			return
		}

		// Ссылки веток/ответов — на сообщения этой же группы
		for name, ref := range map[string]int64{"thread_root": req.ThreadRoot, "reply_to": req.ReplyTo} {
			if ref == 0 {
				continue
			}
			exists, err := deps.store.GroupMessageExists(r.Context(), groupID, ref)
			if err != nil {
				log.Printf("postGroupMessage: ref %s: %v", name, err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if !exists {
				writeErr(w, http.StatusBadRequest, "bad_"+name, name+" не найден в группе")
				return
			}
		}

		// Подпись: устройство из токена, preimage — group_message_canonical_bytes
		signingPub, err := deps.store.SigningKey(r.Context(), id.DeviceID, id.UserID)
		if errors.Is(err, store.ErrDeviceUnknown) {
			writeErr(w, http.StatusForbidden, "unknown_device", "устройство отправителя не зарегистрировано")
			return
		} else if err != nil {
			log.Printf("postGroupMessage: signing key: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		cb := timacrypto.GroupMessageCanonicalBytes(timacrypto.GroupMessageMeta{
			GroupID:         groupID,
			SenderID:        id.UserID,
			SenderDevice:    id.DeviceID,
			Kind:            req.Kind,
			CreatedAtUnixMs: req.CreatedAtUnixMs,
			ThreadRoot:      uint64(req.ThreadRoot),
			ReplyTo:         uint64(req.ReplyTo),
			GKVersion:       uint32(req.GKVersion),
		}, payload)
		if !timacrypto.VerifyEnvelopeSignature(signingPub, cb, signature) {
			writeErr(w, http.StatusForbidden, "bad_signature", "подпись сообщения не прошла проверку")
			return
		}

		msg := store.GroupMessage{
			GroupID:         groupID,
			ClientMsgID:     req.ClientMsgID,
			SenderID:        id.UserID,
			SenderDevice:    id.DeviceID,
			Kind:            int32(req.Kind),
			GKVersion:       req.GKVersion,
			Payload:         payload,
			ThreadRoot:      req.ThreadRoot,
			ReplyTo:         req.ReplyTo,
			CreatedAtUnixMs: req.CreatedAtUnixMs,
			Signature:       signature,
			Level:           level,
		}
		messageID, duplicate, err := deps.store.SaveGroupMessage(r.Context(), msg)
		if err != nil {
			log.Printf("postGroupMessage: save: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if duplicate {
			_ = json.NewEncoder(w).Encode(map[string]any{"duplicate": true, "message_id": messageID})
			return
		}
		msg.MessageID = messageID

		// Доставка активным участникам (кроме устройства отправителя):
		// event log + live онлайн-устройствам
		devices, err := deps.store.ActiveMemberDevices(r.Context(), groupID, id.DeviceID)
		if err != nil {
			log.Printf("postGroupMessage: member devices: %v", err)
		}
		eventPayload := groupMessageJSON(msg)
		for _, dev := range devices {
			deps.notifier.Device(r.Context(), dev, "message.group", eventPayload)
		}

		// Инвариант ADR-0017 §2: в группе, где в эту эпоху была отправка, последняя версия
		// GK обязана быть завёрнута на текущую эпоху. Отправка только что произошла — самое
		// время проверить. Сервер ротировать не может (ключа он не видит), поэтому просит
		// участников: кто первым откроет приложение, тот и сменит.
		//
		// ТОЛЬКО ДЛЯ ЗАШИФРОВАННОГО. Инвариант говорит про отправку, которая пользовалась
		// ключом: escrow-блоб у группы один на версию GK, и восстанавливать по ордеру
		// нужно то, что закрыто. Открытое сообщение (уровни 0…3) ключа не касается и
		// лежит на сервере открытым — восстанавливать в нём нечего.
		//
		// Без этого условия описание личной группы — сообщение уровня 0 — гнало бы тихую
		// группу на ротацию, то есть на фан-аут обёрток по всем устройствам всех
		// участников. Недосмотр этапа Г1, где открытые сообщения появились; исправлено
		// 2026-09-04.
		if level == levelSecret {
			remindAboutRotation(deps, r.Context(), groupID, append(devices, id.DeviceID))
		}

		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": messageID})
	}
}

// groupMessageJSON — сообщение в JSON-обвязке (все поля preimage — получатель
// обязан суметь проверить подпись).
func groupMessageJSON(m store.GroupMessage) map[string]any {
	b64 := base64.RawURLEncoding
	return map[string]any{
		"message_id":         m.MessageID,
		"group_id":           m.GroupID,
		"sender_id":          m.SenderID,
		"sender_device":      m.SenderDevice,
		"kind":               m.Kind,
		"gk_version":         m.GKVersion,
		"payload":            b64.EncodeToString(m.Payload),
		"thread_root":        m.ThreadRoot,
		"reply_to":           m.ReplyTo,
		"created_at_unix_ms": m.CreatedAtUnixMs,
		"signature":          b64.EncodeToString(m.Signature),
		"level":              m.Level,
	}
}

// listGroupMessages — GET /groups/{groupID}/messages?before=&limit=&thread=.
func listGroupMessages(deps groupsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		g, role, ok := groupAndRole(deps, w, r)
		if !ok {
			return
		}
		// Не-участник получает историю только до уровня «всем» — и только если карточка
		// ему открыта. Иначе 404: существование личной группы не должно быть видно.
		if role == "" {
			id, _ := auth.FromContext(r.Context())
			open, err := cardOpenTo(deps, r, g.Kind, g.GroupID, id.UserID)
			if err != nil {
				log.Printf("listGroupMessages: card audience: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if !open {
				writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
				return
			}
		}
		var before, thread int64
		if v := r.URL.Query().Get("before"); v != "" {
			before, _ = strconv.ParseInt(v, 10, 64)
		}
		if v := r.URL.Query().Get("thread"); v != "" {
			thread, _ = strconv.ParseInt(v, 10, 64)
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

		// Граница выдачи: роль плюс поимённое разрешение, если оно есть и не истекло.
		max := maxLevelFor(role)
		if role != "" && max < levelByGrant {
			id, _ := auth.FromContext(r.Context())
			if granted, err := deps.store.GrantedLevelFor(r.Context(), r.PathValue("groupID"), id.UserID); err != nil {
				log.Printf("listGroupMessages: grant: %v", err)
			} else if granted > max {
				max = granted
			}
		}
		msgs, err := deps.store.ListGroupMessages(r.Context(), r.PathValue("groupID"), thread, before, limit, max)
		if err != nil {
			log.Printf("listGroupMessages: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(msgs))
		for _, m := range msgs {
			out = append(out, groupMessageJSON(m))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"messages": out})
	}
}

// remindAboutRotation рассылает group.rotation_needed, если ключ группы завёрнут на
// прошлую эпоху (ADR-0017 §3).
//
// **Не чаще раза в час на группу и эпоху.** Иначе каждое сообщение в группе с устаревшим
// ключом порождало бы фан-аут события на все устройства — то есть наказывало бы за
// разговорчивость. Одного напоминания достаточно: получивший его клиент ротирует, и
// условие перестанет выполняться само.
//
// Без Redis (dev) напоминание уходит на каждое сообщение: это шумно, но честнее, чем
// молчать о невыполнимом ордере.
func remindAboutRotation(deps groupsDeps, ctx context.Context, groupID string, devices []string) {
	latest, err := deps.store.LatestGroupRotation(ctx, groupID)
	if err != nil {
		log.Printf("напомнитьОРотации: последняя ротация: %v", err)
		return
	}
	// Группа без ротаций ключа не имеет вовсе — напоминать не о чем: первую версию
	// выпустят при первой отправке в private-группу.
	if latest.GKVersion == 0 {
		return
	}
	epoch := escrow.EpochOf(time.Now())
	if latest.EscrowEpoch == epoch {
		return
	}
	// Эпоха сменилась — значит ключ всё равно будет ротироваться. Ровно здесь выводим
	// тех, чей срок участия вышел (ADR-0019 §9): выбытие обязано менять ключ, и делать
	// его в момент, когда ключ и так меняется, дешевле всего. Отдельной задачи по
	// расписанию поэтому не заводится — это было условием решения о сроках.
	//
	// Читать группу просроченный перестаёт раньше и без этого: `GroupRole` проверяет
	// срок тем же запросом, которым читает роль. Здесь — только уборка состава.
	if n, err := deps.store.ExpireMemberships(ctx, groupID); err != nil {
		log.Printf("expire memberships %s: %v", groupID, err)
	} else if n > 0 {
		log.Printf("группа %s: срок вышел у %d участников, ключ сменится ротацией эпохи", groupID, n)
	}
	if deps.limiter() != nil {
		ok, _, err := deps.limiter().Allow(ctx, "gk_epoch_notify:"+groupID+":"+epoch, 1, time.Hour)
		if err != nil || !ok {
			return
		}
	}
	for _, dev := range devices {
		deps.notifier.Device(ctx, dev, "group.rotation_needed", map[string]any{
			"group_id": groupID,
			"reason":   "epoch",
			"epoch":    epoch,
		})
	}
}
