// Звонки 1:1 (calls-livekit.md §3): бэкенд создаёт комнату и выдаёт LiveKit-токены,
// уведомляет собеседника (call.incoming). Медиа идёт через LiveKit, не через нас.
// Живой звонок требует развёрнутого LiveKit и реальных устройств — токен выдаётся
// и без сервера LiveKit, но подключение по нему нужно к работающему SFU.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

const callTokenTTL = 2 * time.Minute // §3: короткий TTL на подключение

// личностьЗвонка — как участник называется в LiveKit: пара «человек:устройство».
func личностьЗвонка(id auth.Identity) string { return id.UserID + ":" + id.DeviceID }

// startCall — POST /calls {peer_id, kind}: комната + токен инициатора, звонок собеседнику.
func startCall(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if д.выдача() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы (LIVEKIT_API_KEY/SECRET)")
			return
		}
		var req struct {
			PeerID string `json:"peer_id"`
			Kind   string `json:"kind"` // audio|video
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil || req.PeerID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен peer_id")
			return
		}
		if req.Kind != "audio" && req.Kind != "video" {
			req.Kind = "audio"
		}
		id, _ := auth.FromContext(r.Context())
		// room уникальна на звонок; генерируем как UUID (переиспользуем newUUID)
		room := "call-" + newUUID()
		callID, err := д.хранилище.CreateCall(r.Context(), store.Call{
			Room: room, Kind: req.Kind, InitiatorID: id.UserID, PeerID: req.PeerID,
		})
		if err != nil {
			log.Printf("startCall: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		token, err := д.выдача().Token(room, личностьЗвонка(id), true, callTokenTTL, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		// call.incoming устройствам собеседника (VoIP push — с провайдером позже)
		if devices, err := д.хранилище.ListDevices(r.Context(), req.PeerID); err == nil {
			for _, d := range devices {
				д.уведомитель.Device(r.Context(), d.DeviceID, "call.incoming", map[string]any{
					"call_id": callID, "room": room, "kind": req.Kind, "from": id.UserID,
				})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"call_id": callID, "room": room, "url": д.адресLiveKit(), "token": token,
		})
	}
}

// answerCall — POST /calls/{callID}/answer: токен для собеседника, состояние answered.
func answerCall(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if д.выдача() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
			return
		}
		callID := r.PathValue("callID")
		call, err := д.хранилище.GetCall(r.Context(), callID)
		if errors.Is(err, store.ErrCallNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "звонок не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if call.PeerID != id.UserID {
			writeErr(w, http.StatusForbidden, "not_callee", "ответить может только вызываемый")
			return
		}
		token, err := д.выдача().Token(call.Room, личностьЗвонка(id), true, callTokenTTL, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		_ = д.хранилище.SetCallState(r.Context(), callID, "answered")
		if devices, err := д.хранилище.ListDevices(r.Context(), call.InitiatorID); err == nil {
			for _, d := range devices {
				д.уведомитель.Device(r.Context(), d.DeviceID, "call.state", map[string]any{"call_id": callID, "state": "answered"})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"room": call.Room, "url": д.адресLiveKit(), "token": token})
	}
}

// ── Аудио-чаты (постоянные голосовые комнаты) ──

