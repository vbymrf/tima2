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
	ListGroupMessages(ctx context.Context, groupID string, threadRoot, before int64, limit int, maxLevel int16) ([]store.GroupMessage, error)
	AskForLevel(ctx context.Context, groupID, userID string, level int16) (string, error)
	GrantLevel(ctx context.Context, groupID, userID string, level int16, untilEpoch, grantedBy string, grant bool) error
	ListLevelGrants(ctx context.Context, groupID string) ([]store.LevelGrant, error)
	GrantedLevelFor(ctx context.Context, groupID, userID string) (int16, error)
	SetMembershipTerm(ctx context.Context, groupID, userID, untilEpoch string) error
	ExpireMemberships(ctx context.Context, groupID string) (int64, error)
	AskToJoin(ctx context.Context, groupID, userID string) (string, error)
	ListJoinRequests(ctx context.Context, groupID string) ([]store.JoinRequest, error)
	AnswerJoinRequest(ctx context.Context, groupID, userID, state, answeredBy string) error
	MyJoinRequestState(ctx context.Context, groupID, userID string) (string, error)
	ListUserDevices(ctx context.Context, userID string) ([]store.UserDevice, error)
	SetGroupCardAudience(ctx context.Context, groupID string, userIDs []string) error
	CardsFor(ctx context.Context, userID string) ([]store.GroupCardRow, error)
	GroupCardOpenTo(ctx context.Context, groupID, userID string) (bool, error)
	NarrowGroupMessageLevel(ctx context.Context, groupID string, messageID int64, level int16, requesterID string, allowForeign bool) (string, error)
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

// groupsDeps — зависимости группы.
//
// Ограничитель частоты берётся функцией: он, как Blob и настройки LiveKit,
// заполняется на Server уже после Register.
type groupsDeps struct {
	store    GroupStore
	limiter  func() *ratelimit.Limiter
	notifier *Notifier
}

// RegisterGroups — шестнадцать маршрутов групп.
func RegisterGroups(
	mux *http.ServeMux,
	st GroupStore,
	limit func() *ratelimit.Limiter,
	n *Notifier,
	requireDevice Middleware,
) {
	deps := groupsDeps{store: st, limiter: limit, notifier: n}

	mux.HandleFunc("POST /api/v1/groups", requireDevice(createGroup(deps)))
	mux.HandleFunc("GET /api/v1/groups", requireDevice(listMyGroups(deps)))
	// Раньше «{groupID}»: иначе «cards» будет принято за идентификатор группы.
	mux.HandleFunc("GET /api/v1/groups/cards", requireDevice(listMyCards(deps)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}", requireDevice(getGroup(deps)))
	mux.HandleFunc("PATCH /api/v1/groups/{groupID}", requireDevice(patchGroup(deps)))
	mux.HandleFunc("DELETE /api/v1/groups/{groupID}", requireDevice(deleteGroup(deps)))
	mux.HandleFunc("PUT /api/v1/groups/{groupID}/audience", requireDevice(putGroupAudience(deps)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/join-requests", requireDevice(postJoinRequest(deps)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/join-requests", requireDevice(listJoinRequests(deps)))
	mux.HandleFunc("PATCH /api/v1/groups/{groupID}/join-requests/{userID}", requireDevice(patchJoinRequest(deps)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/level-requests", requireDevice(postLevelRequest(deps)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/level-grants", requireDevice(listLevelGrants(deps)))
	mux.HandleFunc("PUT /api/v1/groups/{groupID}/level-grants/{userID}", requireDevice(putLevelGrant(deps)))
	mux.HandleFunc("PUT /api/v1/groups/{groupID}/members/{userID}/term", requireDevice(putMembershipTerm(deps)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/members", requireDevice(listGroupMembers(deps)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/members", requireDevice(addGroupMember(deps)))
	mux.HandleFunc("DELETE /api/v1/groups/{groupID}/members/{userID}", requireDevice(removeGroupMember(deps)))
	mux.HandleFunc("PUT /api/v1/groups/{groupID}/members/{userID}/role", requireDevice(setGroupRole(deps)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/members/{userID}/ban", requireDevice(banGroupMember(deps)))

	mux.HandleFunc("POST /api/v1/groups/{groupID}/messages", requireDevice(postGroupMessage(deps)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/messages", requireDevice(listGroupMessages(deps)))
	mux.HandleFunc("PATCH /api/v1/groups/{groupID}/messages/{messageID}", requireDevice(patchGroupMessageLevel(deps)))

	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys", requireDevice(groupRotate(deps)))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/keys", requireDevice(groupKeys(deps)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys/recover", requireDevice(groupKeyRecover(deps)))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys/recover/provide", requireDevice(groupKeyProvide(deps)))
}
