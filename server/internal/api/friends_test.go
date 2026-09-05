package api

// Друзья (ПЛАН-КОНТАКТОВ.md, Д1б).
//
// Проверяется не «ручка отвечает 201», а четыре обещания: список свой и чужому не
// виден, добавление сразу открывает ленту, удаление её закрывает, и повторное
// нажатие ничего не ломает. Первое из них — единственное, что отделяет список
// друзей от социального справочника.

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func добавитьДруга(t *testing.T, ts *httptest.Server, token, userID string) int {
	t.Helper()
	return authedJSON(t, ts, "POST", "/api/v1/users/me/friends", token,
		map[string]any{"user_id": userID}, nil)
}

func убратьДруга(t *testing.T, ts *httptest.Server, token, userID string) int {
	t.Helper()
	return authedJSON(t, ts, "DELETE", "/api/v1/users/me/friends/"+userID, token, nil, nil)
}

func друзья(t *testing.T, ts *httptest.Server, token string) []string {
	t.Helper()
	var resp struct {
		Friends []string `json:"friends"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me/friends", token, nil, &resp); code != http.StatusOK {
		t.Fatalf("список друзей: %d", code)
	}
	return resp.Friends
}

func TestДругДобавляетсяИПодписываетНаЛенту(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000021")
	анна := registerDevice(t, ts, "+79990000022")

	if code := добавитьДруга(t, ts, пётр.token, анна.userID); code != http.StatusCreated {
		t.Fatalf("добавить друга: %d", code)
	}
	список := друзья(t, ts, пётр.token)
	if len(список) != 1 || список[0] != анна.userID {
		t.Fatalf("список друзей: %v", список)
	}
	// Подписка — то, ради чего друзья и заводятся: лента Анны читается без
	// отдельного действия.
	var лента struct {
		Items []feedItem `json:"items"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/"+анна.userID+"/feed", пётр.token, nil, &лента); code != http.StatusOK {
		t.Fatalf("лента друга не читается: %d", code)
	}
}

func TestЧужойСписокДрузейНеВиден(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000023")
	анна := registerDevice(t, ts, "+79990000024")
	игорь := registerDevice(t, ts, "+79990000025")

	if code := добавитьДруга(t, ts, пётр.token, игорь.userID); code != http.StatusCreated {
		t.Fatalf("добавить: %d", code)
	}
	// У Анны свой список, и он пуст: ручка «мои друзья» отвечает про
	// спрашивающего, а чужого списка нет вовсе — по одному знакомому иначе
	// раскручивался бы круг общения человека.
	if список := друзья(t, ts, анна.token); len(список) != 0 {
		t.Fatalf("чужие друзья попали в свой список: %v", список)
	}
}

func TestДругВидитСвоёАПостороннийНет(t *testing.T) {
	ts, _ := setup(t)
	анна := registerDevice(t, ts, "+79990000026")
	пётр := registerDevice(t, ts, "+79990000027")

	// Анна кладёт на страницу две записи: открытую и «своим».
	groupID := createPublicGroupAPI(t, ts, анна.token)
	всем := sendOne(t, ts, анна, groupID, "bbbbbbbb-0000-0000-0000-000000000001", levelEveryone)
	своим := sendOne(t, ts, анна, groupID, "bbbbbbbb-0000-0000-0000-000000000002", levelMembers)
	if code, _ := carry(t, ts, анна.token, groupID, всем, levelEveryone); code != http.StatusCreated {
		t.Fatalf("положить открытое: %d", code)
	}
	if code, _ := carry(t, ts, анна.token, groupID, своим, levelMembers); code != http.StatusCreated {
		t.Fatalf("положить «своим»: %d", code)
	}

	// Посторонний видит одну запись — открытую.
	if items := feedOf(t, ts, пётр.token, "/api/v1/users/"+анна.userID+"/feed"); len(items) != 1 {
		t.Fatalf("посторонний видит %d записей, ожидалась одна", len(items))
	}

	// Анна добавляет Петра в друзья — и он становится «своим» на её странице.
	if code := добавитьДруга(t, ts, анна.token, пётр.userID); code != http.StatusCreated {
		t.Fatalf("добавить: %d", code)
	}
	if items := feedOf(t, ts, пётр.token, "/api/v1/users/"+анна.userID+"/feed"); len(items) != 2 {
		t.Fatalf("друг видит %d записей, ожидались две", len(items))
	}

	// Убрали из друзей — узкое закрылось снова.
	if code := убратьДруга(t, ts, анна.token, пётр.userID); code != http.StatusNoContent {
		t.Fatalf("убрать: %d", code)
	}
	if список := друзья(t, ts, анна.token); len(список) != 0 {
		t.Fatalf("друг остался в списке: %v", список)
	}
	if items := feedOf(t, ts, пётр.token, "/api/v1/users/"+анна.userID+"/feed"); len(items) != 1 {
		t.Fatalf("после удаления из друзей видно %d записей, ожидалась одна", len(items))
	}
}

func TestПовторноеДобавлениеНеПлодитСтрок(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000028")
	анна := registerDevice(t, ts, "+79990000029")

	for i := 0; i < 3; i++ {
		if code := добавитьДруга(t, ts, пётр.token, анна.userID); code != http.StatusCreated {
			t.Fatalf("добавление %d: %d", i, code)
		}
	}
	if список := друзья(t, ts, пётр.token); len(список) != 1 {
		t.Fatalf("трижды добавленный друг: %v", список)
	}
}

func TestСебяВДрузьяНеДобавить(t *testing.T) {
	ts, _ := setup(t)
	пётр := registerDevice(t, ts, "+79990000030")

	if code := добавитьДруга(t, ts, пётр.token, пётр.userID); code != http.StatusBadRequest {
		t.Fatalf("себя добавили в друзья: %d", code)
	}
}
