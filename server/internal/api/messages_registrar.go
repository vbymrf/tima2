package api

import (
	"context"
	"net/http"

	"tima/server/internal/store"
)

// Личные сообщения и состояния переписки — шаг 4, восьмая группа.
//
// Вынесена последней намеренно: postMessage самый связанный handler сервера —
// разбор конверта, проверка размеров, сверка отправителя с токеном, запись и
// рассылка. Всё, что можно было сломать по мелочи, ломалось бы здесь тише всего,
// поэтому переносилось это, когда остальные восемь групп уже прошли прогон.

// MessageStore — что личным сообщениям нужно от хранилища: четыре метода.
type MessageStore interface {
	SaveMessage(ctx context.Context, m store.Message) error
	ListMessages(ctx context.Context, chatID, deviceID string, before uint64, limit int) ([]store.StoredMessage, error)
	SigningKey(ctx context.Context, deviceID, userID string) ([]byte, error)
	ChatHelperDevices(ctx context.Context, chatID, requesterDevice, requesterUser string) ([]store.ChatHelper, error)
}

var _ MessageStore = (*store.Store)(nil)

// сообщенияDeps — зависимости группы.
//
// Шина нужна здесь напрямую, в обход Notifier, и это не обход правила: «печатает…»
// живёт только пока смотрят на экран. Записывать его в device_events значило бы
// догонять человека сообщением о том, что кто-то печатал полчаса назад.
type сообщенияDeps struct {
	хранилище   MessageStore
	шина        func() Publisher
	уведомитель *Notifier
}

// RegisterMessages — четыре маршрута: отправка, чтение, прочитано, печатает.
func RegisterMessages(
	mux *http.ServeMux,
	st MessageStore,
	bus func() Publisher,
	n *Notifier,
	requireDevice Middleware,
) {
	д := сообщенияDeps{хранилище: st, шина: bus, уведомитель: n}

	mux.HandleFunc("POST /api/v1/messages", requireDevice(postMessage(д)))
	mux.HandleFunc("GET /api/v1/chats/{chatID}/messages", requireDevice(listMessages(д)))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/read", requireDevice(chatRead(д)))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/typing", requireDevice(chatTyping(д)))
}
