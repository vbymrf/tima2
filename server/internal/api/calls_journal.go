// Журнал звонков: GET /api/v1/calls — что было, а не что идёт.
// И снимок одного звонка: GET /api/v1/calls/{id} — что с ним прямо сейчас.
// Планы: doc_mig/ПЛАН-ЖУРНАЛА-ЗВОНКОВ.md Ж1, doc_mig/ПЛАН-ДОСТАВКИ-ПОЛОСАМИ.md П1.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Сколько строк отдаём за раз, если клиент не попросил иначе.
//
// Тридцать — примерно экран с запасом на прокрутку. Больше означало бы возить то, чего
// не увидят: журнал открывают, чтобы перезвонить последнему, а не читать месяц.
const callsPageDefault = 30

// listCalls — GET /api/v1/calls?limit=&before=: страница журнала, новые → старые.
//
// ── ПОЧЕМУ СТРОКА ОТДАЁТСЯ СЫРОЙ ────────────────────────────────────────────
//
// Ни «направления», ни «исхода» здесь нет, хотя сервер мог бы их посчитать: он знает,
// кто спрашивает. Отдаются `initiator_id`, `state`, `ended_by` и времена, а «исходящий,
// не дозвонился» складывает клиент.
//
// Причина в том, что строка читается **по-разному у двоих**: одна и та же запись
// `missed` у звонившего означает «не дозвонился», а у вызываемого — «пропущенный»; та
// же с `ended_by = вызываемый` — «отклонил». Посчитай это на сервере — и получится
// поле, которое зависит от того, кто спросил: кэшировать его нельзя, сверять двумя
// запросами нельзя, а `GET /calls/{id}` обязан отдавать то же самое (П1).
//
// Сырая строка одинакова для всех, и разойтись двум видам одного звонка не на чем.
func listCalls(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		limit := callsPageDefault
		if v := r.URL.Query().Get("limit"); v != "" {
			if n, err := strconv.Atoi(v); err == nil && n > 0 {
				limit = n
			}
		}
		var before time.Time
		if v := r.URL.Query().Get("before"); v != "" {
			t, err := time.Parse(time.RFC3339, v)
			if err != nil {
				writeErr(w, http.StatusBadRequest, "bad_before", "before — время в RFC3339")
				return
			}
			before = t
		}
		rows, err := deps.store.ListCalls(r.Context(), id.UserID, before, limit)
		if err != nil {
			log.Printf("listCalls %s: %v", id.UserID, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(rows))
		for _, c := range rows {
			out = append(out, callJSON(c))
		}
		body := map[string]any{"calls": out}
		// Следующая страница обещается только когда страница полна. Иначе клиент
		// сходил бы за пустотой ровно один раз на каждое открытие журнала — и это
		// заметно ровно у тех, у кого звонков мало.
		if len(rows) == limit {
			body["next_before"] = rows[len(rows)-1].CreatedAt.UTC().Format(time.RFC3339Nano)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(body)
	}
}

// callJSON — строка звонка на проводе.
//
// **Одно место на список и на снимок.** `GET /calls/{id}` (П1) обязан отдавать ту же
// строку, и собирать её вторым куском кода нельзя: два вида одного и того же однажды
// разойдутся, и разойдутся молча.
//
// Пустые времена не отдаются вовсе, а не отдаются нулём: `answered_at` отсутствует —
// это «трубку не брали», и сказано оно должно быть отсутствием, а не 1970 годом.
func callJSON(c store.CallRow) map[string]any {
	m := map[string]any{
		"call_id":      c.CallID,
		"kind":         c.Kind,
		"state":        c.State,
		"initiator_id": c.InitiatorID,
		"peer_id":      c.PeerID,
		"created_at":   c.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
	if c.EndedBy != "" {
		m["ended_by"] = c.EndedBy
	}
	if !c.AnsweredAt.IsZero() {
		m["answered_at"] = c.AnsweredAt.UTC().Format(time.RFC3339Nano)
	}
	if !c.EndedAt.IsZero() {
		m["ended_at"] = c.EndedAt.UTC().Format(time.RFC3339Nano)
	}
	return m
}

// callSnapshot — GET /api/v1/calls/{callID}: что со звонком прямо сейчас.
//
// ── РАДИ ЧЕГО ЭТА РУЧКА ЗАВЕДЕНА ────────────────────────────────────────────
//
// Кадр звонка перестаёт нести состояние и становится подсказкой «посмотри звонок N»
// (П2). Подсказка **протухнуть не может**: вызов недельной давности, доехавший до
// телефона, приводит не к звонку, а к этому запросу, который честно отвечает
// «кончился». Целый класс бед — звонки по мёртвым вызовам — исчезает не починкой, а
// устройством.
//
// ── ТРИ ОТВЕТА, И ТРЕТИЙ ОБЯЗАТЕЛЕН ─────────────────────────────────────────
//
// `403` спрашивающему не-участнику — не формальность. Без него ручка становится
// способом узнать, разговаривает ли человек прямо сейчас: перебирай идентификаторы и
// смотри на `state`. Право проверяется как в `/join`.
//
// `404` — строку убрал сборщик мусора. Для клиента это то же, что «кончился».
func callSnapshot(deps callsDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		callID := r.PathValue("callID")
		call, err := deps.store.GetCall(r.Context(), callID)
		if errors.Is(err, store.ErrCallNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "звонок не найден")
			return
		} else if err != nil {
			log.Printf("callSnapshot %s: %v", callID, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if !maySeeCall(deps, r, call, id.UserID) {
			writeErr(w, http.StatusForbidden, "not_participant", "звонок не ваш")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(callJSON(call.Row()))
	}
}

// maySeeCall — сторона личного звонка или участник группового.
//
// Та же проверка, что в `/join`, и намеренно тем же способом: разойдись они — и
// «кому выдаём токен» перестанет совпадать с «кому показываем состояние».
func maySeeCall(deps callsDeps, r *http.Request, call store.Call, userID string) bool {
	if call.InitiatorID == userID || call.PeerID == userID {
		return true
	}
	if _, err := deps.store.CallForJoinByID(r.Context(), call.CallID, userID); err == nil {
		return true
	}
	return false
}
