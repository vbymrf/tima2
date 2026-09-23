package api

import (
	"testing"
)

// callJournalRow — строка журнала, какой её видит клиент.
type callJournalRow struct {
	CallID      string `json:"call_id"`
	Kind        string `json:"kind"`
	State       string `json:"state"`
	InitiatorID string `json:"initiator_id"`
	PeerID      string `json:"peer_id"`
	EndedBy     string `json:"ended_by"`
	CreatedAt   string `json:"created_at"`
	AnsweredAt  string `json:"answered_at"`
	EndedAt     string `json:"ended_at"`
}

type callJournal struct {
	Calls      []callJournalRow `json:"calls"`
	NextBefore string           `json:"next_before"`
}

// TestЖурналЗвонковВиденОбеимСторонам — одна запись, два разных рассказа.
//
// Главное в журнале и есть это: строка в базе одна на обоих, а читается по-разному.
// Сервер поэтому отдаёт её сырой — `initiator_id`, `state`, `ended_by` и времена, — а
// «исходящий, не дозвонился» складывает клиент. Проверяем, что сырое одинаково у двоих
// и что по нему направление восстанавливается.
func TestЖурналЗвонковВиденОбеимСторонам(t *testing.T) {
	ts, _ := setupWithCalls(t)
	каллер := registerDevice(t, ts, "+79990000060")
	собеседник := registerDevice(t, ts, "+79990000061")

	var начало struct {
		CallID string `json:"call_id"`
	}
	if code := postAuthed(t, ts, каллер.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": собеседник.userID, "kind": "video"}, &начало); code != 201 {
		t.Fatalf("звонок не завёлся: %d", code)
	}

	var уКаллера, уСобеседника callJournal
	if code := getAuthed(t, ts, каллер.token, "/api/v1/calls", &уКаллера); code != 200 {
		t.Fatalf("журнал звонящего: %d", code)
	}
	if code := getAuthed(t, ts, собеседник.token, "/api/v1/calls", &уСобеседника); code != 200 {
		t.Fatalf("журнал вызываемого: %d", code)
	}
	if len(уКаллера.Calls) != 1 || len(уСобеседника.Calls) != 1 {
		t.Fatalf("звонок виден не обоим: %d и %d", len(уКаллера.Calls), len(уСобеседника.Calls))
	}
	a, b := уКаллера.Calls[0], уСобеседника.Calls[0]
	if a != b {
		t.Fatalf("сырая строка разошлась у двоих:\n%+v\n%+v", a, b)
	}
	// Вид — та самая кнопка, которой звонили. Ради него журнал и заводился: повтор
	// звонит тем же видом, а не голосом наугад.
	if a.Kind != "video" {
		t.Fatalf("вид звонка потерян: %q", a.Kind)
	}
	if a.InitiatorID != каллер.userID || a.PeerID != собеседник.userID {
		t.Fatalf("стороны перепутаны: %+v", a)
	}
	if a.EndedAt != "" || a.AnsweredAt != "" {
		t.Fatalf("у звонящего звонка проставлены времена конца: %+v", a)
	}
}

