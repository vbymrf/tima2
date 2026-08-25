// Групповые звонки (Р5). Здесь только сигналинг и состояние на нашей стороне:
// медиа и его качество — зона LiveKit (ADR-0006 Поправка-1), своей лестницы
// деградации и своих таймеров у продукта нет.
package api

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// startGroupCall — POST /calls/group {group_id, kind}.
//
// Приглашаются все участники группы. Инициатор — такой же участник: в группе он не
// медиа-хаб, и его обрыв не роняет звонок для остальных.
func startGroupCall(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if д.выдача() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы (LIVEKIT_API_KEY/SECRET)")
			return
		}
		var req struct {
			GroupID string `json:"group_id"`
			Kind    string `json:"kind"` // audio|video
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil || !uuidRe.MatchString(req.GroupID) {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен group_id (UUID)")
			return
		}
		if req.Kind != "audio" && req.Kind != "video" {
			req.Kind = "audio"
		}
		id, _ := auth.FromContext(r.Context())

		members, err := д.хранилище.ListGroupMembers(r.Context(), req.GroupID)
		if err != nil {
			log.Printf("startGroupCall members: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		userIDs := make([]string, 0, len(members))
		inGroup := false
		for _, m := range members {
			userIDs = append(userIDs, m.UserID)
			if m.UserID == id.UserID {
				inGroup = true
			}
		}
		if !inGroup {
			writeErr(w, http.StatusForbidden, "not_member", "звонить в группу может только её участник")
			return
		}

		room := "call-" + newUUID()
		callID, err := д.хранилище.CreateGroupCall(r.Context(), room, req.Kind, req.GroupID, id.UserID, userIDs)
		if err != nil {
			log.Printf("startGroupCall: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		token, err := д.выдача().Token(room, личностьЗвонка(id), true, callTokenTTL, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		for _, uid := range userIDs {
			if uid == id.UserID {
				continue
			}
			if devices, err := д.хранилище.ListDevices(r.Context(), uid); err == nil {
				for _, d := range devices {
					д.уведомитель.Device(r.Context(), d.DeviceID, "call.incoming", map[string]any{
						"call_id": callID, "room": room, "kind": req.Kind,
						"from": id.UserID, "group_id": req.GroupID, "type": "group",
					})
				}
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			// url — то же имя, что и у звонка один на один. Разнобой в именах уже стоил
			// бы клиенту пустого адреса и молчаливого «не подключается».
			"call_id": callID, "room": room, "token": token,
			"url": д.адресLiveKit(), "livekit_url": д.адресLiveKit(), "type": "group",
		})
	}
}

// joinCall — POST /calls/{callID}/join: токен для входа в активный звонок.
//
// Одним эндпоинтом закрываются оба случая: тот, кто не ответил сразу, и тот, кто
// выпал и возвращается. Разделять их незачем — право на вход у приглашённого одно
// и то же и действует, пока звонок активен.
//
// Для звонка один на один повторного входа НЕТ: обрыв завершает его для обоих, и
// вернуться можно только новым звонком.
func joinCall(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if д.выдача() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
			return
		}
		callID := r.PathValue("callID")
		if !uuidRe.MatchString(callID) {
			writeErr(w, http.StatusBadRequest, "bad_call_id", "нужен call_id (UUID)")
			return
		}
		id, _ := auth.FromContext(r.Context())
		c, err := д.хранилище.CallForJoinByID(r.Context(), callID, id.UserID)
		if errors.Is(err, store.ErrNotInvited) {
			writeErr(w, http.StatusForbidden, "not_invited", "вас не приглашали в этот звонок")
			return
		} else if err != nil {
			log.Printf("joinCall: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if c.Type != "group" {
			writeErr(w, http.StatusConflict, "no_rejoin",
				"в звонок один на один повторный вход не предусмотрен — позвоните заново")
			return
		}
		if c.State == "ended" || c.State == "missed" {
			writeErr(w, http.StatusGone, "call_ended", "звонок уже завершён")
			return
		}
		token, err := д.выдача().Token(c.Room, личностьЗвонка(id), true, callTokenTTL, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"call_id": callID, "room": c.Room, "token": token,
			"url": д.адресLiveKit(), "livekit_url": д.адресLiveKit(), "kind": c.Kind,
		})
	}
}

