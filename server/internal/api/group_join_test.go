package api

// Заявка на вступление (ADR-0018 п. 7, Г3): право просить = право видеть карточку,
// отказ виден просившему, принятие добавляет участника и двигает ключ.

import (
	"net/http"
	"testing"
)

// Полный путь: карточка → просьба → отказ виден → просьба снова → приняли.
func TestЗаявкаПутьЦеликом(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79997770001")
	знакомый := registerDevice(t, ts, "+79997770002")
	groupID := createGroupAPI(t, ts, owner.token)

	// пока карточку не передали — просить нечего и группы не существует
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/join-requests", знакомый.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("просьба без карточки — 404, получено %d", code)
	}

	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", owner.token,
		map[string]any{"user_ids": []string{знакомый.userID}}, nil); code != http.StatusOK {
		t.Fatalf("раздача карточки: %d", code)
	}

	var asked struct {
		State string `json:"state"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/join-requests", знакомый.token, nil, &asked); code != http.StatusCreated || asked.State != "pending" {
		t.Fatalf("просьба: код %d, состояние %q", code, asked.State)
	}

	// админ видит очередь
	var queue struct {
		Requests []struct {
			UserID string `json:"user_id"`
			State  string `json:"state"`
		} `json:"requests"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/join-requests", owner.token, nil, &queue); code != http.StatusOK {
		t.Fatalf("очередь админа: %d", code)
	}
	if len(queue.Requests) != 1 || queue.Requests[0].UserID != знакомый.userID {
		t.Fatalf("в очереди должна быть одна заявка, а там %+v", queue.Requests)
	}

	// отказ — и он виден просившему
	if code := authedJSON(t, ts, "PATCH", "/api/v1/groups/"+groupID+"/join-requests/"+знакомый.userID,
		owner.token, map[string]any{"accept": false}, nil); code != http.StatusOK {
		t.Fatalf("отказ: %d", code)
	}
	var mine struct {
		MyState string `json:"my_state"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/join-requests", знакомый.token, nil, &mine); code != http.StatusOK {
		t.Fatalf("своё состояние: %d", code)
	}
	if mine.MyState != "declined" {
		t.Fatalf("отказ должен быть виден просившему, а состояние %q", mine.MyState)
	}

	// попросить снова можно: отказ не вечен
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/join-requests", знакомый.token, nil, &asked); code != http.StatusCreated || asked.State != "pending" {
		t.Fatalf("повторная просьба: код %d, состояние %q", code, asked.State)
	}

	// принятие добавляет в состав
	if code := authedJSON(t, ts, "PATCH", "/api/v1/groups/"+groupID+"/join-requests/"+знакомый.userID,
		owner.token, map[string]any{"accept": true}, nil); code != http.StatusOK {
		t.Fatalf("принятие: %d", code)
	}
	var card struct {
		MyRole string `json:"my_role"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, знакомый.token, nil, &card); code != http.StatusOK || card.MyRole != "member" {
		t.Fatalf("после принятия человек — участник: код %d, роль %q", code, card.MyRole)
	}
}

// Повторная просьба не плодит строк в очереди админа.
func TestПовторнаяПросьбаНеПлодитЗаявок(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79997770001")
	знакомый := registerDevice(t, ts, "+79997770002")
	groupID := createGroupAPI(t, ts, owner.token)
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", owner.token,
		map[string]any{"user_ids": []string{знакомый.userID}}, nil); code != http.StatusOK {
		t.Fatalf("раздача: %d", code)
	}

	for i := 0; i < 3; i++ {
		if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/join-requests", знакомый.token, nil, nil); code != http.StatusCreated {
			t.Fatalf("просьба %d: %d", i, code)
		}
	}
	var queue struct {
		Requests []struct{} `json:"requests"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/join-requests", owner.token, nil, &queue); code != http.StatusOK {
		t.Fatalf("очередь: %d", code)
	}
	if len(queue.Requests) != 1 {
		t.Fatalf("три просьбы должны дать одну строку, а их %d", len(queue.Requests))
	}
}

// На заявки отвечает администрация; участник — нет. И участник не просится повторно.
func TestПраваНаОтветИПовторноеВступление(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79997770001")
	member := registerDevice(t, ts, "+79997770002")
	знакомый := registerDevice(t, ts, "+79997770003")
	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")

	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", owner.token,
		map[string]any{"user_ids": []string{знакомый.userID}}, nil); code != http.StatusOK {
		t.Fatalf("раздача: %d", code)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/join-requests", знакомый.token, nil, nil); code != http.StatusCreated {
		t.Fatalf("просьба: %d", code)
	}

	if code := authedJSON(t, ts, "PATCH", "/api/v1/groups/"+groupID+"/join-requests/"+знакомый.userID,
		member.token, map[string]any{"accept": true}, nil); code != http.StatusForbidden {
		t.Fatalf("участник не отвечает на заявки, получено %d", code)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/join-requests", member.token, nil, nil); code != http.StatusConflict {
		t.Fatalf("участник не просится второй раз, получено %d", code)
	}
}
