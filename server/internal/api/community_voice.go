// Звуковой чат в сообществе (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ7).
//
// Только внутри сообщества; создают и настраивают владелец и админы. Войти может тот, кто видит сообщество; выгнанный
// не входит до конца разговора; сверх предела — нет. Говорят все, кто вошёл: токен LiveKit — с правом публиковать.
// Ведущий — одно место: занять может вошедший, если свободно и ведущий разрешён; владелец и админы назначают и снимают.
// Звук без сквозного шифрования, записи нет.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

type voiceDeps struct {
	st      ThreadEntities
	cv      store.CommunityVoice
	livekit func() LiveKitSettings
	n       *Notifier
}

// RegisterCommunityVoice — звуковые чаты сообществ.
func RegisterCommunityVoice(mux *http.ServeMux, st ThreadEntities, cv store.CommunityVoice, livekit func() LiveKitSettings, n *Notifier, requireDevice Middleware) {
	d := voiceDeps{st: st, cv: cv, livekit: livekit, n: n}
	mux.HandleFunc("POST /api/v1/communities/{entityID}/voice-rooms", requireDevice(createCommunityVoice(d)))
	mux.HandleFunc("GET /api/v1/communities/{entityID}/voice-rooms", requireDevice(listCommunityVoice(d)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/enter", requireDevice(enterVoice(d)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/leave", requireDevice(leaveVoice(d)))
	mux.HandleFunc("PUT /api/v1/voice-rooms/{roomID}/settings", requireDevice(voiceSettings(d)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/host", requireDevice(voiceHost(d)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/kick", requireDevice(voiceKick(d)))
}

func voiceJSON(c store.VoiceChat) map[string]any {
	present := c.Present
	if present == nil {
		present = []string{}
	}
	return map[string]any{
		"room_id": c.RoomID, "title": c.Title, "community_id": c.CommunityID, "group_id": c.GroupID,
		"allow_mute": c.AllowMute, "host_allowed": c.HostAllowed, "host_can_kick": c.HostCanKick,
		"max_participants": c.MaxParticipants, "host_id": c.HostID, "present": present,
	}
}

// communityStaff — владелец и админы сообщества (§1.8, 2).
func communityStaff(ctx context.Context, st ThreadEntities, communityID, userID string) (bool, error) {
	c, err := st.GetCommunity(ctx, communityID)
	if err != nil {
		return false, err
	}
	if c.OwnerID == userID {
		return true, nil
	}
	admins, err := st.CommunityAdmins(ctx, communityID)
	return listHas(admins, userID), err
}

func createCommunityVoice(d voiceDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Title   string `json:"title"`
			GroupID string `json:"group_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || strings.TrimSpace(req.Title) == "" || len(req.Title) > 200 {
			writeErr(w, http.StatusBadRequest, "bad_title", "нужен title, до 200 байт")
			return
		}
		me, _ := auth.FromContext(r.Context())
		communityID := r.PathValue("entityID")
		ok, err := communityStaff(r.Context(), d.st, communityID, me.UserID)
		if errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !ok {
			writeErr(w, http.StatusForbidden, "forbidden", "звуковой чат создают владелец и админы сообщества")
			return
		}
		id, err := d.cv.Create(r.Context(), communityID, strings.TrimSpace(req.Title), me.UserID, req.GroupID)
		if err != nil {
			log.Printf("createCommunityVoice: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"room_id": id})
	}
}

func listCommunityVoice(d voiceDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		me, _ := auth.FromContext(r.Context())
		communityID := r.PathValue("entityID")
		if sees, err := seesPage(r.Context(), d.st, "community", communityID, me.UserID); err != nil || !sees {
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		}
		list, err := d.cv.List(r.Context(), communityID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(list))
		for _, c := range list {
			out = append(out, voiceJSON(c))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"rooms": out})
	}
}

// voiceOf — звуковой чат и вправе ли просящий распоряжаться им (владелец и админы сообщества).
func voiceOf(w http.ResponseWriter, r *http.Request, d voiceDeps) (store.VoiceChat, bool, bool) {
	c, err := d.cv.Get(r.Context(), r.PathValue("roomID"))
	if errors.Is(err, store.ErrVoiceRoomNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "звуковой чат не найден")
		return c, false, false
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return c, false, false
	}
	me, _ := auth.FromContext(r.Context())
	staff, _ := communityStaff(r.Context(), d.st, c.CommunityID, me.UserID)
	return c, staff, true
}

// enterVoice — POST /voice-rooms/{id}/enter: токен комнаты; видящий сообщество, не выгнанный, в пределах числа.
func enterVoice(d voiceDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, _, ok := voiceOf(w, r, d)
		if !ok {
			return
		}
		me, _ := auth.FromContext(r.Context())
		if sees, err := seesPage(r.Context(), d.st, "community", c.CommunityID, me.UserID); err != nil || !sees {
			writeErr(w, http.StatusNotFound, "not_found", "звуковой чат не найден")
			return
		}
		settings := d.livekit()
		if settings.Issuer == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
			return
		}
		if err := d.cv.Enter(r.Context(), c.RoomID, me.UserID); errors.Is(err, store.ErrKicked) {
			writeErr(w, http.StatusForbidden, "kicked", "вас выгнали из этого разговора")
			return
		} else if errors.Is(err, store.ErrRoomFull) {
			writeErr(w, http.StatusConflict, "room_full", "звуковой чат полон")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		room := "voice-" + c.RoomID
		// Говорят все, кто вошёл (§1.5, 2): право публиковать — у каждого.
		token, err := settings.Issuer.Token(room, callIdentity(me), true, 10*time.Minute, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		fresh, _ := d.cv.Get(r.Context(), c.RoomID)
		answer := voiceJSON(fresh)
		answer["room"], answer["url"], answer["token"] = room, settings.URL, token
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(answer)
	}
}

func leaveVoice(d voiceDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, _, ok := voiceOf(w, r, d)
		if !ok {
			return
		}
		me, _ := auth.FromContext(r.Context())
		if err := d.cv.Leave(r.Context(), c.RoomID, me.UserID); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func voiceSettings(d voiceDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, staff, ok := voiceOf(w, r, d)
		if !ok {
			return
		}
		if !staff {
			writeErr(w, http.StatusForbidden, "forbidden", "настраивают владелец и админы сообщества")
			return
		}
		var req struct {
			AllowMute       *bool `json:"allow_mute"`
			HostAllowed     *bool `json:"host_allowed"`
			HostCanKick     *bool `json:"host_can_kick"`
			MaxParticipants *int  `json:"max_participants"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 2<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.AllowMute != nil {
			c.AllowMute = *req.AllowMute
		}
		if req.HostAllowed != nil {
			c.HostAllowed = *req.HostAllowed
		}
		if req.HostCanKick != nil {
			c.HostCanKick = *req.HostCanKick
		}
		if req.MaxParticipants != nil {
			if *req.MaxParticipants < 2 || *req.MaxParticipants > 1000 {
				writeErr(w, http.StatusBadRequest, "bad_max", "участников — от 2 до 1000")
				return
			}
			c.MaxParticipants = *req.MaxParticipants
		}
		if err := d.cv.Settings(r.Context(), c.RoomID, c.AllowMute, c.HostAllowed, c.HostCanKick, c.MaxParticipants); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		fresh, _ := d.cv.Get(r.Context(), c.RoomID)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(voiceJSON(fresh))
	}
}

// voiceHost — POST /voice-rooms/{id}/host {user_id}: вошедший занимает свободное место (своим номером); владелец и
// админы назначают любого вошедшего или снимают (пустой user_id).
func voiceHost(d voiceDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, staff, ok := voiceOf(w, r, d)
		if !ok {
			return
		}
		var req struct {
			UserID string `json:"user_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id (пусто — снять)")
			return
		}
		me, _ := auth.FromContext(r.Context())
		if !c.HostAllowed && req.UserID != "" {
			writeErr(w, http.StatusConflict, "host_off", "ведущий в этом чате не разрешён")
			return
		}
		switch {
		case staff:
			// назначить любого вошедшего или снять
		case req.UserID == me.UserID && c.HostID == "":
			// занять свободное место самому
		case req.UserID == "" && c.HostID == me.UserID:
			// уступить место
		default:
			writeErr(w, http.StatusForbidden, "forbidden", "место занято: снимают и назначают владелец и админы")
			return
		}
		if req.UserID != "" && !listHas(c.Present, req.UserID) {
			writeErr(w, http.StatusConflict, "not_present", "ведущий — из тех, кто в чате")
			return
		}
		if err := d.cv.SetHost(r.Context(), c.RoomID, req.UserID); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if d.n != nil {
			d.n.Users(r.Context(), c.Present, "voice.host", map[string]any{"room_id": c.RoomID, "host_id": req.UserID})
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// voiceKick — POST /voice-rooms/{id}/kick {user_id}: ведущий, если разрешено; владелец и админы — всегда.
func voiceKick(d voiceDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, staff, ok := voiceOf(w, r, d)
		if !ok {
			return
		}
		var req struct {
			UserID string `json:"user_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req); err != nil || req.UserID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
			return
		}
		me, _ := auth.FromContext(r.Context())
		hostMay := c.HostID == me.UserID && c.HostCanKick
		if !staff && !hostMay {
			writeErr(w, http.StatusForbidden, "forbidden", "выгоняет ведущий, если разрешено, или владелец и админы")
			return
		}
		if err := d.cv.Kick(r.Context(), c.RoomID, req.UserID); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Из комнаты LiveKit — все устройства выгнанного.
		if rooms := d.livekit().Rooms; rooms != nil {
			if parts, err := rooms.ListParticipants(r.Context(), "voice-"+c.RoomID); err == nil {
				for _, p := range parts {
					if strings.HasPrefix(p.Identity, req.UserID+":") {
						_ = rooms.RemoveParticipant(r.Context(), "voice-"+c.RoomID, p.Identity)
					}
				}
			}
		}
		if d.n != nil {
			d.n.Users(r.Context(), []string{req.UserID}, "voice.kicked", map[string]any{"room_id": c.RoomID})
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
