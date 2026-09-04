package api

// Карточка личной группы (ADR-0018, Г2): три состояния — не передавали, передали,
// участник. Плюс: описание группы это обычное сообщение уровня 0, а не особое поле.

import (
	"net/http"
	"testing"
)

// Личная группа: посторонний вне списка не знает о её существовании, получивший
// карточку видит название и сообщения уровня 0, участник — всё.
func TestКарточкаЛичнойГруппыТриСостояния(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79996660001")
	знакомый := registerDevice(t, ts, "+79996660002")
	посторонний := registerDevice(t, ts, "+79996660003")

	groupID := createGroupAPI(t, ts, owner.token)

	// описание группы — обычное сообщение уровня 0
	if code, _ := sendLeveled(t, ts, owner, groupID, "c0000000-0000-0000-0000-000000000001",
		levelPublicShowcase, []byte("разбираем релизы по вторникам")); code != http.StatusCreated {
		t.Fatalf("описание уровня 0: %d", code)
	}

	// 1. карточку не передавали — группы не существует
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, посторонний.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("посторонний должен получить 404, получено %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID+"/messages", посторонний.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("история постороннему — 404, получено %d", code)
	}

	// 2. владелец положил группу на страницу: карточка ушла контактам
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", owner.token,
		map[string]any{"user_ids": []string{знакомый.userID}}, nil); code != http.StatusOK {
		t.Fatalf("раздача карточки: %d", code)
	}

	var card struct {
		Title    string `json:"title"`
		IsMember bool   `json:"is_member"`
		CanAsk   bool   `json:"can_ask"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, знакомый.token, nil, &card); code != http.StatusOK {
		t.Fatalf("знакомый должен видеть карточку, получено %d", code)
	}
	if card.Title == "" || card.IsMember || !card.CanAsk {
		t.Fatalf("карточка не та: %+v", card)
	}
	if got := levelsOf(t, ts, знакомый.token, groupID); len(got) != 1 || got[0] != levelPublicShowcase {
		t.Fatalf("знакомому положено одно сообщение уровня 0, а видно %v", got)
	}

	// посторонний по-прежнему ничего не знает
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, посторонний.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("посторонний всё ещё 404, получено %d", code)
	}

	// 3. участник видит группу целиком, и ответ ему — ПРЕЖНИЙ, поле в поле:
	// существующий клиент не должен заметить, что карточка вообще появилась.
	var full struct {
		MyRole  string `json:"my_role"`
		OwnerID string `json:"owner_id"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, owner.token, nil, &full); code != http.StatusOK {
		t.Fatalf("владелец должен видеть группу: %d", code)
	}
	if full.MyRole != "owner" || full.OwnerID == "" {
		t.Fatalf("ответ участнику изменился: %+v", full)
	}
}

// Убрал группу со страницы — карточку перестают видеть. Список заменяется целиком.
func TestКарточкаУбираетсяПустымСписком(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79996660001")
	знакомый := registerDevice(t, ts, "+79996660002")
	groupID := createGroupAPI(t, ts, owner.token)

	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", owner.token,
		map[string]any{"user_ids": []string{знакомый.userID}}, nil); code != http.StatusOK {
		t.Fatalf("раздача: %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, знакомый.token, nil, nil); code != http.StatusOK {
		t.Fatalf("карточка должна быть видна: %d", code)
	}
	// пустой список — «убрал со страницы»
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", owner.token,
		map[string]any{"user_ids": []string{}}, nil); code != http.StatusOK {
		t.Fatalf("очистка списка: %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, знакомый.token, nil, nil); code != http.StatusNotFound {
		t.Fatalf("после очистки — 404, получено %d", code)
	}
}

// Публичная группа видна и без списка получателей: её находят поиском и каталогом.
func TestПубличнаяГруппаВиднаБезСписка(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79996660001")
	чужой := registerDevice(t, ts, "+79996660002")
	groupID := createPublicGroupAPI(t, ts, owner.token)

	if code, _ := sendLeveled(t, ts, owner, groupID, "c0000000-0000-0000-0000-000000000002",
		levelEveryone, []byte("всем")); code != http.StatusCreated {
		t.Fatalf("сообщение уровня 1: %d", code)
	}
	if code, _ := sendLeveled(t, ts, owner, groupID, "c0000000-0000-0000-0000-000000000003",
		levelMembers, []byte("вступившим")); code != http.StatusCreated {
		t.Fatalf("сообщение уровня 2: %d", code)
	}

	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, чужой.token, nil, nil); code != http.StatusOK {
		t.Fatalf("публичная карточка должна быть видна: %d", code)
	}
	got := levelsOf(t, ts, чужой.token, groupID)
	if len(got) != 1 || got[0] != levelEveryone {
		t.Fatalf("не-участнику публичной группы положен только уровень 1, видно %v", got)
	}
}

// Карточку раздаёт администрация, а не любой участник.
func TestРаздачаКарточкиТолькоАдминистрации(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79996660001")
	member := registerDevice(t, ts, "+79996660002")
	groupID := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")

	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", member.token,
		map[string]any{"user_ids": []string{member.userID}}, nil); code != http.StatusForbidden {
		t.Fatalf("участник не раздаёт карточку, получено %d", code)
	}
}

// Незнакомые сервер идентификаторы в списке — не ошибка: в книге контактов есть номера,
// которых в TIMA нет.
func TestНеизвестныеПолучателиНеЛомаютРаздачу(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79996660001")
	знакомый := registerDevice(t, ts, "+79996660002")
	groupID := createGroupAPI(t, ts, owner.token)

	var resp struct {
		Count int `json:"count"`
	}
	body := map[string]any{"user_ids": []string{
		знакомый.userID,
		"00000000-0000-0000-0000-0000000000ff", // такого пользователя нет
	}}
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/audience", owner.token, body, &resp); code != http.StatusOK {
		t.Fatalf("раздача с неизвестным получателем: %d", code)
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/"+groupID, знакомый.token, nil, nil); code != http.StatusOK {
		t.Fatalf("знакомый всё равно должен видеть карточку: %d", code)
	}
}

// Вкладка «Друзья»: карточки, которые мне открыли. Своих групп там нет.
func TestСписокКарточекБезСвоихГрупп(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79996660001")
	знакомый := registerDevice(t, ts, "+79996660002")

	чужая := createGroupAPI(t, ts, owner.token)
	своя := createGroupAPI(t, ts, знакомый.token)

	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+чужая+"/audience", owner.token,
		map[string]any{"user_ids": []string{знакомый.userID}}, nil); code != http.StatusOK {
		t.Fatalf("раздача: %d", code)
	}

	var list struct {
		Cards []struct {
			GroupID string `json:"group_id"`
		} `json:"cards"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/cards", знакомый.token, nil, &list); code != http.StatusOK {
		t.Fatalf("список карточек: %d", code)
	}
	if len(list.Cards) != 1 || list.Cards[0].GroupID != чужая {
		t.Fatalf("в карточках должна быть одна чужая группа, а там %+v (своя: %s)", list.Cards, своя)
	}

	// вступил — карточка ушла из списка сама
	addMemberAPI(t, ts, owner.token, чужая, знакомый.userID, "member")
	if code := authedJSON(t, ts, "GET", "/api/v1/groups/cards", знакомый.token, nil, &list); code != http.StatusOK {
		t.Fatalf("список после вступления: %d", code)
	}
	if len(list.Cards) != 0 {
		t.Fatalf("после вступления карточка должна уйти из «Друзей», а там %+v", list.Cards)
	}
}
