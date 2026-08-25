// API групповых ключей (api-overview.md §Группы; crypto-protocol.md §4).
// Серверная сторона клиентского GroupKeyManager: приём ротации GK и выдача
// пропущенных wrapped_GK. Сервер сами GK не видит — только escrow и обёртки.
// Права: ротирует ЛЮБОЙ действующий участник private-группы (ADR-0017 §5) —
// эпохальный триггер привязан к календарю, и право, привязанное к присутствию
// админа, сделало бы гарантию зависимой от чужого отпуска. Ротация прав не выдаёт:
// она заново заворачивает ключ на тот же состав, который знает сервер.
// Получатели обёрток — устройства активных участников (membership — group_service.go).
package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/escrow"
	"tima/server/internal/store"
)

// groupRotate — POST /groups/{groupID}/keys: новая версия GK (строго current+1).
func groupRotate(д группыDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			GKVersion          int32  `json:"gk_version"`
			Reason             string `json:"reason,omitempty"` // periodic|member_join|member_leave|compromise
			SenderEphemeralPub string `json:"sender_ephemeral_pub"`
			Escrow             struct {
				MlkemCt           string `json:"mlkem_ct"`
				WrappedMessageKey string `json:"wrapped_message_key"`
				EscrowKeyVersion  int32  `json:"escrow_key_version"`
			} `json:"escrow"`
			WrappedKeys []struct {
				Recipient string `json:"recipient"`
				Wrapped   string `json:"wrapped"`
			} `json:"wrapped_keys"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		b64 := base64.RawURLEncoding
		ephPub, err := b64.DecodeString(req.SenderEphemeralPub)
		if err != nil || len(ephPub) != 32 {
			writeErr(w, http.StatusBadRequest, "bad_eph", "sender_ephemeral_pub — 32 байта base64url")
			return
		}
		mlkemCt, err1 := b64.DecodeString(req.Escrow.MlkemCt)
		escrowWrapped, err2 := b64.DecodeString(req.Escrow.WrappedMessageKey)
		if err1 != nil || err2 != nil || len(mlkemCt) != 1088 || len(escrowWrapped) < 24+16+32 {
			writeErr(w, http.StatusBadRequest, "bad_escrow", "escrow: mlkem_ct 1088 байт, wrapped ≥ 72 байт")
			return
		}
		if len(req.WrappedKeys) == 0 {
			writeErr(w, http.StatusBadRequest, "no_wrapped", "ротация без wrapped_keys бессмысленна")
			return
		}
		wrapped := make(map[string][]byte, len(req.WrappedKeys))
		for _, wk := range req.WrappedKeys {
			raw, err := b64.DecodeString(wk.Wrapped)
			if err != nil || wk.Recipient == "" || len(raw) < 24+16+32 {
				writeErr(w, http.StatusBadRequest, "bad_wrapped", "некорректный wrapped_GK")
				return
			}
			wrapped[wk.Recipient] = raw
		}
		reason := req.Reason
		if reason == "" {
			reason = "periodic"
		}

		// Права: группа существует, private, ротирующий — owner|admin
		g, role, ok := группаИРоль(д, w, r)
		if !ok {
			return
		}
		if g.Kind != "private" {
			writeErr(w, http.StatusBadRequest, "not_e2e", "GK есть только у private-групп")
			return
		}
		if role == "" {
			writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
			return
		}
		// Заблокированный не ротирует: бан снимает право писать, а ротация — это запись,
		// которую увидят все устройства группы.
		id, _ := auth.FromContext(r.Context())
		if _, bannedUntil, err := д.хранилище.GroupMemberInfo(r.Context(), r.PathValue("groupID"), id.UserID); err != nil {
			log.Printf("groupRotate: member info: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if bannedUntil != nil && bannedUntil.After(time.Now()) {
			writeErr(w, http.StatusForbidden, "banned", "заблокированный участник не ротирует ключ")
			return
		}

		// ── Причина и частота (ADR-0017 §7) ──────────────────────────────────────
		//
		// Проверка причины важнее порога: после успешной эпохальной ротации условие
		// перестаёт выполняться само, и одновременные попытки остальных участников
		// отвергаются как ненужные. При праве ротации у каждого это главная защита.
		if !допустимаяПричина(reason) {
			writeErr(w, http.StatusBadRequest, "bad_reason", "неизвестная причина ротации: "+reason)
			return
		}
		последняя, err := д.хранилище.LatestGroupRotation(r.Context(), r.PathValue("groupID"))
		if err != nil {
			log.Printf("groupRotate: последняя ротация: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Конфликт версии проверяется ПЕРВЫМ, раньше эпохи и порога. Эти отказы клиент
		// обрабатывает по-разному: version_conflict означает «кто-то ротировал раньше, цель
		// достигнута, перечитай», а rotation_too_soon — «повтори позже». Ответь мы порогом
		// на устаревшую версию, клиент ждал бы пятнадцать минут ради ротации, которая уже
		// произошла. Окончательная проверка всё равно идёт под блокировкой в хранилище —
		// здесь ранний выход, а не замена ей.
		if req.GKVersion != последняя.GKVersion+1 {
			writeErr(w, http.StatusConflict, "version_conflict",
				"текущая "+strconv.Itoa(int(последняя.GKVersion))+", предложена "+strconv.Itoa(int(req.GKVersion)))
			return
		}
		текущаяЭпоха := escrow.EpochOf(time.Now())
		if reason == причинаЭпоха && последняя.EscrowEpoch == текущаяЭпоха {
			// Не отказ по существу: ключ уже привязан к текущей эпохе, цель достигнута.
			// Клиент обязан считать это успехом (ADR-0017 §7).
			writeErr(w, http.StatusConflict, "rotation_not_needed", "ключ уже привязан к эпохе "+текущаяЭпоха)
			return
		}
		// Проверка названной причины по состоянию сервера (ADR-0017 §7). До первой ротации
		// подтверждать нечего: первая версия ключа группе нужна в любом случае.
		if последняя.GKVersion > 0 && !последняя.RotatedAt.IsZero() {
			доводы, err := д.хранилище.RotationEvidenceSince(r.Context(), r.PathValue("groupID"), последняя.RotatedAt)
			if err != nil {
				log.Printf("groupRotate: доводы: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if !причинаПодтверждена(reason, доводы) {
				writeErr(w, http.StatusConflict, "rotation_not_needed",
					"причина «"+reason+"» не подтверждается состоянием группы")
				return
			}
		}

		// Порог — подстраховка на случай ошибки в проверке причины, поэтому он только для
		// несрочных. Задержка ротации по составу означала бы, что исключённый читает
		// переписку ещё пятнадцать минут.
		if несрочная(reason) && !последняя.RotatedAt.IsZero() &&
			time.Since(последняя.RotatedAt) < порогРотации {
			w.Header().Set("Retry-After", strconv.Itoa(int(порогРотации.Seconds())))
			writeErr(w, http.StatusTooManyRequests, "rotation_too_soon",
				"предыдущая ротация моложе "+порогРотации.String())
			return
		}
		// Получатели wrapped_GK — действующие устройства активных участников
		recipients := make([]string, 0, len(wrapped))
		for rcpt := range wrapped {
			recipients = append(recipients, rcpt)
		}
		outsiders, err := д.хранилище.NonMemberDevices(r.Context(), r.PathValue("groupID"), recipients)
		if err != nil {
			log.Printf("groupRotate: non-member devices: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if len(outsiders) > 0 {
			writeErr(w, http.StatusBadRequest, "recipient_not_member",
				"wrapped_keys содержат устройства не-участников: "+strings.Join(outsiders, ", "))
			return
		}

		// Полнота получателей (ADR-0017 §6). Пока считаем и пишем в журнал, не отвергая:
		// сегодняшний клиент такую ротацию присылать может, а сервер обязан работать со
		// старым клиентом. Отказ missing_recipients включается после выката клиента.
		if все, err := д.хранилище.ActiveMemberDevices(r.Context(), r.PathValue("groupID"), ""); err != nil {
			log.Printf("groupRotate: устройства участников: %v", err)
		} else {
			непокрытые := make([]string, 0)
			for _, устройство := range все {
				if _, есть := wrapped[устройство]; !есть {
					непокрытые = append(непокрытые, устройство)
				}
			}
			if len(непокрытые) > 0 {
				// Это не мелочь: устройство без обёртки перестаёт читать группу молча —
				// для человека это выглядит как «в группе тихо», а не как отказ.
				log.Printf("groupRotate: группа %s версия %d — без обёрток остались устройства: %s",
					r.PathValue("groupID"), req.GKVersion, strings.Join(непокрытые, ", "))
			}
		}

		// Эпоха берётся у ТОГО ключа, которым клиент завернул блоб, а не по часам сервера:
		// клиент мог зашифровать на устаревший ключ, и «сейчас» стало бы неправдой в
		// истории. Неизвестная эпоха записывается пустой — тогда группа честно ротируется
		// при первой же активности, потому что пусто никогда не равно текущей эпохе.
		эпохаКлюча, err := д.хранилище.EscrowKeyEpoch(r.Context(), uint32(req.Escrow.EscrowKeyVersion))
		if err != nil {
			log.Printf("groupRotate: эпоха ключа %d неизвестна: %v", req.Escrow.EscrowKeyVersion, err)
			эпохаКлюча = ""
		}

		err = д.хранилище.SaveGroupRotation(r.Context(), store.GroupRotation{
			GroupID:            r.PathValue("groupID"),
			GKVersion:          req.GKVersion,
			RotatedBy:          id.UserID,
			SenderEphemeralPub: ephPub,
			EscrowMlkemCt:      mlkemCt,
			EscrowWrappedKey:   escrowWrapped,
			EscrowKeyVersion:   req.Escrow.EscrowKeyVersion,
			EscrowEpoch:        эпохаКлюча,
			Reason:             reason,
			WrappedKeys:        wrapped,
		})
		if errors.Is(err, store.ErrVersionConflict) {
			writeErr(w, http.StatusConflict, "version_conflict", err.Error())
			return
		} else if err != nil {
			log.Printf("groupRotate: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// key.rotated участникам: event log + live онлайн-устройствам (websocket-events.md)
		for recipient, w := range wrapped {
			д.уведомитель.Device(r.Context(), recipient, "key.rotated", map[string]any{
				"group_id":             r.PathValue("groupID"),
				"gk_version":           req.GKVersion,
				"sender_ephemeral_pub": req.SenderEphemeralPub,
				"wrapped_gk":           base64.RawURLEncoding.EncodeToString(w),
			})
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"group_id": r.PathValue("groupID"), "gk_version": req.GKVersion})
	}
}

// recoverCanonical — подписываемый ключом личности preimage запроса восстановления.
// Домен + group_id + requester_device (UUID без '|' — однозначно). Тот же в Kotlin-клиенте.
func recoverCanonical(groupID, requesterDevice string) []byte {
	return []byte("tima.recover.v1|" + groupID + "|" + requesterDevice)
}

// groupKeyRecover — POST /groups/{groupID}/keys/recover: устройство просит недостающие
// версии GK (историю до своего входа) у участников (ADR-0010 §этап 1). Сервер находит
// устройства-помощники, у кого эти версии есть, и рассылает им recovery.gk_request.
// Аутентификация запроса — device JWT + членство (крипто-подпись запроса ключом
// личности — этап 3). Согласие в группе автоматическое: помощник и так делится с
// участником, имеющим право на историю.
func groupKeyRecover(д группыDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())

		role, err := д.хранилище.GroupRole(r.Context(), groupID, id.UserID)
		if err != nil || role == "" {
			writeErr(w, http.StatusForbidden, "not_member", "восстановление доступно только участникам группы")
			return
		}

		// Аутентификация запроса ключом личности (ADR-0010 §этап 3): если у аккаунта
		// установлен identity_pub, запрос обязан быть им подписан — барьер против угона
		// номера (укравший SIM имеет device JWT, но без фразы не подпишет). Аккаунт без
		// фразы (identity_pub NULL) — восстановление по членству (совместимость).
		var req struct {
			Signature string `json:"signature"` // base64url, Ed25519 над recoverCanonical
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req)
		identityPub, err := д.хранилище.IdentityPub(r.Context(), id.UserID)
		if err != nil {
			log.Printf("groupKeyRecover: identity: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if len(identityPub) == 32 {
			sig, derr := base64.RawURLEncoding.DecodeString(req.Signature)
			if derr != nil || !timacrypto.VerifyEnvelopeSignature(identityPub, recoverCanonical(groupID, id.DeviceID), sig) {
				writeErr(w, http.StatusForbidden, "bad_identity_sig", "запрос не подписан ключом личности аккаунта")
				return
			}
		}
		missing, err := д.хранилище.MissingGKVersions(r.Context(), groupID, id.DeviceID)
		if err != nil {
			log.Printf("groupKeyRecover: missing: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if len(missing) == 0 {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"requested": 0, "helpers": 0})
			return
		}
		helpers, err := д.хранилище.HelperDevices(r.Context(), groupID, id.DeviceID, missing)
		if err != nil {
			log.Printf("groupKeyRecover: helpers: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		encPub, err := д.хранилище.DeviceEncryptionPub(r.Context(), id.DeviceID)
		if err != nil {
			log.Printf("groupKeyRecover: enc pub: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		b64 := base64.RawURLEncoding
		versions := make([]int32, len(missing))
		copy(versions, missing)
		for _, helper := range helpers {
			д.уведомитель.Device(r.Context(), helper, "recovery.gk_request", map[string]any{
				"group_id":          groupID,
				"requester_device":  id.DeviceID,
				"requester_enc_pub": b64.EncodeToString(encPub),
				"versions":          versions,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"requested": len(missing), "helpers": len(helpers)})
	}
}

// groupKeyProvide — POST /groups/{groupID}/keys/recover/provide: помощник присылает
// обёртки GK под устройство-запросившее. Сервер кладёт их в group_wrapped_keys и
// уведомляет получателя recovery.gk_ready. Проверки: помощник — участник; получатель —
// устройство активного участника (ключи не уходят чужому).
func groupKeyProvide(д группыDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())

		role, err := д.хранилище.GroupRole(r.Context(), groupID, id.UserID)
		if err != nil || role == "" {
			writeErr(w, http.StatusForbidden, "not_member", "делиться ключами может только участник")
			return
		}
		var req struct {
			RequesterDevice string `json:"requester_device"`
			Keys            []struct {
				GKVersion          int32  `json:"gk_version"`
				SenderEphemeralPub string `json:"sender_ephemeral_pub"`
				Wrapped            string `json:"wrapped"`
			} `json:"keys"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil || req.RequesterDevice == "" || len(req.Keys) == 0 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны requester_device и keys")
			return
		}
		member, err := д.хранилище.IsGroupMemberDevice(r.Context(), groupID, req.RequesterDevice)
		if err != nil {
			log.Printf("groupKeyProvide: member check: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !member {
			writeErr(w, http.StatusBadRequest, "not_member_device", "получатель — не устройство активного участника")
			return
		}
		b64 := base64.RawURLEncoding
		keys := make([]store.RecoveryKey, 0, len(req.Keys))
		for _, k := range req.Keys {
			eph, err1 := b64.DecodeString(k.SenderEphemeralPub)
			wrapped, err2 := b64.DecodeString(k.Wrapped)
			if err1 != nil || err2 != nil || len(eph) != 32 || len(wrapped) < 24+16+32 || k.GKVersion <= 0 {
				writeErr(w, http.StatusBadRequest, "bad_key", "некорректная обёртка восстановления")
				return
			}
			keys = append(keys, store.RecoveryKey{GKVersion: k.GKVersion, SenderEphemeralPub: eph, Wrapped: wrapped})
		}
		if err := д.хранилище.SaveRecoveryKeys(r.Context(), groupID, req.RequesterDevice, keys); err != nil {
			log.Printf("groupKeyProvide: save: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		д.уведомитель.Device(r.Context(), req.RequesterDevice, "recovery.gk_ready", map[string]any{
			"group_id": groupID, "count": len(keys),
		})
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"saved": len(keys)})
	}
}

