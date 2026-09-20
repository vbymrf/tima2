// Звонки 1:1 (calls-livekit.md §3): бэкенд создаёт комнату и выдаёт LiveKit-токены,
// уведомляет собеседника (call.incoming). Медиа идёт через LiveKit, не через нас.
// Живой звонок требует развёрнутого LiveKit и реальных устройств — токен выдаётся
// и без сервера LiveKit, но подключение по нему нужно к работающему SFU.
package api

import (
	"context"
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

// callIdentity — как участник называется в LiveKit: пара «человек:устройство».
func callIdentity(id auth.Identity) string { return id.UserID + ":" + id.DeviceID }

// startCall — POST /calls {peer_id, kind}: комната + токен инициатора, звонок собеседнику.
func startCall(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.issuer() == nil {
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

		// ── ЗАНЯТ ───────────────────────────────────────────────────────────
		//
		// Раньше звонок занятому человеку заводился как обычный: у него он не
		// показывался (один сеанс за раз), а звонящий слушал гудки до своего срока и
		// узнавал только «никто не ответил». Это неправда — ответить было некому, а не
		// некогда.
		//
		// Отказ отдельным кодом, а не общим: «занят» и «не отвечает» человек различает и
		// поступает по-разному — во втором случае перезванивают сразу, в первом ждут.
		//
		// Ошибка запроса в сторону «свободен» дешевле: лишний вызов виден, а отнятый —
		// нет. Поэтому беду хранилища здесь не превращаем в «занят», а пропускаем.
		if busy, err := deps.store.HasLiveCall(r.Context(), req.PeerID); err == nil && busy {
			writeErr(w, http.StatusConflict, "busy", "собеседник занят другим звонком")
			return
		}
		// Занят может быть и сам звонящий. Клиент это и так не даёт, но правило «один
		// сеанс за раз» принадлежит серверу, а не кнопке: у человека несколько устройств,
		// и второе про звонок первого не знает.
		if busy, err := deps.store.HasLiveCall(r.Context(), id.UserID); err == nil && busy {
			writeErr(w, http.StatusConflict, "already_in_call", "у вас уже идёт звонок")
			return
		}

		// room уникальна на звонок; генерируем как UUID (переиспользуем newUUID)
		room := "call-" + newUUID()
		callID, err := deps.store.CreateCall(r.Context(), store.Call{
			Room: room, Kind: req.Kind, InitiatorID: id.UserID, PeerID: req.PeerID,
		})
		if err != nil {
			log.Printf("startCall: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		token, err := deps.issuer().Token(room, callIdentity(id), true, callTokenTTL, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		// call.incoming устройствам собеседника (VoIP push — с провайдером позже)
		payload := map[string]any{"call_id": callID, "room": room, "kind": req.Kind, "from": id.UserID}
		ringPeer(deps, r.Context(), req.PeerID, payload)
		// И ещё дважды, пока звонок звонит, — см. ringAgain.
		go ringAgain(deps, callID, req.PeerID, payload)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"call_id": callID, "room": room, "url": deps.livekitURL(), "token": token,
		})
	}
}

// ringPeer — разослать кадр всем устройствам собеседника.
func ringPeer(deps callsDeps, ctx context.Context, peerID string, payload map[string]any) {
	devices, err := deps.store.ListDevices(ctx, peerID)
	if err != nil {
		log.Printf("ringPeer %s: %v", peerID, err)
		return
	}
	for _, d := range devices {
		deps.notifier.Device(ctx, d.DeviceID, "call.incoming", payload)
	}
}

// Когда повторять вызов и сколько раз.
//
// Две попытки с разбегом: почти всякая потеря — одиночная, и второй попытки хватает.
// Третья пришлась бы уже на середину сорокапятисекундного ожидания и разбудила бы
// человека, который к тому времени трубку взял.
var ringAgainAfter = []time.Duration{2 * time.Second, 6 * time.Second}

// ringAgain — повторить call.incoming, пока звонок всё ещё звонит.
//
// ── ЗАЧЕМ ЭТО НУЖНО ─────────────────────────────────────────────────────────
//
// Живая доставка идёт через Redis Pub/Sub — это «не более одного раза». Кадр, потерянный
// по дороге, теряется насовсем: долговечный журнал событий у нас есть, но клиент читает
// его только при переподключении, а соединение при этом не рвётся.
//
// Для сообщений дыра почти не видна — они подождут следующего подключения. Для звонка
// она смертельна: он живёт сорок пять секунд, и потерянный кадр означает, что человеку
// просто не позвонили. Ровно это и случилось 2026-09-20: сервер записал событие 326,
// отчёты обоих телефонов показывают, что до второго оно не дошло, а соседние события того
// же устройства дошли.
//
// **Это заплатка, а не лечение.** Лечение — доставка «хотя бы один раз», то есть Redis
// Streams вместо Pub/Sub: [ПЛАН-ДОСТАВКИ-СОБЫТИЙ.md](../../../doc_mig/ПЛАН-ДОСТАВКИ-СОБЫТИЙ.md).
// Здесь же цена повтора — два лишних кадра на звонок, и она заведомо меньше цены
// несостоявшегося звонка.
//
// **Клиент обязан уметь повтор.** Дубликат, пришедший на показанный звонок, отсеивается
// по идентификатору (`CallHost.ring`); клиент без этой защиты положил бы трубку
// собственному вызову, приняв повтор за второй звонок.
func ringAgain(deps callsDeps, callID, peerID string, payload map[string]any) {
	// Свой контекст: запрос к этому времени давно закрыт, а его отмена унесла бы повтор.
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for _, after := range ringAgainAfter {
		select {
		case <-ctx.Done():
			return
		case <-time.After(after):
		}
		// Звонок мог быть отвечен, отклонён или отменён. Повторять его тогда — звать
		// человека к разговору, который уже идёт или уже кончился.
		call, err := deps.store.GetCall(ctx, callID)
		if err != nil || call.State != "ringing" {
			return
		}
		ringPeer(deps, ctx, peerID, payload)
	}
}

// answerCall — POST /calls/{callID}/answer: токен для собеседника, состояние answered.
func answerCall(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.issuer() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
			return
		}
		callID := r.PathValue("callID")
		call, err := deps.store.GetCall(r.Context(), callID)
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
		token, err := deps.issuer().Token(call.Room, callIdentity(id), true, callTokenTTL, time.Now())
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
			return
		}
		_ = deps.store.SetCallState(r.Context(), callID, "answered")
		// ── ОСТАЛЬНЫЕ УСТРОЙСТВА ОТВЕТИВШЕГО ───────────────────────────────
		//
		// `call.incoming` уходит ВСЕМ устройствам человека, и звонят они все. Пока
		// устройство одно, это незаметно; со вторым — беда, и тихая: ответил телефон, а
		// десктоп продолжает звонить, и через сорок пять секунд его сторож кладёт трубку
		// запросом `/end`. Сервер закрывает звонок — **живой разговор обрывается**, и
		// выглядит это как беда связи.
		//
		// Слово `taken` отдельное, не `ended`: соседу надо **закрыть у себя окно**, а не
		// закончить звонок. Спутать эти два действия — значит получить то же самое
		// лекарством.
		if devices, err := deps.store.ListDevices(r.Context(), id.UserID); err == nil {
			for _, d := range devices {
				if d.DeviceID == id.DeviceID {
					continue // сам ответивший знает и так
				}
				deps.notifier.Device(r.Context(), d.DeviceID, "call.state",
					map[string]any{"call_id": callID, "state": "taken"})
			}
		}
		if devices, err := deps.store.ListDevices(r.Context(), call.InitiatorID); err == nil {
			for _, d := range devices {
				deps.notifier.Device(r.Context(), d.DeviceID, "call.state", map[string]any{"call_id": callID, "state": "answered"})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"room": call.Room, "url": deps.livekitURL(), "token": token})
	}
}

