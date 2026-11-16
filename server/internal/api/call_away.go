package api

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"sync"
	"time"
)

// ── ЛИЧНЫЙ ЗВОНОК: УШЕДШЕГО ЖДЁМ, А НЕ ХОРОНИМ ─────────────────────────────────
//
// Беда 2026-11-15 (doc_mig/БЕДЫ/2026-11-15-обрыв-звонка-при-смене-сети.md, жалобы UABD, YWME, M4C2): у
// собеседника на миг пропадала сеть (смена Wi-Fi и мобильной, мобильный вызов), его приложение за полторы
// секунды переподключалось, LiveKit менял старое подключение новым и слал `participant_left` — а сервер
// закрывал звонок обоим и удалял комнату вместе с вернувшимся.
//
// Теперь по причине ухода (`participant.disconnectReason` вебхука, решение заказчика 2026-11-16 «Делай все
// три, 20 секунд»):
//   - DUPLICATE_IDENTITY — то же устройство уже вошло заново: звонок идёт, ничего не делаем;
//   - CLIENT_INITIATED — вышел сам (положил трубку): закрываем сразу, как раньше;
//   - прочее или причины нет — ждём возврата [awayWait]; вошёл снова — звонок идёт, нет — закрываем, и конец
//     записывается моментом ухода, а не концом ожидания.
// Пока ждём, ушедший не «занят»: позвонит сам или ему позвонит тот же собеседник — прежний звонок
// закрывается сразу (`settleAway`).
//
// Ожидание — в памяти сервера: перезапуск его теряет, и звонок закроет уборщик открытых звонков.

// awayWait — сколько ждать возврата ушедшего; в проверках короче.
var awayWait = 20 * time.Second

type awayEntry struct {
	userID string
	at     time.Time
	timer  *time.Timer
}

type awayCalls struct {
	mu sync.Mutex
	m  map[string]*awayEntry // звонок → кто ушёл
}

func newAwayCalls() *awayCalls { return &awayCalls{m: map[string]*awayEntry{}} }

// left — ушёл не сам: ждём; не вернулся за [awayWait] — [end] с моментом ухода.
func (a *awayCalls) left(callID, userID string, at time.Time, end func(at time.Time)) {
	if a == nil {
		end(at)
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if old := a.m[callID]; old != nil {
		old.timer.Stop()
	}
	e := &awayEntry{userID: userID, at: at}
	e.timer = time.AfterFunc(awayWait, func() {
		a.mu.Lock()
		cur := a.m[callID]
		if cur != e {
			a.mu.Unlock()
			return
		}
		delete(a.m, callID)
		a.mu.Unlock()
		log.Printf("личный звонок %s: %s не вернулся за %v — закрываю", short(callID), short(userID), awayWait)
		end(at)
	})
	a.m[callID] = e
	log.Printf("личный звонок %s: %s вышел не сам — жду возврата %v", short(callID), short(userID), awayWait)
}

// back — ушедший вошёл снова: звонок идёт.
func (a *awayCalls) back(callID, userID string) {
	if a == nil {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if e := a.m[callID]; e != nil && e.userID == userID {
		e.timer.Stop()
		delete(a.m, callID)
		log.Printf("личный звонок %s: %s вернулся — звонок идёт", short(callID), short(userID))
	}
}

// take — забрать ожидание звонка (закрыли иначе или закрываем сразу); false — не ждали.
func (a *awayCalls) take(callID string) (awayEntry, bool) {
	if a == nil {
		return awayEntry{}, false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	e := a.m[callID]
	if e == nil {
		return awayEntry{}, false
	}
	e.timer.Stop()
	delete(a.m, callID)
	return *e, true
}

// who — кого ждут в звонке.
func (a *awayCalls) who(callID string) (string, bool) {
	if a == nil {
		return "", false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if e := a.m[callID]; e != nil {
		return e.userID, true
	}
	return "", false
}

// calls — звонки, в которых сейчас ждут.
func (a *awayCalls) calls() []string {
	if a == nil {
		return nil
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]string, 0, len(a.m))
	for id := range a.m {
		out = append(out, id)
	}
	return out
}

// settleAway — новый личный звонок от [caller] к [peer]: звонок, где ждут самого звонящего или где ждут
// одного из этих двоих друг с другом, закрывается сразу — человек явно не в нём. Иначе повторный вызов
// получил бы «занят» или «у вас уже идёт звонок» на всё ожидание.
func settleAway(deps callsDeps, ctx context.Context, caller, peer string) {
	for _, callID := range deps.away.calls() {
		who, ok := deps.away.who(callID)
		if !ok {
			continue
		}
		call, err := deps.store.GetCall(ctx, callID)
		if err != nil || call.Type == "group" {
			continue
		}
		pair := (call.InitiatorID == caller && call.PeerID == peer) || (call.InitiatorID == peer && call.PeerID == caller)
		if !pair && who != caller {
			// Ждут другого человека в чужом звонке — он ещё может вернуться.
			continue
		}
		e, ok := deps.away.take(callID)
		if !ok {
			continue
		}
		log.Printf("личный звонок %s: новый вызов — прежний, где ждали %s, закрываю", short(callID), short(e.userID))
		endDirectAt(deps, ctx, callID, e.userID, e.at)
	}
}

// inRoom — есть ли [userID] в комнате LiveKit любым своим устройством. LiveKit не ответил — «нет»: звонок
// закроется, как закрывался до ожидания.
func inRoom(deps callsDeps, room, userID string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	people, err := deps.rooms().ListParticipants(ctx, room)
	if err != nil {
		return false
	}
	for _, p := range people {
		if strings.HasPrefix(p.Identity, userID+":") {
			return true
		}
	}
	return false
}

// disconnectReason — причина ухода из вебхука: LiveKit пишет перечисление именем (protojson), на всякий
// случай принимается и номер. Пусто — причины нет.
func disconnectReason(raw json.RawMessage) string {
	s := strings.Trim(strings.TrimSpace(string(raw)), `"`)
	switch s {
	case "1":
		return "CLIENT_INITIATED"
	case "2":
		return "DUPLICATE_IDENTITY"
	case "", "0", "null":
		return ""
	}
	return s
}