// groupKeys — GET /groups/{groupID}/keys?since_version=: пропущенные wrapped_GK
// для устройства из токена (догон после офлайна / нового устройства).
// Членство не проверяется намеренно: выдаются только обёртки, адресованные
// этому устройству, — исключённый читает старые версии для истории
// (crypto-protocol §4.2: окно апелляции), новых версий у него нет.
func groupKeys(д группыDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var since int64
		if v := r.URL.Query().Get("since_version"); v != "" {
			since, _ = strconv.ParseInt(v, 10, 32)
		}
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())
		keys, err := д.хранилище.ListGroupKeysForDevice(r.Context(), groupID, id.DeviceID, int32(since))
		if err != nil {
			log.Printf("groupKeys: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// current_version — максимум по группе (может быть больше версий, выданных
		// этому устройству): новому устройству админа она нужна для ротации current+1.
		current, err := д.хранилище.CurrentGKVersion(r.Context(), groupID)
		if err != nil {
			log.Printf("groupKeys: current version: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		b64 := base64.RawURLEncoding
		type item struct {
			GKVersion          int32  `json:"gk_version"`
			SenderEphemeralPub string `json:"sender_ephemeral_pub"`
			Wrapped            string `json:"wrapped"`
		}
		out := make([]item, 0, len(keys))
		for _, k := range keys {
			out = append(out, item{k.GKVersion, b64.EncodeToString(k.SenderEphemeralPub), b64.EncodeToString(k.Wrapped)})
		}
		// escrow_epoch — эпоха, на которую завёрнут блоб последней версии (ADR-0017 §3).
		// Без неё клиент не может заметить устаревание сам и зависел бы только от события,
		// которое можно пропустить, будучи офлайн. Поле аддитивное: старый клиент его не
		// читает. Пусто — ротаций не было либо они были до введения правила.
		последняя, err := д.хранилище.LatestGroupRotation(r.Context(), groupID)
		if err != nil {
			log.Printf("groupKeys: последняя ротация: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"keys":            out,
			"current_version": current,
			"escrow_epoch":    последняя.EscrowEpoch,
		})
	}
}

// ── Ротация: причины и порог (ADR-0017 §7) ──────────────────────────────────

const (
	причинаЭпоха       = "epoch"
	причинаСчётчик     = "periodic"
	причинаВход        = "member_join"
	причинаВыход       = "member_leave"
	причинаКомпромисса = "compromise"

	// Подстраховка на случай ошибки в проверке причины. Упереться в неё законной
	// ротацией по счётчику означало бы 11 сообщений в секунду непрерывно.
	порогРотации = 15 * time.Minute
)

func допустимаяПричина(r string) bool {
	switch r {
	case причинаЭпоха, причинаСчётчик, причинаВход, причинаВыход, причинаКомпромисса:
		return true
	}
	return false
}

// несрочная — та, задержка которой ничего не открывает постороннему. Ротации по
// составу и по компрометации срочные: там пятнадцать минут означают пятнадцать минут
// чтения переписки тем, кого уже исключили.
func несрочная(r string) bool {
	return r == причинаЭпоха || r == причинаСчётчик
}

// причинаПодтверждена — сверка названной причины с тем, что видит сервер.
//
// Смысл не в недоверии к клиенту, а в том, что ротировать теперь может каждый участник:
// причина — это то, по чему сервер решает, действует ли порог частоты и нужна ли ротация
// вообще. Непроверяемая причина сделала бы обе проверки украшением.
//
// `periodic` подтверждается любым сообщением после прошлой ротации, а не порогом в
// 10 000: счётчик ведёт клиент, и сервер не знает, на каком он числе. Требовать здесь
// ровно порог значило бы отвергать законную ротацию из-за расхождения в единицу.
// Достаточно, что группа вообще жила: ротация в мёртвой группе по причине «много
// сообщений» — заведомая неправда.
func причинаПодтверждена(reason string, доводы store.RotationEvidence) bool {
	switch reason {
	case причинаЭпоха:
		return true // эпоха проверена выше, отдельно и точнее
	case причинаСчётчик:
		return доводы.MessagesSince > 0
	case причинаВход:
		return доводы.Joined
	case причинаВыход:
		return доводы.Left
	case причинаКомпромисса:
		return доводы.DeviceRevoked
	}
	return false
}