// TestЖурналЗапоминаетКтоПоложилТрубку — без этого «отменил» и «отклонил» неразличимы.
//
// `POST /end` зовут обе стороны, и состояние выходит одно — `missed`. Человек же эти
// случаи различает: не дозвонился сам — перезвонит; отклонили — подождёт. Пишется
// первый нажавший, и поверх он не переписывается.
func TestЖурналЗапоминаетКтоПоложилТрубку(t *testing.T) {
	ts, _ := setupWithCalls(t)
	каллер := registerDevice(t, ts, "+79990000062")
	собеседник := registerDevice(t, ts, "+79990000063")

	var начало struct {
		CallID string `json:"call_id"`
	}
	if code := postAuthed(t, ts, каллер.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": собеседник.userID, "kind": "audio"}, &начало); code != 201 {
		t.Fatalf("звонок не завёлся: %d", code)
	}
	// Отклоняет вызываемый — и это должно быть видно у обоих.
	if code := postAuthed(t, ts, собеседник.token, "POST",
		"/api/v1/calls/"+начало.CallID+"/end", map[string]string{}, nil); code != 200 {
		t.Fatalf("отбой не прошёл: %d", code)
	}
	// А звонящий нажимает следом — вторым, и переписать первого он не вправе.
	_ = postAuthed(t, ts, каллер.token, "POST",
		"/api/v1/calls/"+начало.CallID+"/end", map[string]string{}, nil)

	var журнал callJournal
	if code := getAuthed(t, ts, каллер.token, "/api/v1/calls", &журнал); code != 200 {
		t.Fatalf("журнал: %d", code)
	}
	if len(журнал.Calls) != 1 {
		t.Fatalf("строк в журнале: %d", len(журнал.Calls))
	}
	row := журнал.Calls[0]
	if row.State != "missed" {
		t.Fatalf("состояние: %q, ждали missed", row.State)
	}
	if row.EndedBy != собеседник.userID {
		t.Fatalf("трубку положил %q, в журнале %q", собеседник.userID, row.EndedBy)
	}
	if row.EndedAt == "" {
		t.Fatalf("время конца не проставлено: %+v", row)
	}
}

// TestЖурналОтдаётСтраницами — и вторая страница не повторяет первую.
//
// Отбор по `before` строгий. Был бы нестрогим — последняя строка первой страницы
// приходила бы первой строкой второй, и журнал показывал бы звонок дважды на каждой
// границе. Заметно это становится ровно тогда, когда звонков много.
func TestЖурналОтдаётСтраницами(t *testing.T) {
	ts, _ := setupWithCalls(t)
	каллер := registerDevice(t, ts, "+79990000064")
	собеседник := registerDevice(t, ts, "+79990000065")

	// Три звонка подряд. Каждый закрываем, иначе второй упрётся в «занято».
	for i := 0; i < 3; i++ {
		var начало struct {
			CallID string `json:"call_id"`
		}
		if code := postAuthed(t, ts, каллер.token, "POST", "/api/v1/calls",
			map[string]string{"peer_id": собеседник.userID, "kind": "audio"}, &начало); code != 201 {
			t.Fatalf("звонок %d не завёлся: %d", i, code)
		}
		if code := postAuthed(t, ts, каллер.token, "POST",
			"/api/v1/calls/"+начало.CallID+"/end", map[string]string{}, nil); code != 200 {
			t.Fatalf("звонок %d не закрылся: %d", i, code)
		}
	}

	var первая callJournal
	if code := getAuthed(t, ts, каллер.token, "/api/v1/calls?limit=2", &первая); code != 200 {
		t.Fatalf("первая страница: %d", code)
	}
	if len(первая.Calls) != 2 {
		t.Fatalf("на первой странице %d строк, просили 2", len(первая.Calls))
	}
	if первая.NextBefore == "" {
		t.Fatalf("страница полна, а продолжения не обещано")
	}
	var вторая callJournal
	if code := getAuthed(t, ts, каллер.token,
		"/api/v1/calls?limit=2&before="+первая.NextBefore, &вторая); code != 200 {
		t.Fatalf("вторая страница: %d", code)
	}
	if len(вторая.Calls) != 1 {
		t.Fatalf("на второй странице %d строк, ждали 1", len(вторая.Calls))
	}
	for _, a := range первая.Calls {
		if a.CallID == вторая.Calls[0].CallID {
			t.Fatalf("строка %s пришла на обеих страницах", a.CallID)
		}
	}
	// Продолжения больше нет: страница не полна.
	if вторая.NextBefore != "" {
		t.Fatalf("неполная страница обещает продолжение: %q", вторая.NextBefore)
	}
}

// TestЖурналНеПоказываетЧужое — звонок, к которому человек отношения не имеет.
//
// Проверка не про вёрстку: журнал отвечает на «кто кому звонил», и лишняя строка здесь
// — это выданное наружу знание о чужих разговорах, а не косметика.
func TestЖурналНеПоказываетЧужое(t *testing.T) {
	ts, _ := setupWithCalls(t)
	каллер := registerDevice(t, ts, "+79990000066")
	собеседник := registerDevice(t, ts, "+79990000067")
	посторонний := registerDevice(t, ts, "+79990000068")

	if code := postAuthed(t, ts, каллер.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": собеседник.userID, "kind": "audio"}, nil); code != 201 {
		t.Fatalf("звонок не завёлся: %d", code)
	}
	var чужой callJournal
	if code := getAuthed(t, ts, посторонний.token, "/api/v1/calls", &чужой); code != 200 {
		t.Fatalf("журнал постороннего: %d", code)
	}
	if len(чужой.Calls) != 0 {
		t.Fatalf("посторонний видит чужие звонки: %+v", чужой.Calls)
	}
}