// livekitWebhook — POST /livekit/webhook: состав комнаты со слов самого LiveKit.
//
// Источник правды о том, кто в комнате, — SFU. Своего учёта «жив или нет» бэкенд не
// ведёт и таймеры LiveKit не дублирует: он сам решает, когда участник ушёл, с учётом
// реконнектов, а параллельный учёт неизбежно разошёлся бы с ним.
func livekitWebhook(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err != nil {
			writeErr(w, http.StatusBadRequest, "bad_body", "не прочитано тело")
			return
		}
		if !подписьВебхукаВерна(д, r.Header.Get("Authorization"), body) {
			// Не «неверная подпись», а глухой отказ: эндпоинт открыт наружу, и
			// подробности помогали бы подбирать.
			writeErr(w, http.StatusUnauthorized, "unauthorized", "подпись вебхука не прошла проверку")
			return
		}
		var ev struct {
			Event string `json:"event"`
			Room  struct {
				Name string `json:"name"`
			} `json:"room"`
			Participant struct {
				Identity string `json:"identity"`
			} `json:"participant"`
			CreatedAt int64 `json:"createdAt"`
		}
		if err := json.Unmarshal(body, &ev); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "не разобрано событие")
			return
		}
		callID, callType, err := д.хранилище.CallIDByRoom(r.Context(), ev.Room.Name)
		if err != nil {
			// Комната не наша или звонок уже почищен — не ошибка: отвечаем 200,
			// иначе LiveKit будет ретраить событие, которое некуда применить.
			w.WriteHeader(http.StatusOK)
			return
		}
		// identity = user_id:device_id (см. callIdentity)
		userID, _, _ := strings.Cut(ev.Participant.Identity, ":")
		at := time.Now()
		if ev.CreatedAt > 0 {
			at = time.Unix(ev.CreatedAt, 0).UTC()
		}

		switch ev.Event {
		case "participant_joined":
			if userID != "" {
				_ = д.хранилище.SetParticipantState(r.Context(), callID, userID, store.PartJoined, at)
				_ = д.хранилище.SetCallState(r.Context(), callID, "answered")
			}
		case "participant_left":
			if userID != "" {
				_ = д.хранилище.SetParticipantState(r.Context(), callID, userID, store.PartLeft, at)
			}
			// Один на один: уход любой стороны завершает звонок для обоих — держать
			// второго в пустой комнате незачем. В группе остальные продолжают.
			if callType == "direct" {
				_ = д.хранилище.SetCallState(r.Context(), callID, "ended")
			}
			уведомитьУчастников(д, r, callID, "call.participant_left", map[string]any{
				"call_id": callID, "user_id": userID,
			})
		case "room_finished":
			_ = д.хранилище.SetCallState(r.Context(), callID, "ended")
			уведомитьУчастников(д, r, callID, "call.state", map[string]any{
				"call_id": callID, "state": "ended",
			})
		}
		w.WriteHeader(http.StatusOK)
	}
}

// notifyCallParticipants рассылает событие устройствам всех приглашённых.
func уведомитьУчастников(д звонкиDeps, r *http.Request, callID, event string, payload map[string]any) {
	parts, err := д.хранилище.CallParticipants(r.Context(), callID)
	if err != nil {
		return
	}
	for uid := range parts {
		devices, err := д.хранилище.ListDevices(r.Context(), uid)
		if err != nil {
			continue
		}
		for _, d := range devices {
			д.уведомитель.Device(r.Context(), d.DeviceID, event, payload)
		}
	}
}

// verifyLiveKitWebhook проверяет подпись вебхука.
//
// LiveKit подписывает тело так: JWT на API-секрете, в claim `sha256` — хэш тела в
// base64. Проверяем и подпись токена, и что хэш совпал с реально пришедшим телом:
// без второй проверки перехваченный токен годился бы для любого содержимого.
func подписьВебхукаВерна(д звонкиDeps, authHeader string, body []byte) bool {
	if д.выдача() == nil || authHeader == "" {
		return false
	}
	raw := strings.TrimPrefix(authHeader, "Bearer ")
	var c struct {
		Sha256 string `json:"sha256"`
		jwt.RegisteredClaims
	}
	tok, err := jwt.ParseWithClaims(raw, &c, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("неожиданный алгоритм подписи")
		}
		return []byte(д.выдача().APISecret), nil
	})
	if err != nil || !tok.Valid || c.Issuer != д.выдача().APIKey {
		return false
	}
	want, err := base64.StdEncoding.DecodeString(c.Sha256)
	if err != nil {
		return false
	}
	got := sha256.Sum256(body)
	return subtle.ConstantTimeCompare(got[:], want) == 1
}