// ── Аудио-чаты (постоянные голосовые комнаты) ──

func createVoiceRoom(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil || req.Title == "" {
			writeErr(w, http.StatusBadRequest, "bad_title", "нужен title")
			return
		}
		id, _ := auth.FromContext(r.Context())
		roomID, err := deps.store.CreateVoiceRoom(r.Context(), req.Title, id.UserID)
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

func listVoiceRooms(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rooms, err := deps.store.ListVoiceRooms(r.Context(), 50)
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
func joinVoiceRoom(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.issuer() == nil {
			writeErr(w, http.StatusServiceUnavailable, "no_livekit", "звонки не сконфигурированы")
			return
		}
		roomID := r.PathValue("roomID")
		vr, err := deps.store.GetVoiceRoom(r.Context(), roomID)
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
		speaker, err := deps.store.IsSpeaker(r.Context(), vr.RoomID, vr.OwnerID, id.UserID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		token, err := deps.issuer().Token(room, callIdentity(id), speaker, 10*time.Minute, time.Now())
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
			"room": room, "url": deps.livekitURL(), "token": token, "title": vr.Title,
			"role": role, "is_owner": vr.OwnerID == id.UserID,
		})
	}
}

// raiseHand — POST /voice-rooms/{id}/hand: слушатель просит слово → владельцу voice.hand.
func raiseHand(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		roomID := r.PathValue("roomID")
		vr, err := deps.store.GetVoiceRoom(r.Context(), roomID)
		if errors.Is(err, store.ErrVoiceRoomNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "аудио-чат не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if devices, err := deps.store.ListDevices(r.Context(), vr.OwnerID); err == nil {
			for _, d := range devices {
				deps.notifier.Device(r.Context(), d.DeviceID, "voice.hand", map[string]any{"room_id": roomID, "user_id": id.UserID})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
	}
}

// setSpeaker — POST /voice-rooms/{id}/grant|revoke {user_id}: владелец даёт/забирает слово.
func setSpeaker(deps callsDeps, w http.ResponseWriter, r *http.Request, grant bool) {
	roomID := r.PathValue("roomID")
	vr, err := deps.store.GetVoiceRoom(r.Context(), roomID)
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
		err = deps.store.AddSpeaker(r.Context(), roomID, req.UserID)
	} else {
		err = deps.store.RemoveSpeaker(r.Context(), roomID, req.UserID)
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
	if devices, err := deps.store.ListDevices(r.Context(), req.UserID); err == nil {
		for _, d := range devices {
			deps.notifier.Device(r.Context(), d.DeviceID, event, map[string]any{"room_id": roomID})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
}

func grantSpeaker(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) { setSpeaker(deps, w, r, true) }
}

func revokeSpeaker(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) { setSpeaker(deps, w, r, false) }
}

// endCall — POST /calls/{callID}/end: завершение любым участником.
func endCall(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		callID := r.PathValue("callID")
		call, err := deps.store.GetCall(r.Context(), callID)
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
		// ── ПРИЧИНА ОТ ТОГО, КТО ЕЁ ЗНАЕТ ───────────────────────────────────
		//
		// «Занят» знает не база, а телефон собеседника: он получил вызов во время
		// разговора и сам его закончил. Сервер этого знания не имеет — состояние
		// `answered` в базе закрывается снаружи и при утечке живёт вечно, а проверка по
		// нему однажды заперла всех на пять часов (2026-09-20).
		//
		// Поэтому слово приходит от клиента. Принимается **только `busy`** и **только на
		// звонке, который ещё звонил**: на отвеченном «занят» означал бы неправду, а
		// произвольное слово от клиента стало бы состоянием, которого сервер не заводил.
		if r.URL.Query().Get("reason") == "busy" && call.State == "ringing" {
			state = "busy"
		}
		// Закрываем комнату НА САМОМ ДЕЛЕ. Без этого «завершить» меняло состояние у нас
		// и рассылало уведомление, а комната жила до empty_timeout: клиент, который
		// уведомление не получил или проигнорировал, продолжал публиковать звук.
		if err := deps.rooms().DeleteRoom(r.Context(), call.Room); err != nil {
			log.Printf("endCall: комната %s не закрылась: %v", call.Room, err)
		}
		_ = deps.store.SetCallState(r.Context(), callID, state)
		other := call.PeerID
		if id.UserID == call.PeerID {
			other = call.InitiatorID
		}
		if devices, err := deps.store.ListDevices(r.Context(), other); err == nil {
			for _, d := range devices {
				deps.notifier.Device(r.Context(), d.DeviceID, "call.state", map[string]any{"call_id": callID, "state": state})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"call_id": callID, "state": state})
	}
}