func createVoiceRoom(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil || req.Title == "" {
			writeErr(w, http.StatusBadRequest, "bad_title", "нужен title")
			return
		}
		id, _ := auth.FromContext(r.Context())
		roomID, err := д.хранилище.CreateVoiceRoom(r.Context(), req.Title, id.UserID)
		if err != nil {
			log.Printf("createVoiceRoom: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"room_id": roomID})
	}
}

func listVoiceRooms(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rooms, err := д.хранилище.ListVoiceRooms(r.Context(), 50)
		if err != nil {
			log.Printf("listVoiceRooms: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(rooms))
		for _, v := range rooms {
			out = append(out, map[string]any{"room_id": v.RoomID, "title": v.Title, "owner_id": v.OwnerID})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"rooms": out})
	}
}

// joinVoiceRoom — POST /voice-rooms/{id}/join: LiveKit-токен комнаты. MVP: все спикеры
// (canPublish=true); роли спикер/слушатель — следующая итерация.
func joinVoiceRoom(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if д.выдача() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
			return
		}
		roomID := r.PathValue("roomID")
		vr, err := д.хранилище.GetVoiceRoom(r.Context(), roomID)
		if errors.Is(err, store.ErrVoiceRoomNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "аудио-чат не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		room := "voice-" + vr.RoomID
		// Роль решает canPublish: спикер говорит, слушатель только слушает
		speaker, err := д.хранилище.IsSpeaker(r.Context(), vr.RoomID, vr.OwnerID, id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		token, err := д.выдача().Token(room, личностьЗвонка(id), speaker, 10*time.Minute, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		role := "listener"
		if speaker {
			role = "speaker"
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"room": room, "url": д.адресLiveKit(), "token": token, "title": vr.Title,
			"role": role, "is_owner": vr.OwnerID == id.UserID,
		})
	}
}

// raiseHand — POST /voice-rooms/{id}/hand: слушатель просит слово → владельцу voice.hand.
func raiseHand(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		roomID := r.PathValue("roomID")
		vr, err := д.хранилище.GetVoiceRoom(r.Context(), roomID)
		if errors.Is(err, store.ErrVoiceRoomNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "аудио-чат не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if devices, err := д.хранилище.ListDevices(r.Context(), vr.OwnerID); err == nil {
			for _, d := range devices {
				д.уведомитель.Device(r.Context(), d.DeviceID, "voice.hand", map[string]any{"room_id": roomID, "user_id": id.UserID})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
	}
}

// setSpeaker — POST /voice-rooms/{id}/grant|revoke {user_id}: владелец даёт/забирает слово.
func датьСлово(д звонкиDeps, w http.ResponseWriter, r *http.Request, grant bool) {
	roomID := r.PathValue("roomID")
	vr, err := д.хранилище.GetVoiceRoom(r.Context(), roomID)
	if errors.Is(err, store.ErrVoiceRoomNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "аудио-чат не найден")
		return
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	id, _ := auth.FromContext(r.Context())
	if vr.OwnerID != id.UserID {
		writeErr(w, http.StatusForbidden, "not_owner", "слово выдаёт владелец аудио-чата")
		return
	}
	var req struct {
		UserID string `json:"user_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil || req.UserID == "" {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
		return
	}
	if grant {
		err = д.хранилище.AddSpeaker(r.Context(), roomID, req.UserID)
	} else {
		err = д.хранилище.RemoveSpeaker(r.Context(), roomID, req.UserID)
	}
	if err != nil {
		log.Printf("setSpeaker: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// Уведомляем адресата: клиент перезайдёт за токеном с новой ролью
	event := "voice.granted"
	if !grant {
		event = "voice.revoked"
	}
	if devices, err := д.хранилище.ListDevices(r.Context(), req.UserID); err == nil {
		for _, d := range devices {
			д.уведомитель.Device(r.Context(), d.DeviceID, event, map[string]any{"room_id": roomID})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
}

func grantSpeaker(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) { датьСлово(д, w, r, true) }
}

func revokeSpeaker(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) { датьСлово(д, w, r, false) }
}

// endCall — POST /calls/{callID}/end: завершение любым участником.
func endCall(д звонкиDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		callID := r.PathValue("callID")
		call, err := д.хранилище.GetCall(r.Context(), callID)
		if errors.Is(err, store.ErrCallNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "звонок не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if call.InitiatorID != id.UserID && call.PeerID != id.UserID {
			writeErr(w, http.StatusForbidden, "not_participant", "завершить может участник звонка")
			return
		}
		state := "ended"
		if call.State == "ringing" {
			state = "missed"
		}
		// Закрываем комнату НА САМОМ ДЕЛЕ. Без этого «завершить» меняло состояние у нас
		// и рассылало уведомление, а комната жила до empty_timeout: клиент, который
		// уведомление не получил или проигнорировал, продолжал публиковать звук.
		if err := д.комнаты().DeleteRoom(r.Context(), call.Room); err != nil {
			log.Printf("endCall: комната %s не закрылась: %v", call.Room, err)
		}
		_ = д.хранилище.SetCallState(r.Context(), callID, state)
		other := call.PeerID
		if id.UserID == call.PeerID {
			other = call.InitiatorID
		}
		if devices, err := д.хранилище.ListDevices(r.Context(), other); err == nil {
			for _, d := range devices {
				д.уведомитель.Device(r.Context(), d.DeviceID, "call.state", map[string]any{"call_id": callID, "state": state})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"call_id": callID, "state": state})
	}
}
