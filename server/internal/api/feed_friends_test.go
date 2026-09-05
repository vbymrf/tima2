package api

// Дружба как подписка на ленту (ПЛАН-КОНТАКТОВ.md, Д1б — пересмотр 2026-09-05).
//
// Проверяется главное: доступ к ленте даёт ВЛАДЕЛЕЦ, а не читатель, и дружба
// асимметрична — я вижу твою ленту, ты мою можешь не видеть. Уровни при этом те же, что
// в группе: не друг видит «всем», друг — до «своим».

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func открытьЛенту(t *testing.T, ts *httptest.Server, token, userID string) int {
	t.Helper()
	return authedJSON(t, ts, "POST", "/api/v1/users/me/feed/subscribers", token,
		map[string]any{"user_id": userID}, nil)
}

func закрытьЛенту(t *testing.T, ts *httptest.Server, token, userID string) int {
	t.Helper()
	return authedJSON(t, ts, "DELETE", "/api/v1/users/me/feed/subscribers/"+userID, token, nil, nil)
}

func комуОткрыта(t *testing.T, ts *httptest.Server, token string) []string {
	t.Helper()
	var resp struct {
		Subscribers []string `json:"subscribers"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me/feed/subscribers", token, nil, &resp); code != http.StatusOK {
		t.Fatalf("список подписчиков: %d", code)
	}
	return resp.Subscribers
}

// чужаяЛента — записи и признак «владелец дружит со мной».
func чужаяЛента(t *testing.T, ts *httptest.Server, token, ownerID string) (int, int, bool) {
	t.Helper()
	var resp struct {
		Items  []feedItem `json:"items"`
		Friend bool       `json:"friend"`
	}
	code := authedJSON(t, ts, "GET", "/api/v1/users/"+ownerID+"/feed", token, nil, &resp)
	return code, len(resp.Items), resp.Friend
}

// положиНаСтраницу — открытая запись и запись «своим».
func положиНаСтраницу(t *testing.T, ts *httptest.Server, автор *device, id1, id2 string) {
	t.Helper()
	groupID := createPublicGroupAPI(t, ts, автор.token)
	всем := sendOne(t, ts, автор, groupID, id1, levelEveryone)
	своим := sendOne(t, ts, автор, groupID, id2, levelMembers)
	if code, _ := carry(t, ts, автор.token, groupID, всем, levelEveryone); code != http.StatusCreated {
		t.Fatalf("положить открытое: %d", code)
	}
	if code, _ := carry(t, ts, автор.token, groupID, своим, levelMembers); code != http.StatusCreated {
		t.Fatalf("положить «своим»: %d", code)
	}
}

func TestДоступКЛентеДаётВладелец(t *testing.T) {
	ts, _ := setup(t)
	анна := registerDevice(t, ts, "+79990000051")
	пётр := registerDevice(t, ts, "+79990000052")
	положиНаСтраницу(t, ts, анна,
		"cccccccc-0000-0000-0000-000000000001",
		"cccccccc-0000-0000-0000-000000000002")

	// Пётр открыл страницу Анны — и это его подписчиком НЕ делает: прежде так
	// становился своим любой прохожий.
	code, сколько, друг := чужаяЛента(t, ts, пётр.token, анна.userID)
	if code != http.StatusOK {
		t.Fatalf("чужая лента: %d", code)
	}
	if сколько != 1 {
		t.Fatalf("посторонний видит %d записей, ожидалась одна («всем»)", сколько)
	}
	if друг {
		t.Fatalf("чтение страницы сделало читателя другом")
	}
	if кому := комуОткрыта(t, ts, анна.token); len(кому) != 0 {
		t.Fatalf("чтение добавило подписчика: %v", кому)
	}

	// Анна открывает ему ленту — и только теперь он видит «своим».
	if code := открытьЛенту(t, ts, анна.token, пётр.userID); code != http.StatusCreated {
		t.Fatalf("открыть ленту: %d", code)
	}
	_, сколько, друг = чужаяЛента(t, ts, пётр.token, анна.userID)
	if сколько != 2 || !друг {
		t.Fatalf("друг видит %d записей, друг=%v", сколько, друг)
	}
}

func TestДружбаАсимметрична(t *testing.T) {
	ts, _ := setup(t)
	анна := registerDevice(t, ts, "+79990000053")
	пётр := registerDevice(t, ts, "+79990000054")
	положиНаСтраницу(t, ts, анна,
		"cccccccc-0000-0000-0000-000000000003",
		"cccccccc-0000-0000-0000-000000000004")
	положиНаСтраницу(t, ts, пётр,
		"cccccccc-0000-0000-0000-000000000005",
		"cccccccc-0000-0000-0000-000000000006")

	// Анна дружит с Петром, Пётр с Анной — нет.
	if code := открытьЛенту(t, ts, анна.token, пётр.userID); code != http.StatusCreated {
		t.Fatalf("Анна открыла ленту: %d", code)
	}

	if _, сколько, друг := чужаяЛента(t, ts, пётр.token, анна.userID); сколько != 2 || !друг {
		t.Fatalf("Пётр у Анны: %d записей, друг=%v", сколько, друг)
	}
	// Обратное направление осталось закрытым: дружба односторонняя.
	if _, сколько, друг := чужаяЛента(t, ts, анна.token, пётр.userID); сколько != 1 || друг {
		t.Fatalf("Анна у Петра: %d записей, друг=%v — дружба оказалась взаимной", сколько, друг)
	}
}

func TestЗакрылЛентуУзкоеПропало(t *testing.T) {
	ts, _ := setup(t)
	анна := registerDevice(t, ts, "+79990000055")
	пётр := registerDevice(t, ts, "+79990000056")
	положиНаСтраницу(t, ts, анна,
		"cccccccc-0000-0000-0000-000000000007",
		"cccccccc-0000-0000-0000-000000000008")

	if code := открытьЛенту(t, ts, анна.token, пётр.userID); code != http.StatusCreated {
		t.Fatalf("открыть: %d", code)
	}
	if code := закрытьЛенту(t, ts, анна.token, пётр.userID); code != http.StatusNoContent {
		t.Fatalf("закрыть: %d", code)
	}
	if кому := комуОткрыта(t, ts, анна.token); len(кому) != 0 {
		t.Fatalf("подписчик остался: %v", кому)
	}
	// Открытое видно и постороннему: закрылось именно «своим», а не лента целиком.
	if _, сколько, друг := чужаяЛента(t, ts, пётр.token, анна.userID); сколько != 1 || друг {
		t.Fatalf("после закрытия: %d записей, друг=%v", сколько, друг)
	}
}

func TestСвойСписокПодписчиковНеЧужой(t *testing.T) {
	ts, _ := setup(t)
	анна := registerDevice(t, ts, "+79990000057")
	пётр := registerDevice(t, ts, "+79990000058")
	игорь := registerDevice(t, ts, "+79990000059")

	if code := открытьЛенту(t, ts, анна.token, игорь.userID); code != http.StatusCreated {
		t.Fatalf("открыть: %d", code)
	}
	if кому := комуОткрыта(t, ts, анна.token); len(кому) != 1 || кому[0] != игорь.userID {
		t.Fatalf("свой список: %v", кому)
	}
	// У Петра свой и пустой: чужого списка друзей на сервере не спрашивают вовсе —
	// по одному знакомому иначе раскручивается круг общения человека.
	if кому := комуОткрыта(t, ts, пётр.token); len(кому) != 0 {
		t.Fatalf("чужие подписчики попали в свой список: %v", кому)
	}
}

func TestСебеЛентуОткрыватьНеНужно(t *testing.T) {
	ts, _ := setup(t)
	анна := registerDevice(t, ts, "+79990000060")

	if code := открытьЛенту(t, ts, анна.token, анна.userID); code != http.StatusBadRequest {
		t.Fatalf("себя добавили в подписчики: %d", code)
	}
}