// TestВходЗакрытВсякимНеживымСостоянием — перечисляем живые, а не хоронить по списку.
//
// Проверка входа была списком похороненных: `ended` и `missed`. Он протекал уже тогда —
// `busy` в нём не значился, — и протёк бы снова с появлением `lost`. Теперь пускают
// только `ringing` и `answered`, и всякое новое состояние по умолчанию закрыто.
func TestВходЗакрытВсякимНеживымСостоянием(t *testing.T) {
	ts, srv := setupWithCalls(t)
	каллер := registerDevice(t, ts, "+79990000070")
	собеседник := registerDevice(t, ts, "+79990000071")

	for _, состояние := range []string{"ended", "missed", "busy", "lost"} {
		var начало struct {
			CallID string `json:"call_id"`
		}
		if code := postAuthed(t, ts, каллер.token, "POST", "/api/v1/calls",
			map[string]string{"peer_id": собеседник.userID, "kind": "audio"}, &начало); code != 201 {
			t.Fatalf("%s: звонок не завёлся: %d", состояние, code)
		}
		if err := srv.Store.SetCallState(t.Context(), начало.CallID, состояние, ""); err != nil {
			t.Fatal(err)
		}
		if code := postAuthed(t, ts, собеседник.token, "POST",
			"/api/v1/calls/"+начало.CallID+"/join", map[string]string{}, nil); code != 410 {
			t.Fatalf("вход в звонок в состоянии %q: %d, ожидали 410", состояние, code)
		}
	}
}

// TestЖурналНеПоказываетДлительностьУОборванного — `lost` не врёт про минуты.
//
// `ended_at` у брошенного звонка ставит уборщик в момент уборки, а ходит он раз в час.
// Разговор на минуту читался как час с лишним. Состояние `lost` говорит «конец
// неизвестен», и клиент по нему длительность не считает.
func TestЖурналНеПоказываетДлительностьУОборванного(t *testing.T) {
	ts, srv := setupWithCalls(t)
	каллер := registerDevice(t, ts, "+79990000072")
	собеседник := registerDevice(t, ts, "+79990000073")

	var начало struct {
		CallID string `json:"call_id"`
	}
	if code := postAuthed(t, ts, каллер.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": собеседник.userID, "kind": "audio"}, &начало); code != 201 {
		t.Fatalf("звонок не завёлся: %d", code)
	}
	// Разговор состоялся, а конец потерялся — и его закрыл уборщик.
	if err := srv.Store.SetCallState(t.Context(), начало.CallID, "answered", ""); err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.SetCallState(t.Context(), начало.CallID, "lost", ""); err != nil {
		t.Fatal(err)
	}

	var журнал callJournal
	if code := getAuthed(t, ts, каллер.token, "/api/v1/calls", &журнал); code != 200 {
		t.Fatalf("журнал: %d", code)
	}
	if len(журнал.Calls) != 1 {
		t.Fatalf("строк в журнале: %d", len(журнал.Calls))
	}
	row := журнал.Calls[0]
	if row.State != "lost" {
		t.Fatalf("состояние: %q, ждали lost", row.State)
	}
	// Трубку не клал никто — иначе бы состояние было не lost, а ended.
	if row.EndedBy != "" {
		t.Fatalf("у оборванного звонка назван тот, кто положил трубку: %q", row.EndedBy)
	}
	// Времена при этом есть оба — и именно поэтому по ним нельзя считать: `ended_at`
	// здесь момент уборки, а не момент конца.
	if row.AnsweredAt == "" || row.EndedAt == "" {
		t.Fatalf("времена не проставлены: %+v", row)
	}
}
