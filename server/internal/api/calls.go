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
		// Звонок, где ждут возврата одного из этих двоих (или самого звонящего), закрывается сразу: человек
		// явно не в нём, и «занят» на всё ожидание было бы неправдой (беда 2026-11-15, `call_away.go`).
		settleAway(deps, r.Context(), id.UserID, req.PeerID)
		// ── ВСТРЕЧНЫЙ ВЫЗОВ — СОЕДИНИТЬ, А НЕ «ЗАНЯТ» ──────────────────────────
		//
		// Собеседник уже звонит мне — и я набираю его. Прежде проверка ниже видела его вызов и отвечала «занят»
		// обоим (отчёты 47H7, DZKC, LF57, 2026-10-26). Заказчик 2026-11-20: «соединяем». Второй вызов не
		// заводится: набравший входит в первый как ответивший — так делают телефоны.
		//
		// Проверка и заведение — под замком пары: иначе два нажатия в одну секунду оба не видят встречного и
		// заводят по звонку. Беда замка (база недоступна) звонок не отнимает — идём прежним путём.
		if deps.crossing != nil {
			if unlock, err := deps.crossing.Lock(r.Context(), id.UserID, req.PeerID); err == nil {
				defer unlock()
			}
			if callID, err := deps.crossing.Ringing(r.Context(), req.PeerID, id.UserID); err == nil && callID != "" {
				if call, err := deps.store.GetCall(r.Context(), callID); err == nil {
					log.Printf("звонок %s: встречный вызов — %s входит ответившим", callID[:8], id.UserID[:8])
					answerWith(deps, w, r, call, id, http.StatusCreated)
					return
				}
			}
		}
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
		// Вызов — изменение `ringing` в ленте собеседника и подсказка всем его устройствам
		// (ВЗ0а). Кто звонит и каким звонком, телефон узнает из снимка звонка в ленте.
		cts := deps.notifier.CallChange(r.Context(), req.PeerID, callID, "ringing", "")
		// Повтор подсказки, «не в сети» через 5 с и срок звонка — см. call_updates.go.
		afterRing(deps, callID, id.UserID, req.PeerID, cts)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"call_id": callID, "room": room, "url": deps.livekitURL(), "token": token,
			// Потолок видео (ПЛАН-(В)-ВИДЕО.md В5б): новое поле, клиент без него берёт своё.
			"video": deps.video(),
		})
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
		answerWith(deps, w, r, call, id, http.StatusOK)
	}
}

// answerWith — ответить на звонок: токен комнаты, состояние answered, сказать обеим сторонам. Зовут «Принять» и
// встречный вызов (`startCall`); у второго ответ — 201 с `call_id` и `crossed`, как у заведённого звонка.
func answerWith(deps callsDeps, w http.ResponseWriter, r *http.Request, call store.Call, id auth.Identity, status int) {
	callID := call.CallID
	token, err := deps.issuer().Token(call.Room, callIdentity(id), true, callTokenTTL, time.Now())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "не выдался токен")
		return
	}
	_ = deps.store.SetCallState(r.Context(), callID, "answered", "")
	// ── ОСТАЛЬНЫЕ УСТРОЙСТВА ОТВЕТИВШЕГО ───────────────────────────────
	//
	// Вызов звонит на ВСЕХ устройствах человека. Ответил телефон — десктоп обязан
	// замолчать, а не звонить сорок пять секунд и класть трубку живому разговору.
	//
	// Раньше для этого было отдельное слово `taken` адресно соседям. Теперь изменение
	// одно — `answered` с ответившим устройством, — а «взяли здесь или на другом»
	// ответ ленты говорит каждому спрашивающему сам (`here`).
	deps.notifier.CallChange(r.Context(), id.UserID, callID, "answered", id.DeviceID)
	deps.notifier.CallChange(r.Context(), call.InitiatorID, callID, "answered", "")
	w.Header().Set("Content-Type", "application/json")
	body := map[string]any{"room": call.Room, "url": deps.livekitURL(), "token": token, "video": deps.video()}
	if status == http.StatusCreated {
		// Новые поля: клиент без них видит обычный заведённый звонок и входит в ту же комнату.
		body["call_id"] = callID
		body["crossed"] = true
		body["kind"] = call.Kind
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
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
		// Групповой: уход одного звонок не кончает (ГЗ2). Для всех — `control stop`.
		if call.Type == "group" && call.GroupID != "" {
			leaveRoomCall(deps, w, r, call, id)
			return
		}
		if call.InitiatorID != id.UserID && call.PeerID != id.UserID {
			writeErr(w, http.StatusForbidden, "not_participant", "завершить может участник звонка")
			return
		}
		// Положили трубку — ждать ушедшего больше незачем (беда 2026-11-15).
		deps.away.take(callID)
		// Уже закрытый звонок не переписывается: `/end` зовут обе стороны, и второй
		// получает в ответ то, что решил первый (Ж4).
		open := call.State == "ringing" || call.State == "answered"
		state := "ended"
		if call.State == "ringing" {
			state = "missed"
		}
		if !open {
			state = call.State
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
		// «Занят» на уже отвеченном — опоздавший отказ, а не конец разговора: вызов звонил на всех устройствах, ответило
		// другое (или вошёл встречный, 2026-11-20), а этот, занятый своим, только теперь сказал «занят». Закрыть по
		// нему значило бы положить трубку живому разговору.
		if r.URL.Query().Get("reason") == "busy" && call.State == "answered" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"call_id": callID, "state": call.State})
			return
		}
		// Закрываем комнату НА САМОМ ДЕЛЕ. Без этого «завершить» меняло состояние у нас
		// и рассылало уведомление, а комната жила до empty_timeout: клиент, который
		// уведомление не получил или проигнорировал, продолжал публиковать звук.
		if err := deps.rooms().DeleteRoom(r.Context(), call.Room); err != nil {
			log.Printf("endCall: комната %s не закрылась: %v", call.Room, err)
		}
		// Кто положил трубку — сведение, которого в базе не было: `/end` зовут обе
		// стороны, и без имени первого «отменил» и «отклонил» неразличимы. Ж4.
		// Обеим сторонам, а не только «другой»: у кладущего трубку могут быть ещё
		// устройства, и звонящий десктоп должен узнать, что звонок кончился на телефоне.
		// Уже закрытый звонок второй раз не пишется и не объявляется.
		if open {
			_ = deps.store.SetCallState(r.Context(), callID, state, id.UserID)
			change := endChange(call, id.UserID, state)
			deps.notifier.CallChange(r.Context(), call.InitiatorID, callID, change, "")
			if call.PeerID != "" {
				deps.notifier.CallChange(r.Context(), call.PeerID, callID, change, "")
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"call_id": callID, "state": state})
	}
}
