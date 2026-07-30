package api

// Групповые звонки (Р5). Проверяем то, что решает бэкенд: кого пригласили, кому
// выдать токен, что говорят вебхуки LiveKit. Медиа не трогаем — это зона SFU.

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"tima/server/internal/calls"
	"tima/server/internal/store"
)

const testLiveKitKey, testLiveKitSecret = "devkey", "devsecret-devsecret-devsecret"

func withCalls(srv *Server) {
	srv.Calls = calls.NewIssuer(testLiveKitKey, testLiveKitSecret)
	srv.LiveKitURL = "wss://lk.example.test"
	// Rooms намеренно nil: боевого LiveKit в тестах нет, а DeleteRoom на nil —
	// осознанный no-op, чтобы отсутствие SFU не роняло завершение звонка.
}

// createGroupWith заводит группу и добавляет в неё участников.
func createGroupWith(t *testing.T, ts *httptest.Server, owner *device, members ...*device) string {
	t.Helper()
	var created struct {
		GroupID string `json:"group_id"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/groups", owner.token,
		map[string]any{"title": "Созвон", "kind": "private"}, &created); code != 201 {
		t.Fatalf("создание группы: %d", code)
	}
	for _, m := range members {
		if code := jsonAuth(t, ts, "POST", "/api/v1/groups/"+created.GroupID+"/members", owner.token,
			map[string]any{"user_id": m.userID}, nil); code != 201 && code != 200 {
			t.Fatalf("добавление участника: %d", code)
		}
	}
	return created.GroupID
}

type groupCallResp struct {
	CallID string `json:"call_id"`
	Room   string `json:"room"`
	Token  string `json:"token"`
	Type   string `json:"type"`
}

func startGroupCallAs(t *testing.T, ts *httptest.Server, d *device, groupID string) (groupCallResp, int) {
	t.Helper()
	var out groupCallResp
	code := jsonAuth(t, ts, "POST", "/api/v1/calls/group", d.token,
		map[string]any{"group_id": groupID, "kind": "audio"}, &out)
	return out, code
}

func TestGroupCallInvitesEveryone(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990060001")
	member := registerDevice(t, ts, "+79990060002")
	outsider := registerDevice(t, ts, "+79990060003")

	groupID := createGroupWith(t, ts, owner, member)

	call, code := startGroupCallAs(t, ts, owner, groupID)
	if code != 201 {
		t.Fatalf("создание звонка: %d", code)
	}
	if call.Type != "group" || call.Token == "" || call.Room == "" {
		t.Fatalf("неполный ответ: %+v", call)
	}

	parts, err := srv.Store.CallParticipants(t.Context(), call.CallID)
	if err != nil {
		t.Fatal(err)
	}
	// Инициатор — обычный участник: в группе он не медиа-хаб, и его обрыв не
	// роняет звонок для остальных.
	if _, ok := parts[owner.userID]; !ok {
		t.Fatal("инициатор не попал в список приглашённых")
	}
	if _, ok := parts[member.userID]; !ok {
		t.Fatal("участник группы не приглашён")
	}
	if _, ok := parts[outsider.userID]; ok {
		t.Fatal("посторонний оказался приглашён")
	}

	// Не участник группы звонить в неё не может.
	if _, code := startGroupCallAs(t, ts, outsider, groupID); code != http.StatusForbidden {
		t.Fatalf("посторонний создал звонок: %d", code)
	}
}

// Один эндпоинт входа закрывает оба случая: не ответил сразу и выпал-вернулся.
func TestGroupCallJoinAndRejoin(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990061001")
	member := registerDevice(t, ts, "+79990061002")
	outsider := registerDevice(t, ts, "+79990061003")
	groupID := createGroupWith(t, ts, owner, member)

	call, _ := startGroupCallAs(t, ts, owner, groupID)

	// Первый вход того, кто не ответил сразу.
	var joined struct {
		Room  string `json:"room"`
		Token string `json:"token"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+call.CallID+"/join", member.token, nil, &joined); code != 200 {
		t.Fatalf("вход приглашённого: %d", code)
	}
	if joined.Room != call.Room || joined.Token == "" {
		t.Fatalf("вход дал другую комнату или пустой токен: %+v", joined)
	}

	// Выпал — и возвращается тем же эндпоинтом.
	if err := srv.Store.SetParticipantState(t.Context(), call.CallID, member.userID, store.PartLeft, time.Now()); err != nil {
		t.Fatal(err)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+call.CallID+"/join", member.token, nil, nil); code != 200 {
		t.Fatalf("повторный вход выпавшего: %d", code)
	}

	// Непричастному вход закрыт.
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+call.CallID+"/join", outsider.token, nil, nil); code != http.StatusForbidden {
		t.Fatalf("посторонний вошёл: %d", code)
	}

	// Завершённый звонок больше не пускает.
	if err := srv.Store.SetCallState(t.Context(), call.CallID, "ended"); err != nil {
		t.Fatal(err)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+call.CallID+"/join", member.token, nil, nil); code != http.StatusGone {
		t.Fatalf("вход в завершённый звонок: %d, ожидали 410", code)
	}
}

