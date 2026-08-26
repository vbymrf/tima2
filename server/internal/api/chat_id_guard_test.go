package api

import (
	"net/http"
	"testing"
)

// Посторонний не кладёт сообщение в чужую переписку.
//
// **Почему это вообще возможно было.** Идентификатор личной переписки сервер не
// назначает: клиент считает его из двух user_id. Оба user_id отдаёт справочник, чужие
// публичные ключи — /keys/devices. Значит посторонний мог собрать конверт, завёрнутый
// на устройства двух чужих людей, и указать chat_id ИХ переписки. Прочитать её он и
// так не мог, подделать отправителя не давала подпись, — но его сообщение появлялось
// бы в чужой ветке.
//
// Проверка не про шифрование: она про то, что адрес назначения обязан следовать из
// участников, а не сообщаться отправителем.
func TestChatIDОбязанСледоватьИзУчастников(t *testing.T) {
	ts, _ := setup(t)
	a := registerDevice(t, ts, "+79993330011")
	b := registerDevice(t, ts, "+79993330012")
	чужак := registerDevice(t, ts, "+79993330013")

	// Чужак заворачивает ключ на устройство Б — то есть Б это сообщение прочтёт, —
	// но кладёт его в переписку А и Б.
	env := sealEnvelopeTo(t, чужак, []*device{b}, personalChatID(a.userID, b.userID),
		920001, []byte("подкинуто"))
	resp := post(t, ts, env, чужак.token, "cccccccc-0000-0000-0000-000000000001")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("вставка в чужую переписку прошла: %d", resp.StatusCode)
	}

	// Своя переписка с Б у чужака работает — проверка не запрещает писать незнакомым,
	// она запрещает выбирать чужой адрес.
	own := sealEnvelope(t, чужак, []*device{b}, 920002, []byte("своё"))
	ok := post(t, ts, own, чужак.token, "cccccccc-0000-0000-0000-000000000002")
	defer ok.Body.Close()
	if ok.StatusCode != http.StatusCreated {
		t.Fatalf("честное сообщение отвергнуто: %d", ok.StatusCode)
	}
}

// Конверт на устройства нескольких разных людей — это уже не личная переписка.
func TestНесколькоСобеседниковОтвергается(t *testing.T) {
	ts, _ := setup(t)
	a := registerDevice(t, ts, "+79993330021")
	b := registerDevice(t, ts, "+79993330022")
	c := registerDevice(t, ts, "+79993330023")

	env := sealEnvelopeTo(t, a, []*device{b, c}, personalChatID(a.userID, b.userID),
		920003, []byte("троим"))
	resp := post(t, ts, env, a.token, "cccccccc-0000-0000-0000-000000000003")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("конверт на двоих чужих принят: %d", resp.StatusCode)
	}
}
