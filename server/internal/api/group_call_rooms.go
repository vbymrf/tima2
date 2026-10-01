package api

// Групповой звонок в личной группе (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ.md, ГЗ2; решения заказчика
// 2026-10-01).
//
// ── КАК ИДЁТ ЗВОНОК ─────────────────────────────────────────────────────────
//
//   1. POST /groups/{id}/call {ring, video, invited} — звонок в группе. Начинает тот, у
//      кого роль модератор и выше; модераторов нет — владелец (решение 14). Идёт уже —
//      ответ тот же, что на вход: повторный звонок идёт в тот же (решение 1).
//   2. ring = true — отмеченным изменение `ringing` в ленте звонков, как у личного; кто
//      не вошёл за срок вызова — `missed` (решение 12). ring = false — не звоним: о звонке
//      скажут приглашение в личном чате и полоса «Идёт звонок» в группе (решение 3а).
//   3. Войти может любой участник группы, пока мест меньше предела (решения 2, 15) —
//      POST /calls/{id}/join.
//   4. Команды создателя — POST /calls/{id}/control: выключить чужие микрофон и видео,
//      удалить из звонка, пауза, продолжить, остановить (решения 5, 6).
//
// Временная группа (`call_ttl_until`) живёт CALL_GROUP_TTL от последнего звонка: начало
// и конец звонка срок отодвигают, уборщик удаляет вышедшую (ГЗ1).

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// GroupCallRules — правила группового звонка, которые задаёт сервер (решение 4).
//
// Качество — по числу участников: до 4 — 720p, до 8 — 480p, больше — только голос.
// Предел — 25. Числа меняются переменными окружения между звонками, как потолок видео
// личного звонка (В5б).
type GroupCallRules struct {
	Max   int              `json:"max"`
	Video []GroupVideoTier `json:"video"`
	// TTL — срок временной группы от последнего звонка. Клиенту не отдаётся: он видит
	// готовый `call_ttl_until` у группы.
	TTL time.Duration `json:"-"`
}

// GroupVideoTier — «до UpTo участников включительно — видео не выше Height».
type GroupVideoTier struct {
	UpTo   int `json:"up_to"`
	Height int `json:"height"`
}

// DefaultGroupCallRules — решения заказчика 2026-10-01.
var DefaultGroupCallRules = GroupCallRules{
	Max:   25,
	Video: []GroupVideoTier{{UpTo: 4, Height: 720}, {UpTo: 8, Height: 480}},
	TTL:   12 * time.Hour,
}

// GroupCallRulesFromEnv — CALL_GROUP_MAX, CALL_GROUP_TTL (длительность Go: «12h»),
// CALL_GROUP_HD_UPTO / CALL_GROUP_HD_HEIGHT, CALL_GROUP_SD_UPTO / CALL_GROUP_SD_HEIGHT.
// Негодное значение — умолчание этого поля.
func GroupCallRulesFromEnv(get func(string) string) GroupCallRules {
	rules := DefaultGroupCallRules
	rules.Video = append([]GroupVideoTier(nil), DefaultGroupCallRules.Video...)
	pick := func(name string, into *int, least int) {
		if v, err := strconv.Atoi(get(name)); err == nil && v >= least {
			*into = v
		}
	}
	pick("CALL_GROUP_MAX", &rules.Max, 2)
	pick("CALL_GROUP_HD_UPTO", &rules.Video[0].UpTo, 1)
	pick("CALL_GROUP_HD_HEIGHT", &rules.Video[0].Height, 90)
	pick("CALL_GROUP_SD_UPTO", &rules.Video[1].UpTo, 1)
	pick("CALL_GROUP_SD_HEIGHT", &rules.Video[1].Height, 90)
	if d, err := time.ParseDuration(get("CALL_GROUP_TTL")); err == nil && d >= time.Minute {
		rules.TTL = d
	}
	return rules
}

func (r GroupCallRules) orDefault() GroupCallRules {
	if r.Max == 0 {
		return DefaultGroupCallRules
	}
	return r
}

