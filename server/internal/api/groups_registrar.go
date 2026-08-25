package api

import (
	"context"
	"net/http"
	"time"

	"tima/server/internal/ratelimit"
	"tima/server/internal/store"
)

// Группы: управление составом, сообщения и ключи — шаг 4, четвёртая группа.
//
// Самая связанная из вынесенных: три файла, шестнадцать handler-ов и тридцать два
// метода хранилища. Разделять её на три registrar-а было бы неправдой — состав,
// сообщения и ключи связаны инвариантом ротации: смена состава обязана менять ключ,
// и проверяет это один и тот же код.

// GroupStore — что группам нужно от хранилища.
type GroupStore interface {
	// Управление группой и составом
	CreateGroup(ctx context.Context, g store.Group) (string, error)
	GetGroup(ctx context.Context, groupID string) (store.Group, error)
	UpdateGroup(ctx context.Context, g store.Group) error
	SoftDeleteGroup(ctx context.Context, groupID string) error
	ListGroupsForUser(ctx context.Context, userID string) ([]store.MyGroup, error)
	ListGroupMembers(ctx context.Context, groupID string) ([]store.Member, error)
	AddGroupMember(ctx context.Context, groupID, userID, role string) error
	RemoveGroupMember(ctx context.Context, groupID, userID string) error
	SetGroupRole(ctx context.Context, groupID, userID, role string) error
	BanGroupMember(ctx context.Context, groupID, userID string, seconds int64) error
	GroupRole(ctx context.Context, groupID, userID string) (string, error)
	GroupMemberInfo(ctx context.Context, groupID, userID string) (string, *time.Time, error)

	// Сообщения группы
	SaveGroupMessage(ctx context.Context, m store.GroupMessage) (int64, bool, error)
	ListGroupMessages(ctx context.Context, groupID string, threadRoot, before int64, limit int) ([]store.GroupMessage, error)
	GroupMessageExists(ctx context.Context, groupID string, messageID int64) (bool, error)
	SenderPostedWithin(ctx context.Context, groupID, senderID string, seconds int32) (bool, error)

	// Ключи группы и ротация
	CurrentGKVersion(ctx context.Context, groupID string) (int32, error)
	GroupKeyVersionExists(ctx context.Context, groupID string, version int32) (bool, error)
	SaveGroupRotation(ctx context.Context, rot store.GroupRotation) error
	LatestGroupRotation(ctx context.Context, groupID string) (store.GroupRotationInfo, error)
	RotationEvidenceSince(ctx context.Context, groupID string, since time.Time) (store.RotationEvidence, error)
	ListGroupKeysForDevice(ctx context.Context, groupID, deviceID string, sinceVersion int32) ([]store.DeviceGroupKey, error)
	MissingGKVersions(ctx context.Context, groupID, deviceID string) ([]int32, error)
	SaveRecoveryKeys(ctx context.Context, groupID, recipient string, keys []store.RecoveryKey) error
	HelperDevices(ctx context.Context, groupID, requester string, versions []int32) ([]string, error)
	EscrowKeyEpoch(ctx context.Context, id uint32) (string, error)

	// Устройства участников: покрытие ротации и адресаты уведомлений
	ActiveMemberDevices(ctx context.Context, groupID, exceptDevice string) ([]string, error)
	NonMemberDevices(ctx context.Context, groupID string, deviceIDs []string) ([]string, error)
	IsGroupMemberDevice(ctx context.Context, groupID, deviceID string) (bool, error)
	DeviceEncryptionPub(ctx context.Context, deviceID string) ([]byte, error)
	IdentityPub(ctx context.Context, userID string) ([]byte, error)
	SigningKey(ctx context.Context, deviceID, userID string) ([]byte, error)
}

var _ GroupStore = (*store.Store)(nil)

// группыDeps — зависимости группы.
//
// Ограничитель частоты берётся функцией: он, как Blob и настройки LiveKit,
// заполняется на Server уже после Register.
type группыDeps struct {
	хранилище   GroupStore
	лимит       func() *ratelimit.Limiter
	уведомитель *Notifier
}

// RegisterGroups — шестнадцать маршрутов групп.
func RegisterGroups(
	mux *http.ServeMux,
	st GroupStore,
	limit func() *ratelimit.Limiter,
	n *Notifier,
	requireDevice Middleware,
) {
	д := группыDeps{хранилище: st, лимит: limit, уведомитель: n}

	mux.HandleFunc("POST /api/v1/groups", requireDevice(createGroup(д)))
	mux.HandleFunc("GET /api/v1/groups", requireDevice(listMyGroups(д)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}", requireDevice(getGroup(д)))
	mux.HandleFunc("PATCH /api/v1/groups/{groupID}", requireDevice(patchGroup(д)))
	mux.HandleFunc("DELETE /api/v1/groups/{groupID}", requireDevice(deleteGroup(д)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/members", requireDevice(listGroupMembers(д)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/members", requireDevice(addGroupMember(д)))
	mux.HandleFunc("DELETE /api/v1/groups/{groupID}/members/{userID}", requireDevice(removeGroupMember(д)))
	mux.HandleFunc("PUT /api/v1/groups/{groupID}/members/{userID}/role", requireDevice(setGroupRole(д)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/members/{userID}/ban", requireDevice(banGroupMember(д)))

	mux.HandleFunc("POST /api/v1/groups/{groupID}/messages", requireDevice(postGroupMessage(д)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/messages", requireDevice(listGroupMessages(д)))

	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys", requireDevice(groupRotate(д)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/keys", requireDevice(groupKeys(д)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys/recover", requireDevice(groupKeyRecover(д)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys/recover/provide", requireDevice(groupKeyProvide(д)))
}
