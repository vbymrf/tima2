package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

// Девятый–одиннадцатый круг Социума (ПЛАН-(СЦ) СЦ18–СЦ23, заказчик 2026-11-23).

type subscribersReply struct {
	Subscribers []string `json:"subscribers"`
	OwnerID     string   `json:"owner_id"`
	Moderators  []string `json:"moderators"`
	Admins      []string `json:"admins"`
	Banned      []string `json:"banned"`
}

// newChannel — канал владельца; подписывает перечисленных.
func newChannel(t *testing.T, ts *httptest.Server, owner *device, subs ...*device) string {
	t.Helper()
	var ch struct {
		ChannelID string `json:"channel_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/channels", map[string]any{"title": "Новости"}, &ch); code != 201 {
		t.Fatalf("канал: %d", code)
	}
	for _, d := range subs {
		if code := postAuthed(t, ts, d.token, "POST", "/api/v1/channels/"+ch.ChannelID+"/subscribe", nil, nil); code/100 != 2 {
			t.Fatalf("подписка: %d", code)
		}
	}
	return ch.ChannelID
}

// СЦ21: админ канала — назначает модераторов, но не админов; публикуют владелец, админ, модератор.
func TestАдминКанала(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199001")
	admin := registerDevice(t, ts, "+79990199002")
	mod := registerDevice(t, ts, "+79990199003")
	reader := registerDevice(t, ts, "+79990199004")
	other := registerDevice(t, ts, "+79990199005")
	ch := newChannel(t, ts, owner, admin, mod, reader, other)
	base := "/api/v1/channels/" + ch

	if code := postAuthed(t, ts, admin.token, "POST", base+"/moderators", map[string]string{"user_id": mod.userID}, nil); code != 403 {
		t.Fatalf("не админ назначил модератора: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", base+"/moderators", map[string]string{"user_id": admin.userID, "role": "admin"}, nil); code != 201 {
		t.Fatalf("владелец не назначил админа: %d", code)
	}
	if code := postAuthed(t, ts, admin.token, "POST", base+"/moderators", map[string]string{"user_id": mod.userID}, nil); code != 201 {
		t.Fatalf("админ не назначил модератора: %d", code)
	}
	if code := postAuthed(t, ts, admin.token, "POST", base+"/moderators", map[string]string{"user_id": other.userID, "role": "admin"}, nil); code != 403 {
		t.Fatalf("админ назначил админа: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "POST", base+"/moderators", map[string]string{"user_id": other.userID}, nil); code != 403 {
		t.Fatalf("модератор назначил модератора: %d", code)
	}
	var mine struct {
		Channels []map[string]any `json:"channels"`
	}
	if code := getAuthed(t, ts, admin.token, "/api/v1/channels", &mine); code != 200 || len(mine.Channels) != 1 ||
		mine.Channels[0]["admin"] != true || mine.Channels[0]["moderator"] != true {
		t.Fatalf("мои каналы админа: %d %+v", code, mine.Channels)
	}
	var team struct {
		Moderators []string `json:"moderators"`
		Admins     []string `json:"admins"`
	}
	if code := getAuthed(t, ts, reader.token, base+"/moderators", &team); code != 200 || !listHas(team.Moderators, mod.userID) ||
		!listHas(team.Moderators, admin.userID) || !listHas(team.Admins, admin.userID) || listHas(team.Admins, mod.userID) {
		t.Fatalf("команда: %d %+v", code, team)
	}
	for _, d := range []*device{owner, admin, mod} {
		if code := postAuthed(t, ts, d.token, "POST", base+"/posts", map[string]any{"text": "запись"}, nil); code != 201 {
			t.Fatalf("команда не публикует: %d", code)
		}
	}
	if code := postAuthed(t, ts, reader.token, "POST", base+"/posts", map[string]any{"text": "запись"}, nil); code != 403 {
		t.Fatalf("подписчик опубликовал: %d", code)
	}
	// «Основное» и «Показывать участников» — владелец и админ, не модератор.
	if code := postAuthed(t, ts, admin.token, "PUT", base+"/show-members", map[string]any{"show": false}, nil); code != 200 {
		t.Fatalf("админ не сменил настройку: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "PUT", base+"/show-members", map[string]any{"show": true}, nil); code != 403 {
		t.Fatalf("модератор сменил настройку: %d", code)
	}
	if code := postAuthed(t, ts, admin.token, "DELETE", base+"/moderators/"+mod.userID, nil, nil); code != 204 {
		t.Fatalf("админ не снял модератора: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "DELETE", base+"/moderators/"+admin.userID, nil, nil); code != 403 {
		t.Fatalf("бывший модератор снял админа: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "POST", base+"/posts", map[string]any{"text": "запись"}, nil); code != 403 {
		t.Fatalf("снятый модератор публикует: %d", code)
	}
}

// СЦ19, СЦ21: подписчик видит только список при «Показывать участников»; «Исключить» и «Забанить».
func TestПодписчикиКаналаИБан(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199011")
	mod := registerDevice(t, ts, "+79990199012")
	reader := registerDevice(t, ts, "+79990199013")
	other := registerDevice(t, ts, "+79990199014")
	ch := newChannel(t, ts, owner, mod, reader, other)
	base := "/api/v1/channels/" + ch
	if code := postAuthed(t, ts, owner.token, "POST", base+"/moderators", map[string]string{"user_id": mod.userID}, nil); code != 201 {
		t.Fatalf("модератор: %d", code)
	}

	var list subscribersReply
	if code := getAuthed(t, ts, reader.token, base+"/subscribers", &list); code != 200 || !listHas(list.Subscribers, other.userID) || list.OwnerID != "" {
		t.Fatalf("подписчик при включённой настройке: %d %+v", code, list)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", base+"/show-members", map[string]any{"show": false}, nil); code != 200 {
		t.Fatalf("выключить: %d", code)
	}
	if code := getAuthed(t, ts, reader.token, base+"/subscribers", &list); code != 403 {
		t.Fatalf("подписчик при выключенной: %d", code)
	}
	var show struct {
		Show map[string]bool `json:"show"`
	}
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/entities/show-members", map[string]any{"kind": "channel", "ids": []string{ch}}, &show); code != 200 || show.Show[ch] {
		t.Fatalf("настройка пачкой: %d %+v", code, show)
	}

	// Исключённый подписывается снова; забаненный — нет, пока не снимут.
	if code := postAuthed(t, ts, mod.token, "DELETE", base+"/subscribers/"+other.userID, nil, nil); code != 204 {
		t.Fatalf("исключить: %d", code)
	}
	if code := postAuthed(t, ts, other.token, "POST", base+"/subscribe", nil, nil); code/100 != 2 {
		t.Fatalf("исключённый не подписался: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "POST", base+"/subscribers/"+reader.userID+"/ban", nil, nil); code != 204 {
		t.Fatalf("забанить: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", base+"/subscribe", nil, nil); code != 403 {
		t.Fatalf("забаненный подписался: %d", code)
	}
	list = subscribersReply{}
	if code := getAuthed(t, ts, mod.token, base+"/subscribers", &list); code != 200 || listHas(list.Subscribers, reader.userID) || !listHas(list.Banned, reader.userID) {
		t.Fatalf("список команды: %d %+v", code, list)
	}
	if code := postAuthed(t, ts, reader.token, "POST", base+"/subscribers/"+other.userID+"/ban", nil, nil); code != 403 {
		t.Fatalf("подписчик забанил: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "DELETE", base+"/subscribers/"+reader.userID+"/ban", nil, nil); code != 204 {
		t.Fatalf("снять бан: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", base+"/subscribe", nil, nil); code/100 != 2 {
		t.Fatalf("после снятия бана не подписался: %d", code)
	}
	if code := postAuthed(t, ts, mod.token, "DELETE", base+"/subscribers/"+owner.userID, nil, nil); code != 403 {
		t.Fatalf("исключили владельца: %d", code)
	}
}

// СЦ23: «Исключить» и «Забанить» в сообществе наследуются на каналы и группы внутри.
func TestБанСообществаНаследуется(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990199021")
	admin := registerDevice(t, ts, "+79990199022")
	reader := registerDevice(t, ts, "+79990199023")
	community := makeCommunity(t, ts, owner, "Ядро")
	cbase := "/api/v1/communities/" + community
	if code := postAuthed(t, ts, owner.token, "POST", cbase+"/admins", map[string]string{"user_id": admin.userID}, nil); code/100 != 2 {
		t.Fatalf("админ сообщества: %d", code)
	}
	ch := newChannel(t, ts, owner, reader)
	groupID := createPublicGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, reader.userID, "member")
	for _, it := range []map[string]any{{"kind": "channel", "id": ch}, {"kind": "group", "id": groupID}} {
		if code := postAuthed(t, ts, owner.token, "POST", cbase+"/items", it, nil); code != 201 {
			t.Fatalf("элемент: %d", code)
		}
	}
	if code := postAuthed(t, ts, reader.token, "POST", cbase+"/subscribe", nil, nil); code/100 != 2 {
		t.Fatalf("подписка на сообщество: %d", code)
	}
	var list subscribersReply
	if code := getAuthed(t, ts, admin.token, cbase+"/subscribers", &list); code != 200 || !listHas(list.Subscribers, reader.userID) || !listHas(list.Admins, admin.userID) {
		t.Fatalf("подписчики сообщества: %d %+v", code, list)
	}
	if code := postAuthed(t, ts, admin.token, "DELETE", cbase+"/subscribers/"+owner.userID, nil, nil); code != 403 {
		t.Fatalf("исключили владельца сообщества: %d", code)
	}

	// Исключение снимает и с канала, и с группы внутри.
	if code := postAuthed(t, ts, admin.token, "DELETE", cbase+"/subscribers/"+reader.userID, nil, nil); code != 204 {
		t.Fatalf("исключить из сообщества: %d", code)
	}
	ctx := context.Background()
	if sub, _ := srv.Store.IsSubscribed(ctx, ch, reader.userID); sub {
		t.Fatal("исключённый остался подписан на канал сообщества")
	}
	if role, _ := srv.Store.GroupRole(ctx, groupID, reader.userID); role != "" {
		t.Fatalf("исключённый остался в группе сообщества: %q", role)
	}

	// Бан закрывает вход и в элементы.
	if code := postAuthed(t, ts, admin.token, "POST", cbase+"/subscribers/"+reader.userID+"/ban", nil, nil); code != 204 {
		t.Fatalf("забанить в сообществе: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", cbase+"/subscribe", nil, nil); code != 403 {
		t.Fatalf("забаненный подписался на сообщество: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/channels/"+ch+"/subscribe", nil, nil); code != 403 {
		t.Fatalf("забаненный в сообществе подписался на его канал: %d", code)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/groups/"+groupID+"/members", owner.token,
		map[string]any{"user_id": reader.userID, "role": "member"}, nil); code != 403 {
		t.Fatalf("забаненного в сообществе добавили в его группу: %d", code)
	}
	if code := postAuthed(t, ts, admin.token, "DELETE", cbase+"/subscribers/"+reader.userID+"/ban", nil, nil); code != 204 {
		t.Fatalf("снять бан: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", "/api/v1/channels/"+ch+"/subscribe", nil, nil); code/100 != 2 {
		t.Fatalf("после снятия бана не подписался на канал: %d", code)
	}
}

// СЦ18: уровень 0 и суперсообщение — владелец, админ, модератор; у сообщества — правка и удаление.
func TestОписаниеПишетКоманда(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199031")
	mod := registerDevice(t, ts, "+79990199032")
	member := registerDevice(t, ts, "+79990199033")
	groupID := createPublicGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, groupID, mod.userID, "moderator")
	addMemberAPI(t, ts, owner.token, groupID, member.userID, "member")
	if code, _ := sendLeveled(t, ts, member, groupID, "eeeeeeee-5555-0000-0000-000000000001", 0, []byte("x")); code != http.StatusForbidden {
		t.Fatalf("участник написал уровень 0: %d", code)
	}
	code, resp := sendLeveled(t, ts, mod, groupID, "eeeeeeee-5555-0000-0000-000000000002", 0, []byte("x"))
	if code != http.StatusCreated {
		t.Fatalf("модератор не написал уровень 0: %d", code)
	}
	var n int64
	_ = json.Unmarshal(resp["message_id"], &n)
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/showcase/super", mod.token, map[string]any{"message_id": n}, nil); code != http.StatusOK {
		t.Fatalf("модератор не поставил суперсообщение: %d", code)
	}
	if code := authedJSON(t, ts, "PUT", "/api/v1/groups/"+groupID+"/showcase/super", member.token, map[string]any{"message_id": n}, nil); code != http.StatusForbidden {
		t.Fatalf("участник поставил суперсообщение: %d", code)
	}

	// Сообщество: админ пишет, правит своё, владелец удаляет; удалённое суперсообщение снимается.
	admin := registerDevice(t, ts, "+79990199034")
	community := makeCommunity(t, ts, owner, "Ядро")
	cbase := "/api/v1/communities/" + community
	if code := postAuthed(t, ts, owner.token, "POST", cbase+"/admins", map[string]string{"user_id": admin.userID}, nil); code/100 != 2 {
		t.Fatalf("админ: %d", code)
	}
	var created struct {
		MessageID int64 `json:"message_id"`
	}
	if code := postAuthed(t, ts, admin.token, "POST", cbase+"/messages", map[string]any{"text": "правила"}, &created); code != 201 {
		t.Fatalf("сообщение сообщества: %d", code)
	}
	path := cbase + "/messages/" + strconv.FormatInt(created.MessageID, 10)
	if code := postAuthed(t, ts, owner.token, "PATCH", path, map[string]any{"text": "чужая правка"}, nil); code != 403 {
		t.Fatalf("правка чужого: %d", code)
	}
	if code := postAuthed(t, ts, admin.token, "PATCH", path, map[string]any{"text": "правила 2"}, nil); code != 204 {
		t.Fatalf("правка своего: %d", code)
	}
	if code := authedJSON(t, ts, "PUT", cbase+"/showcase/super", admin.token, map[string]any{"message_id": created.MessageID}, nil); code != http.StatusOK {
		t.Fatalf("админ не поставил суперсообщение сообщества: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "DELETE", path, nil, nil); code != 204 {
		t.Fatalf("удаление: %d", code)
	}
	var sc struct {
		Super    int64            `json:"super"`
		Messages []map[string]any `json:"messages"`
	}
	if code := getAuthed(t, ts, member.token, cbase+"/showcase", &sc); code != 200 || sc.Super != 0 || len(sc.Messages) != 0 {
		t.Fatalf("после удаления: %d %+v", code, sc)
	}
}

// СЦ22: запись канала с телом документом и без текста; правка — только автор. Медленный режим комментариев.
func TestЗаписьКаналаТеломИМедленныйРежим(t *testing.T) {
	ts, _ := setup(t)
	owner := registerDevice(t, ts, "+79990199041")
	reader := registerDevice(t, ts, "+79990199042")
	ch := newChannel(t, ts, owner, reader)
	base := "/api/v1/channels/" + ch
	body := base64.RawURLEncoding.EncodeToString([]byte{0x0a, 0x03, 'a', 'b', 'c'})
	var created struct {
		PostID uint64 `json:"post_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", base+"/posts", map[string]any{"text": "", "body": body}, &created); code != 201 {
		t.Fatalf("запись из одного тела: %d", code)
	}
	var feed struct {
		Posts []map[string]any `json:"posts"`
	}
	if code := getAuthed(t, ts, reader.token, base+"/posts", &feed); code != 200 || len(feed.Posts) != 1 || feed.Posts[0]["body"] != body {
		t.Fatalf("лента с телом: %d %+v", code, feed.Posts)
	}
	path := base + "/posts/" + strconv.FormatUint(created.PostID, 10)
	if code := postAuthed(t, ts, reader.token, "PATCH", path, map[string]any{"text": "чужое"}, nil); code != 403 {
		t.Fatalf("правка чужой записи: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PATCH", path, map[string]any{"text": "исправлено"}, nil); code != 204 {
		t.Fatalf("правка своей записи: %d", code)
	}

	if code := postAuthed(t, ts, reader.token, "PUT", base+"/slow-mode", map[string]any{"sec": 60}, nil); code != 403 {
		t.Fatalf("подписчик поставил медленный режим: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "PUT", base+"/slow-mode", map[string]any{"sec": 60}, nil); code != 200 {
		t.Fatalf("медленный режим: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", path+"/comments", map[string]any{"text": "первый"}, nil); code != 201 {
		t.Fatalf("первый комментарий: %d", code)
	}
	if code := postAuthed(t, ts, reader.token, "POST", path+"/comments", map[string]any{"text": "второй"}, nil); code != http.StatusTooManyRequests {
		t.Fatalf("второй комментарий в медленном режиме: %d", code)
	}
	if code := postAuthed(t, ts, owner.token, "POST", path+"/comments", map[string]any{"text": "владелец не ждёт"}, nil); code != 201 {
		t.Fatalf("комментарий владельца: %d", code)
	}
}
