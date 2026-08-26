package api

import (
	"context"
	"net/http"

	"tima/server/internal/store"
)

// Чаты: архив, резервные копии и восстановление истории — шаг 4, шестая группа.
//
// Одна тема: что человек делает со своей перепиской помимо переписки. Архив прячет
// её из списка, копии и восстановление возвращают историю на новое устройство.

// ChatStore — что этой группе нужно от хранилища.
type ChatStore interface {
	// Архив
	SetChatArchived(ctx context.Context, chatID, userID string, archived bool) error
	ArchivedChatsFor(ctx context.Context, userID string) ([]string, error)

	// Копии и восстановление
	SaveMessageBackups(ctx context.Context, chatID, ownerID string, items []store.MessageBackup) error
	ListMessageBackups(ctx context.Context, chatID, ownerID string) ([]store.MessageBackup, error)
	SaveRecoveryMessageKeys(ctx context.Context, chatID, recipient string, keys []store.RecoveryMessageKey) error
	ChatHelperDevices(ctx context.Context, chatID, requesterDevice, requesterUser string) ([]store.ChatHelper, error)
	IsChatParticipant(ctx context.Context, chatID, userID string) (bool, error)
	IsChatParticipantDevice(ctx context.Context, chatID, deviceID string) (bool, error)
	DeviceEncryptionPub(ctx context.Context, deviceID string) ([]byte, error)
	IdentityPub(ctx context.Context, userID string) ([]byte, error)
}

var _ ChatStore = (*store.Store)(nil)

type chatsDeps struct {
	store    ChatStore
	notifier *Notifier
}

// RegisterChats — семь маршрутов архива и восстановления.
func RegisterChats(mux *http.ServeMux, st ChatStore, n *Notifier, requireDevice Middleware) {
	deps := chatsDeps{store: st, notifier: n}

	mux.HandleFunc("GET /api/v1/chats/archived", requireDevice(listArchivedChats(deps)))
	mux.HandleFunc("PUT /api/v1/chats/{chatID}/archive", requireDevice(archiveChat(deps)))
	mux.HandleFunc("DELETE /api/v1/chats/{chatID}/archive", requireDevice(unarchiveChat(deps)))

	mux.HandleFunc("POST /api/v1/chats/{chatID}/backup", requireDevice(chatBackupSave(deps)))
	mux.HandleFunc("GET /api/v1/chats/{chatID}/backup", requireDevice(chatBackupList(deps)))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/recover", requireDevice(chatRecover(deps)))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/recover/provide", requireDevice(chatRecoverProvide(deps)))
}
