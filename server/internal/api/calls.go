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

func (s *Server) callIdentity(id auth.Identity) string { return id.UserID + ":" + id.DeviceID }

// startCall — POST /calls {peer_id, kind}: комната + токен инициатора, звонок собеседнику.
func (s *Server) startCall(w http.ResponseWriter, r *http.Request) {
	if s.Calls == nil {
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
	callID, err := s.Store.CreateCall(r.Context(), store.Call{
		Room: room, Kind: req.Kind, InitiatorID: id.UserID, PeerID: req.PeerID,
	})
	if err != nil {
		log.Printf("startCall: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	token, err := s.Calls.Token(room, s.callIdentity(id), true, callTokenTTL, time.Now())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
		return
	}
	// call.incoming устройствам собеседника (VoIP push — с провайдером позже)
	if devices, err := s.Store.ListDevices(r.Context(), req.PeerID); err == nil {
		for _, d := range devices {
			s.notify(r.Context(), d.DeviceID, "call.incoming", map[string]any{
				"call_id": callID, "room": room, "kind": req.Kind, "from": id.UserID,
			})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"call_id": callID, "room": room, "url": s.LiveKitURL, "token": token,
	})
}

// answerCall — POST /calls/{callID}/answer: токен для собеседника, состояние answered.
func (s *Server) answerCall(w http.ResponseWriter, r *http.Request) {
	if s.Calls == nil {
		writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
		return
	}
	callID := r.PathValue("callID")
	call, err := s.Store.GetCall(r.Context(), callID)
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
	token, err := s.Calls.Token(call.Room, s.callIdentity(id), true, callTokenTTL, time.Now())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
		return
	}
	_ = s.Store.SetCallState(r.Context(), callID, "answered")
	if devices, err := s.Store.ListDevices(r.Context(), call.InitiatorID); err == nil {
		for _, d := range devices {
			s.notify(r.Context(), d.DeviceID, "call.state", map[string]any{"call_id": callID, "state": "answered"})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"room": call.Room, "url": s.LiveKitURL, "token": token})
}

// ── Аудио-чаты (постоянные голосовые комнаты) ──

func (s *Server) createVoiceRoom(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil || req.Title == "" {
		writeErr(w, http.StatusBadRequest, "bad_title", "нужен title")
		return
	}
	id, _ := auth.FromContext(r.Context())
	roomID, err := s.Store.CreateVoiceRoom(r.Context(), req.Title, id.UserID)
	if err != nil {
		log.Printf("createVoiceRoom: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"room_id": roomID})
}

func (s *Server) listVoiceRooms(w http.ResponseWriter, r *http.Request) {
	rooms, err := s.Store.ListVoiceRooms(r.Context(), 50)
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

// joinVoiceRoom — POST /voice-rooms/{id}/join: LiveKit-токен комнаты. MVP: все спикеры
// (canPublish=true); роли спикер/слушатель — следующая итерация.
func (s *Server) joinVoiceRoom(w http.ResponseWriter, r *http.Request) {
	if s.Calls == nil {
		writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
		return
	}
	roomID := r.PathValue("roomID")
	vr, err := s.Store.GetVoiceRoom(r.Context(), roomID)
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
	speaker, err := s.Store.IsSpeaker(r.Context(), vr.RoomID, vr.OwnerID, id.UserID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	token, err := s.Calls.Token(room, s.callIdentity(id), speaker, 10*time.Minute, time.Now())
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
		"room": room, "url": s.LiveKitURL, "token": token, "title": vr.Title,
		"role": role, "is_owner": vr.OwnerID == id.UserID,
	})
}

// raiseHand — POST /voice-rooms/{id}/hand: слушатель просит слово → владельцу voice.hand.
func (s *Server) raiseHand(w http.ResponseWriter, r *http.Request) {
	roomID := r.PathValue("roomID")
	vr, err := s.Store.GetVoiceRoom(r.Context(), roomID)
	if errors.Is(err, store.ErrVoiceRoomNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "аудио-чат не найден")
		return
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	id, _ := auth.FromContext(r.Context())
	if devices, err := s.Store.ListDevices(r.Context(), vr.OwnerID); err == nil {
		for _, d := range devices {
			s.notify(r.Context(), d.DeviceID, "voice.hand", map[string]any{"room_id": roomID, "user_id": id.UserID})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
}

// setSpeaker — POST /voice-rooms/{id}/grant|revoke {user_id}: владелец даёт/забирает слово.
func (s *Server) setSpeaker(w http.ResponseWriter, r *http.Request, grant bool) {
	roomID := r.PathValue("roomID")
	vr, err := s.Store.GetVoiceRoom(r.Context(), roomID)
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
		err = s.Store.AddSpeaker(r.Context(), roomID, req.UserID)
	} else {
		err = s.Store.RemoveSpeaker(r.Context(), roomID, req.UserID)
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
	if devices, err := s.Store.ListDevices(r.Context(), req.UserID); err == nil {
		for _, d := range devices {
			s.notify(r.Context(), d.DeviceID, event, map[string]any{"room_id": roomID})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
}

func (s *Server) grantSpeaker(w http.ResponseWriter, r *http.Request)  { s.setSpeaker(w, r, true) }
func (s *Server) revokeSpeaker(w http.ResponseWriter, r *http.Request) { s.setSpeaker(w, r, false) }

// endCall — POST /calls/{callID}/end: завершение любым участником.
func (s *Server) endCall(w http.ResponseWriter, r *http.Request) {
	callID := r.PathValue("callID")
	call, err := s.Store.GetCall(r.Context(), callID)
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
	_ = s.Store.SetCallState(r.Context(), callID, state)
	other := call.PeerID
	if id.UserID == call.PeerID {
		other = call.InitiatorID
	}
	if devices, err := s.Store.ListDevices(r.Context(), other); err == nil {
		for _, d := range devices {
			s.notify(r.Context(), d.DeviceID, "call.state", map[string]any{"call_id": callID, "state": state})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"call_id": callID, "state": state})
}
