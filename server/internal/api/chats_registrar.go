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

	// История на новом устройстве (ИУ1)
	PersonalChatsOf(ctx context.Context, userID string) ([]store.PersonalChatRef, error)
	DeviceCertified(ctx context.Context, userID, deviceID string) (bool, error)
}

var _ ChatStore = (*store.Store)(nil)

type chatsDeps struct {
	store    ChatStore
	notifier *Notifier
	// trust — режим доверия к устройствам: в строгом список переписок получает только
	// заверенное устройство.
	trust func() string
}

// RegisterChats — восемь маршрутов архива и восстановления.
func RegisterChats(mux *http.ServeMux, st ChatStore, n *Notifier, requireDevice Middleware, trust func() string) {
	deps := chatsDeps{store: st, notifier: n, trust: trust}

	mux.HandleFunc("GET /api/v1/chats/personal", requireDevice(listPersonalChats(deps)))

	mux.HandleFunc("GET /api/v1/chats/archived", requireDevice(listArchivedChats(deps)))
	mux.HandleFunc("PUT /api/v1/chats/{chatID}/archive", requireDevice(archiveChat(deps)))
	mux.HandleFunc("DELETE /api/v1/chats/{chatID}/archive", requireDevice(unarchiveChat(deps)))

	mux.HandleFunc("POST /api/v1/chats/{chatID}/backup", requireDevice(chatBackupSave(deps)))
	mux.HandleFunc("GET /api/v1/chats/{chatID}/backup", requireDevice(chatBackupList(deps)))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/recover", requireDevice(chatRecover(deps)))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/recover/provide", requireDevice(chatRecoverProvide(deps)))
}