// В звонке один на один повторного входа нет: обрыв завершает его для обоих.
func TestDirectCallHasNoRejoin(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	a := registerDevice(t, ts, "+79990062001")
	b := registerDevice(t, ts, "+79990062002")

	var call struct {
		CallID string `json:"call_id"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/calls", a.token,
		map[string]any{"peer_id": b.userID, "kind": "audio"}, &call); code != 201 {
		t.Fatalf("звонок 1:1: %d", code)
	}
	// Участников у звонка 1:1 в новой таблице нет — вход отклоняется как «не приглашён».
	// Это тоже верный ответ: повторного входа нет ни при каком раскладе.
	code := jsonAuth(t, ts, "POST", "/api/v1/calls/"+call.CallID+"/join", b.token, nil, nil)
	if code != http.StatusConflict && code != http.StatusForbidden {
		t.Fatalf("повторный вход в звонок 1:1 разрешён: %d", code)
	}
	_ = srv
}

// livekitWebhookBody подписывает тело так же, как это делает LiveKit.
func livekitWebhookBody(t *testing.T, payload map[string]any) (body []byte, authHeader string) {
	t.Helper()
	body, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(body)
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"iss":    testLiveKitKey,
		"sha256": base64.StdEncoding.EncodeToString(sum[:]),
		"exp":    time.Now().Add(time.Minute).Unix(),
		"nbf":    time.Now().Add(-time.Minute).Unix(),
	})
	signed, err := tok.SignedString([]byte(testLiveKitSecret))
	if err != nil {
		t.Fatal(err)
	}
	return body, signed
}

func postWebhook(t *testing.T, ts *httptest.Server, body []byte, authHeader string) int {
	t.Helper()
	req, _ := http.NewRequest("POST", ts.URL+"/livekit/webhook", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/webhook+json")
	if authHeader != "" {
		req.Header.Set("Authorization", authHeader)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	return resp.StatusCode
}

func TestWebhookDrivesParticipantState(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990063001")
	member := registerDevice(t, ts, "+79990063002")
	groupID := createGroupWith(t, ts, owner, member)
	call, _ := startGroupCallAs(t, ts, owner, groupID)

	join := map[string]any{
		"event":       "participant_joined",
		"room":        map[string]any{"name": call.Room},
		"participant": map[string]any{"identity": member.userID + ":" + member.id},
		"createdAt":   time.Now().Unix(),
	}
	body, auth := livekitWebhookBody(t, join)
	if code := postWebhook(t, ts, body, auth); code != 200 {
		t.Fatalf("вебхук входа: %d", code)
	}
	active, err := srv.Store.ActiveParticipants(t.Context(), call.CallID)
	if err != nil {
		t.Fatal(err)
	}
	if len(active) != 1 || active[0] != member.userID {
		t.Fatalf("в комнате %v, ожидали только %s", active, member.userID)
	}

	left := map[string]any{
		"event":       "participant_left",
		"room":        map[string]any{"name": call.Room},
		"participant": map[string]any{"identity": member.userID + ":" + member.id},
		"createdAt":   time.Now().Unix(),
	}
	body, auth = livekitWebhookBody(t, left)
	if code := postWebhook(t, ts, body, auth); code != 200 {
		t.Fatalf("вебхук выхода: %d", code)
	}
	active, _ = srv.Store.ActiveParticipants(t.Context(), call.CallID)
	if len(active) != 0 {
		t.Fatalf("после выхода в комнате остались %v", active)
	}
	// В группе уход участника звонок НЕ завершает — остальные продолжают.
	parts, _ := srv.Store.CallParticipants(t.Context(), call.CallID)
	if parts[member.userID] != store.PartLeft {
		t.Fatalf("состояние вышедшего: %q", parts[member.userID])
	}
}

// Подпись вебхука — единственное, что отделяет наше состояние звонков от
// произвольного запроса из интернета.
func TestWebhookRejectsBadSignature(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	owner := registerDevice(t, ts, "+79990064001")
	member := registerDevice(t, ts, "+79990064002")
	groupID := createGroupWith(t, ts, owner, member)
	call, _ := startGroupCallAs(t, ts, owner, groupID)

	payload := map[string]any{
		"event":       "participant_joined",
		"room":        map[string]any{"name": call.Room},
		"participant": map[string]any{"identity": member.userID + ":" + member.id},
	}
	body, auth := livekitWebhookBody(t, payload)

	if code := postWebhook(t, ts, body, ""); code != http.StatusUnauthorized {
		t.Fatalf("без подписи: %d, ожидали 401", code)
	}
	if code := postWebhook(t, ts, body, "Bearer "+auth+"x"); code != http.StatusUnauthorized {
		t.Fatalf("испорченная подпись: %d, ожидали 401", code)
	}
	// ГЛАВНОЕ: подпись валидна, но тело подменено. Без сверки хэша тела
	// перехваченный токен годился бы для любого содержимого.
	// Копия обязательна: TrimSuffix возвращает срез ТОГО ЖЕ массива, и append
	// затёр бы последний байт исходного тела — дальнейшая проверка «корректная
	// пара проходит» падала бы на испорченном body.
	tampered := append(append([]byte{}, bytes.TrimSuffix(body, []byte("}"))...), []byte(`,"x":1}`)...)
	if code := postWebhook(t, ts, tampered, "Bearer "+auth); code != http.StatusUnauthorized {
		t.Fatalf("подменённое тело с валидным токеном: %d, ожидали 401", code)
	}
	// А корректная пара проходит.
	if code := postWebhook(t, ts, body, "Bearer "+auth); code != 200 {
		t.Fatalf("корректный вебхук отвергнут: %d", code)
	}
}

// Событие о чужой комнате — не ошибка: отвечаем 200, иначе LiveKit будет
// бесконечно ретраить то, что некуда применить.
func TestWebhookUnknownRoomIsAccepted(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	body, auth := livekitWebhookBody(t, map[string]any{
		"event": "participant_joined",
		"room":  map[string]any{"name": "call-посторонняя"},
	})
	if code := postWebhook(t, ts, body, "Bearer "+auth); code != 200 {
		t.Fatalf("чужая комната: %d, ожидали 200", code)
	}
	_ = srv
}