// startRoomCall — POST /api/v1/groups/{groupID}/call {ring, video, invited}.
func startRoomCall(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.issuer() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы (LIVEKIT_API_KEY/SECRET)")
			return
		}
		var req struct {
			Ring    bool     `json:"ring"`
			Video   *bool    `json:"video"`
			Invited []string `json:"invited"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())
		g, role, ok := roomCallGroup(deps, w, r, groupID, id.UserID)
		if !ok {
			return
		}
		rules := deps.groupRules()

		// Звонок в группе уже идёт — входим в него, нового не заводим (решение 1).
		if live, err := deps.store.LiveGroupCall(r.Context(), groupID); err == nil {
			enterRoomCall(deps, w, r, live, id, http.StatusOK)
			return
		} else if !errors.Is(err, store.ErrCallNotFound) {
			log.Printf("startRoomCall live: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if roleRank[role] < rankModerator {
			writeErr(w, http.StatusForbidden, "group_call_forbidden", "групповой звонок начинает модератор и выше, а без них — владелец")
			return
		}

		members, err := deps.store.ListGroupMembers(r.Context(), groupID)
		if err != nil {
			log.Printf("startRoomCall members: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		inGroup := map[string]bool{}
		for _, m := range members {
			inGroup[m.UserID] = true
		}
		// Отмеченные — только из участников группы и без самого создателя. Пустой список —
		// все участники: так звонят во временной группе, где все и есть позванные.
		var invited []string
		seen := map[string]bool{id.UserID: true}
		wanted := req.Invited
		if len(wanted) == 0 {
			for _, m := range members {
				wanted = append(wanted, m.UserID)
			}
		}
		for _, u := range wanted {
			if inGroup[u] && !seen[u] {
				seen[u] = true
				invited = append(invited, u)
			}
		}
		if len(invited)+1 > rules.Max {
			writeErr(w, http.StatusBadRequest, "too_many_invited",
				"отмечено больше, чем мест в звонке: предел "+strconv.Itoa(rules.Max)+" вместе с вами")
			return
		}
		kind := "video"
		if req.Video != nil && !*req.Video {
			kind = "audio"
		}
		room := "call-" + newUUID()
		callID, err := deps.store.CreateRoomCall(r.Context(), room, kind, groupID, id.UserID, invited, req.Ring)
		if err != nil {
			log.Printf("startRoomCall: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if g.CallTTLUntil != nil {
			_ = deps.store.BumpCallGroupTTL(r.Context(), groupID, rules.TTL)
		}
		log.Printf("групповой звонок %s в группе %s: создатель %s, отмечено %d, звонить=%v, вид=%s",
			short(callID), short(groupID), short(id.UserID), len(invited), req.Ring, kind)
		if req.Ring {
			for _, u := range invited {
				deps.notifier.CallChange(r.Context(), u, callID, "ringing", "")
			}
			go closeRoomUnanswered(deps, callID)
		}
		notifyGroupCall(deps, r.Context(), members, groupID, callID, "live", id.UserID)
		call, err := deps.store.GetCall(r.Context(), callID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		enterRoomCall(deps, w, r, call, id, http.StatusCreated)
	}
}

// roomCallGroup — личная группа и роль в ней. Не участник — 404, как у всех ручек групп:
// личная группа для чужого неотличима от несуществующей.
func roomCallGroup(deps callsDeps, w http.ResponseWriter, r *http.Request, groupID, userID string) (store.Group, string, bool) {
	g, err := deps.store.GetGroup(r.Context(), groupID)
	if errors.Is(err, store.ErrGroupNotFound) {
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return g, "", false
	} else if err != nil {
		log.Printf("roomCallGroup %s: %v", groupID, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return g, "", false
	}
	role, err := deps.store.GroupRole(r.Context(), groupID, userID)
	if errors.Is(err, store.ErrNotMember) {
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return g, "", false
	} else if err != nil {
		log.Printf("roomCallGroup role %s: %v", groupID, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return g, "", false
	}
	if g.Kind != "private" {
		writeErr(w, http.StatusBadRequest, "not_private", "групповой звонок — в личной группе")
		return g, "", false
	}
	return g, role, true
}

// enterRoomCall — вход в групповой звонок: проверки места и удаления, токен, дверь.
func enterRoomCall(deps callsDeps, w http.ResponseWriter, r *http.Request, call store.Call, id auth.Identity, status int) {
	if call.State != "ringing" && call.State != "answered" {
		writeErr(w, http.StatusGone, "call_ended", "звонок уже завершён")
		return
	}
	if _, err := deps.store.GroupRole(r.Context(), call.GroupID, id.UserID); err != nil {
		writeErr(w, http.StatusForbidden, "not_invited", "войти может только участник группы")
		return
	}
	rules := deps.groupRules()
	if err := deps.store.JoinGroupCall(r.Context(), call.CallID, id.UserID, rules.Max); errors.Is(err, store.ErrRemovedFromCall) {
		writeErr(w, http.StatusForbidden, "removed_from_call", "создатель удалил вас из этого звонка")
		return
	} else if errors.Is(err, store.ErrCallFull) {
		writeErr(w, http.StatusConflict, "call_full", "в звонке нет мест: предел "+strconv.Itoa(rules.Max))
		return
	} else if err != nil {
		log.Printf("enterRoomCall: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// Запрет создателя действует и при перезаходе: что можно публиковать — в правах токена.
	micOff, videoOff := forbidsOf(deps, r.Context(), call.CallID, id.UserID)
	token, err := deps.issuer().TokenSources(call.Room, callIdentity(id), tokenSources(micOff, videoOff), callTokenTTL, time.Now())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
		return
	}
	// Позванный вошёл — остальные его устройства замолкают, как при ответе на личный.
	if id.UserID != call.InitiatorID {
		deps.notifier.CallChange(r.Context(), id.UserID, call.CallID, "answered", id.DeviceID)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	door := roomDoorJSON(deps, call, token)
	door["forbidden"] = map[string]any{"mic": micOff, "video": videoOff}
	_ = json.NewEncoder(w).Encode(door)
}

// forbidsOf — запрещены ли участнику микрофон и видео в этом звонке.
func forbidsOf(deps callsDeps, ctx context.Context, callID, userID string) (bool, bool) {
	parts, err := deps.store.GroupCallParticipants(ctx, callID)
	if err != nil {
		return false, false
	}
	for _, p := range parts {
		if p.UserID == userID {
			return p.MicForbidden, p.VideoForbidden
		}
	}
	return false, false
}

// tokenSources — что можно публиковать при запретах: `nil` — всё, пусто — ничего.
func tokenSources(micOff, videoOff bool) []string {
	if !micOff && !videoOff {
		return nil
	}
	out := []string{}
	if !micOff {
		out = append(out, "microphone")
	}
	if !videoOff {
		out = append(out, "camera")
	}
	return out
}

// applyForbids — запреты участника в живую комнату: на всех его устройствах.
func applyForbids(deps callsDeps, ctx context.Context, room, userID string, micOff, videoOff bool) error {
	rooms := deps.rooms()
	if rooms == nil {
		return nil
	}
	parts, err := rooms.ListParticipants(ctx, room)
	if err != nil {
		return err
	}
	var sources []string
	if !micOff {
		sources = append(sources, "MICROPHONE")
	}
	if !videoOff {
		sources = append(sources, "CAMERA")
	}
	if !micOff && !videoOff {
		sources = append(sources, "SCREEN_SHARE", "SCREEN_SHARE_AUDIO")
	}
	for _, p := range parts {
		if strings.HasPrefix(p.Identity, userID+":") {
			if err := rooms.SetPublishSources(ctx, room, p.Identity, sources); err != nil {
				return err
			}
		}
	}
	return nil
}

// roomDoorJSON — дверь группового звонка: те же поля, что у личного, и сверх них — группа,
// создатель, пауза и правила.
func roomDoorJSON(deps callsDeps, call store.Call, token string) map[string]any {
	return map[string]any{
		"call_id": call.CallID, "room": call.Room, "token": token,
		"url": deps.livekitURL(), "livekit_url": deps.livekitURL(), "kind": call.Kind,
		"video": deps.video(), "type": "group", "group_id": call.GroupID,
		"creator_id": call.InitiatorID, "paused": !call.PausedAt.IsZero(),
		"rules": deps.groupRules(),
	}
}

// roomCallState — GET /api/v1/groups/{groupID}/call: идёт ли звонок в группе.
//
// Для полосы «Идёт звонок — Присоединиться» над перепиской (решение 3а) и для журнала
// звонка: кто отмечен, кто в комнате, кто удалён.
func roomCallState(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID := r.PathValue("groupID")
		id, _ := auth.FromContext(r.Context())
		g, role, ok := roomCallGroup(deps, w, r, groupID, id.UserID)
		if !ok {
			return
		}
		out := map[string]any{
			"call": nil, "rules": deps.groupRules(), "my_role": role,
			// Может ли спрашивающий начать звонок — кнопке незачем повторять правило ролей.
			"can_start": roleRank[role] >= rankModerator,
		}
		if g.CallTTLUntil != nil {
			out["call_ttl_until"] = g.CallTTLUntil.UTC().Format(time.RFC3339)
		}
		live, err := deps.store.LiveGroupCall(r.Context(), groupID)
		if err == nil {
			parts, _ := deps.store.GroupCallParticipants(r.Context(), live.CallID)
			list := make([]map[string]any, 0, len(parts))
			for _, p := range parts {
				list = append(list, map[string]any{
					"user_id": p.UserID, "state": string(p.State), "invited": p.Invited, "removed": p.Removed,
					"mic_forbidden": p.MicForbidden, "video_forbidden": p.VideoForbidden,
				})
			}
			out["call"] = map[string]any{
				"call_id": live.CallID, "creator_id": live.InitiatorID, "kind": live.Kind,
				"started_at": live.CreatedAt.UTC().Format(time.RFC3339), "paused": !live.PausedAt.IsZero(),
				"participants": list,
			}
		} else if !errors.Is(err, store.ErrCallNotFound) {
			log.Printf("roomCallState: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	}
}

// controlRoomCall — POST /api/v1/calls/{callID}/control {action, user_id}: команды
// создателя (решения 5, 6). Остальным — 403: участник выключает только своё, и делает это
// у себя, без сервера.
//
//	invite               — позвать ещё участника группы (с «Звонить» — вызов ему);
//	mute_mic · mute_video — ЗАПРЕТИТЬ участнику микрофон или камеру: сервер перестаёт
//	                       принимать этот источник, пока создатель не разрешит (уточнение
//	                       заказчика 2026-10-01); при перезаходе запрет в правах токена;
//	allow_mic · allow_video — разрешить снова (включает участник сам);
//	remove               — удалить из звонка, не из группы (решение 17);
//	pause · resume       — пауза «висит»: все в комнате, звук и видео стоят;
//	stop                 — завершить звонок для всех.
func controlRoomCall(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		callID := r.PathValue("callID")
		var req struct {
			Action string `json:"action"`
			UserID string `json:"user_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен action")
			return
		}
		call, err := deps.store.GetCall(r.Context(), callID)
		if errors.Is(err, store.ErrCallNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "звонок не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if call.Type != "group" || call.GroupID == "" {
			writeErr(w, http.StatusBadRequest, "not_group", "команды — только у группового звонка")
			return
		}
		if call.InitiatorID != id.UserID {
			writeErr(w, http.StatusForbidden, "not_creator", "командует звонком его создатель")
			return
		}
		if call.State != "ringing" && call.State != "answered" {
			writeErr(w, http.StatusGone, "call_ended", "звонок уже завершён")
			return
		}
		ctx := r.Context()
		switch req.Action {
		case "invite":
			if req.UserID == "" || req.UserID == id.UserID {
				writeErr(w, http.StatusBadRequest, "bad_user", "нужен user_id участника, не свой")
				return
			}
			if _, err := deps.store.GroupRole(ctx, call.GroupID, req.UserID); err != nil {
				writeErr(w, http.StatusBadRequest, "not_member", "позвать можно только участника группы")
				return
			}
			if err := deps.store.InviteToCall(ctx, callID, req.UserID); err != nil {
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			if ring, _ := deps.store.CallRings(ctx, callID); ring {
				deps.notifier.CallChange(ctx, req.UserID, callID, "ringing", "")
				go missedIfNotJoined(deps, callID, req.UserID)
			}
			log.Printf("групповой звонок %s: позван ещё %s", short(callID), short(req.UserID))
		case "mute_mic", "mute_video", "allow_mic", "allow_video":
			if req.UserID == "" || req.UserID == id.UserID {
				writeErr(w, http.StatusBadRequest, "bad_user", "нужен user_id участника, не свой")
				return
			}
			what := "mic"
			if strings.HasSuffix(req.Action, "video") {
				what = "video"
			}
			forbid := strings.HasPrefix(req.Action, "mute_")
			if err := deps.store.SetCallForbidden(ctx, callID, req.UserID, what, forbid); err != nil {
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			micOff, videoOff := forbidsOf(deps, ctx, callID, req.UserID)
			if forbid && deps.rooms() != nil {
				// Сразу выключить, не дожидаясь, пока LiveKit снимет дорожку по правам.
				source := "MICROPHONE"
				if what == "video" {
					source = "CAMERA"
				}
				if _, err := muteUser(deps, ctx, call.Room, req.UserID, source); err != nil {
					log.Printf("control %s %s: выключение: %v", req.Action, short(callID), err)
				}
			}
			if err := applyForbids(deps, ctx, call.Room, req.UserID, micOff, videoOff); err != nil {
				// Запрет записан и подействует при перезаходе; живая комната не ответила.
				log.Printf("control %s %s: права в комнате: %v", req.Action, short(callID), err)
			}
			deps.notifier.Users(ctx, []string{req.UserID}, "call.control", map[string]any{
				"call_id": callID, "action": req.Action, "by": id.UserID,
			})
			log.Printf("групповой звонок %s: %s у %s (микрофон запрещён=%v, видео запрещено=%v)",
				short(callID), req.Action, short(req.UserID), micOff, videoOff)
		case "remove":
			if req.UserID == "" || req.UserID == id.UserID {
				writeErr(w, http.StatusBadRequest, "bad_user", "нужен user_id участника, не свой")
				return
			}
			if err := deps.store.RemoveFromCall(ctx, callID, req.UserID); err != nil {
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			kickUser(deps, ctx, call.Room, req.UserID)
			deps.notifier.Users(ctx, []string{req.UserID}, "call.control", map[string]any{
				"call_id": callID, "action": "remove", "by": id.UserID,
			})
			// Его звонящие устройства замолкают: в этот звонок ему больше нельзя.
			deps.notifier.CallChange(ctx, req.UserID, callID, "ended", "")
			log.Printf("групповой звонок %s: удалён %s", short(callID), short(req.UserID))
		case "pause", "resume":
			paused := req.Action == "pause"
			if err := deps.store.SetCallPaused(ctx, callID, paused); err != nil {
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			meta, _ := json.Marshal(map[string]any{"paused": paused})
			if rooms := deps.rooms(); rooms != nil {
				if err := rooms.SetRoomMetadata(ctx, call.Room, string(meta)); err != nil {
					log.Printf("control pause %s: %v", short(callID), err)
				}
			}
			notifyCallUsers(deps, ctx, callID, "call.control", map[string]any{
				"call_id": callID, "action": req.Action, "by": id.UserID,
			})
			log.Printf("групповой звонок %s: %s", short(callID), req.Action)
		case "stop":
			if rooms := deps.rooms(); rooms != nil {
				if err := rooms.DeleteRoom(ctx, call.Room); err != nil {
					log.Printf("control stop %s: комната не закрылась: %v", short(callID), err)
				}
			}
			closeRoomCall(deps, ctx, call, id.UserID)
			log.Printf("групповой звонок %s: остановлен создателем", short(callID))
		default:
			writeErr(w, http.StatusBadRequest, "bad_action", "action: invite · mute_mic · mute_video · allow_mic · allow_video · remove · pause · resume · stop")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
	}
}

// muteUser выключает у человека дорожки одного вида на всех его устройствах в комнате.
func muteUser(deps callsDeps, ctx context.Context, room, userID, source string) (int, error) {
	rooms := deps.rooms()
	if rooms == nil {
		return 0, errors.New("LiveKit не настроен")
	}
	parts, err := rooms.ListParticipants(ctx, room)
	if err != nil {
		return 0, err
	}
	muted := 0
	for _, p := range parts {
		if !strings.HasPrefix(p.Identity, userID+":") {
			continue
		}
		for _, t := range p.Tracks {
			if t.Source != source || t.Muted {
				continue
			}
			if err := rooms.MuteTrack(ctx, room, p.Identity, t.Sid); err != nil {
				return muted, err
			}
			muted++
		}
	}
	return muted, nil
}

// kickUser выкидывает из комнаты все устройства человека.
func kickUser(deps callsDeps, ctx context.Context, room, userID string) {
	rooms := deps.rooms()
	if rooms == nil {
		return
	}
	parts, err := rooms.ListParticipants(ctx, room)
	if err != nil {
		log.Printf("kickUser %s: %v", short(userID), err)
		return
	}
	for _, p := range parts {
		if strings.HasPrefix(p.Identity, userID+":") {
			if err := rooms.RemoveParticipant(ctx, room, p.Identity); err != nil {
				log.Printf("kickUser %s: %v", p.Identity, err)
			}
		}
	}
}

// closeRoomCall — групповой звонок кончился: остановил создатель или опустела комната.
//
// Ленте каждого — своё слово: позванному, который так и не вошёл, — `missed` (решение 12),
// бывшим в звонке — `ended`. Временной группе срок отодвигается от конца звонка.
func closeRoomCall(deps callsDeps, ctx context.Context, call store.Call, enderID string) {
	if call.State != "ringing" && call.State != "answered" {
		return
	}
	_ = deps.store.SetCallState(ctx, call.CallID, "ended", enderID)
	ring, _ := deps.store.CallRings(ctx, call.CallID)
	parts, _ := deps.store.GroupCallParticipants(ctx, call.CallID)
	missedDue := time.Since(call.CreatedAt) < ringDeadline
	for _, p := range parts {
		switch {
		case p.UserID == call.InitiatorID:
			deps.notifier.CallChange(ctx, p.UserID, call.CallID, "ended", "")
		case p.Removed:
			// уже сказано при удалении
		case p.State == store.PartInvited && p.Invited && ring:
			// После срока вызова пропущенный уже ушёл из closeRoomUnanswered.
			if missedDue {
				deps.notifier.CallChange(ctx, p.UserID, call.CallID, "missed", "")
			}
		case p.Joined || p.UserID == call.InitiatorID:
			deps.notifier.CallChange(ctx, p.UserID, call.CallID, "ended", "")
		}
	}
	if g, err := deps.store.GetGroup(ctx, call.GroupID); err == nil {
		if g.CallTTLUntil != nil {
			_ = deps.store.BumpCallGroupTTL(ctx, call.GroupID, deps.groupRules().TTL)
		}
		if members, err := deps.store.ListGroupMembers(ctx, call.GroupID); err == nil {
			notifyGroupCall(deps, ctx, members, call.GroupID, call.CallID, "ended", enderID)
		}
	}
}

// closeRoomUnanswered — срок вызова вышел: позванным, кто так и не вошёл, — пропущенный.
// Звонок при этом идёт дальше: войти можно и после (по приглашению или полосе в группе).
func closeRoomUnanswered(deps callsDeps, callID string) {
	ctx, cancel := context.WithTimeout(context.Background(), ringDeadline+30*time.Second)
	defer cancel()
	select {
	case <-ctx.Done():
		return
	case <-time.After(ringDeadline):
	}
	call, err := deps.store.GetCall(ctx, callID)
	if err != nil || (call.State != "ringing" && call.State != "answered") {
		return // уже кончился — пропущенные сказал closeRoomCall
	}
	parts, err := deps.store.GroupCallParticipants(ctx, callID)
	if err != nil {
		return
	}
	for _, p := range parts {
		if p.State == store.PartInvited && p.Invited && !p.Removed && p.UserID != call.InitiatorID {
			deps.notifier.CallChange(ctx, p.UserID, callID, "missed", "")
			log.Printf("групповой звонок %s: %s не вошёл за срок вызова — пропущенный", short(callID), short(p.UserID))
		}
	}
}

// missedIfNotJoined — позванному посреди звонка: не вошёл за срок вызова — пропущенный.
func missedIfNotJoined(deps callsDeps, callID, userID string) {
	ctx, cancel := context.WithTimeout(context.Background(), ringDeadline+30*time.Second)
	defer cancel()
	select {
	case <-ctx.Done():
		return
	case <-time.After(ringDeadline):
	}
	parts, err := deps.store.GroupCallParticipants(ctx, callID)
	if err != nil {
		return
	}
	for _, p := range parts {
		if p.UserID == userID && p.State == store.PartInvited && p.Invited && !p.Removed {
			deps.notifier.CallChange(ctx, userID, callID, "missed", "")
		}
	}
}

// notifyGroupCall — участникам группы: звонок в ней начался или кончился. Полоса «Идёт
// звонок» над перепиской обновляется по этому событию, не дожидаясь своего опроса.
// `by` — кто начал или завершил (пусто — никто: опустела комната или вышел срок). По нему
// телефоны пишут строку в переписку группы: «Звонок начат: Анна» (заказчик 2026-10-01, 8б).
func notifyGroupCall(deps callsDeps, ctx context.Context, members []store.Member, groupID, callID, state, by string) {
	ids := make([]string, 0, len(members))
	for _, m := range members {
		ids = append(ids, m.UserID)
	}
	deps.notifier.Users(ctx, ids, "group.call", map[string]any{
		"group_id": groupID, "call_id": callID, "state": state, "by": by,
	})
}

// notifyCallUsers — событие всем, кто числится в звонке (кроме удалённых).
func notifyCallUsers(deps callsDeps, ctx context.Context, callID, event string, payload map[string]any) {
	parts, err := deps.store.GroupCallParticipants(ctx, callID)
	if err != nil {
		return
	}
	ids := make([]string, 0, len(parts))
	for _, p := range parts {
		if !p.Removed {
			ids = append(ids, p.UserID)
		}
	}
	deps.notifier.Users(ctx, ids, event, payload)
}

// leaveRoomCall — `/end` в групповом звонке: человек уходит или отклоняет вызов. Звонок
// для остальных идёт дальше — завершает его для всех только команда `stop` создателя.
func leaveRoomCall(deps callsDeps, w http.ResponseWriter, r *http.Request, call store.Call, id auth.Identity) {
	parts, err := deps.store.GroupCallParticipants(r.Context(), call.CallID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	var me *store.CallParticipant
	for i := range parts {
		if parts[i].UserID == id.UserID {
			me = &parts[i]
		}
	}
	if me == nil {
		writeErr(w, http.StatusForbidden, "not_participant", "вас нет в этом звонке")
		return
	}
	if me.State == store.PartInvited && (call.State == "ringing" || call.State == "answered") {
		// Отклонил вызов: остальные его устройства замолкают, пропущенного не будет.
		_ = deps.store.SetParticipantState(r.Context(), call.CallID, id.UserID, store.PartLeft, time.Now())
		deps.notifier.CallChange(r.Context(), id.UserID, call.CallID, "declined", id.DeviceID)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"call_id": call.CallID, "state": call.State})
}

// short — первые восемь знаков номера для журнала сервера.
func short(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}
