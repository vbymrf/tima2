package api

import (
	"context"
	"net/http"
	"time"

	"tima/server/internal/calls"
	"tima/server/internal/store"
)

// Звонки и аудио-комнаты: вторая группа шага 4.
//
// Здесь сходятся три поля, которые больше никому не нужны: выдача LiveKit-токенов,
// клиент управления комнатами и адрес SFU. На *Server они были видны всем 89
// handler-ам; теперь их видят двенадцать, и ровно те, кому они нужны по делу.

// CallStore — что звонкам нужно от хранилища: шестнадцать методов из ста тридцати двух.
type CallStore interface {
	CreateCall(ctx context.Context, c store.Call) (string, error)
	CreateGroupCall(ctx context.Context, room, kind, groupID, initiatorID string, members []string) (string, error)
	GetCall(ctx context.Context, callID string) (store.Call, error)
	CallForJoinByID(ctx context.Context, callID, userID string) (store.CallForJoin, error)
	CallIDByRoom(ctx context.Context, room string) (string, string, error)
	CallParticipants(ctx context.Context, callID string) (map[string]store.ParticipantState, error)
	SetCallState(ctx context.Context, callID, state string) error
	SetParticipantState(ctx context.Context, callID, userID string, state store.ParticipantState, at time.Time) error
	CreateVoiceRoom(ctx context.Context, title, ownerID string) (string, error)
	GetVoiceRoom(ctx context.Context, roomID string) (store.VoiceRoom, error)
	ListVoiceRooms(ctx context.Context, limit int) ([]store.VoiceRoom, error)
	AddSpeaker(ctx context.Context, roomID, userID string) error
	RemoveSpeaker(ctx context.Context, roomID, userID string) error
	IsSpeaker(ctx context.Context, roomID, ownerID, userID string) (bool, error)
	ListGroupMembers(ctx context.Context, groupID string) ([]store.Member, error)
	ListDevices(ctx context.Context, userID string) ([]store.Device, error)
}

var _ CallStore = (*store.Store)(nil)

// LiveKitSettings — выдача токенов, управление комнатами и адрес SFU.
//
// **Читаются на каждый запрос, а не при регистрации.** Поля Server заполняются
// ПОСЛЕ Register — так делает и cmd/tima, и setupWithCalls в тестах. Снимок,
// снятый в момент регистрации, оставил бы звонки навсегда в 503, и выглядело бы
// это как «LiveKit не настроен», хотя настроен он строкой ниже.
type LiveKitSettings struct {
	Issuer *calls.Issuer     // nil → звонки отвечают 503
	Rooms  *calls.RoomClient // закрыть комнату, выкинуть участника
	URL    string
}

// callsDeps — всё, чем пользуются handler-ы этой группы.
//
// Строчными буквами и внутри пакета: набор зависимостей — не часть публичного
// API, и снаружи его собирать незачем. Публичен только RegisterCalls.
type callsDeps struct {
	store    CallStore
	livekit  func() LiveKitSettings
	notifier *Notifier
}

func (deps callsDeps) issuer() *calls.Issuer    { return deps.livekit().Issuer }
func (deps callsDeps) rooms() *calls.RoomClient { return deps.livekit().Rooms }
func (deps callsDeps) livekitURL() string       { return deps.livekit().URL }

// RegisterCalls — маршруты звонков 1:1, групповых звонков и аудио-комнат.
//
// Вебхук LiveKit регистрируется БЕЗ requireDevice и это не упущение: у SFU нет
// нашего device JWT и быть не должно, его подлинность проверяется подписью на
// секрете LiveKit внутри самого handler-а.
func RegisterCalls(
	mux *http.ServeMux,
	st CallStore,
	livekit func() LiveKitSettings,
	n *Notifier,
	requireDevice Middleware,
) {
	deps := callsDeps{store: st, livekit: livekit, notifier: n}

	mux.HandleFunc("POST /api/v1/calls", requireDevice(startCall(deps)))
	mux.HandleFunc("POST /api/v1/calls/{callID}/answer", requireDevice(answerCall(deps)))
	mux.HandleFunc("POST /api/v1/calls/{callID}/end", requireDevice(endCall(deps)))
	mux.HandleFunc("POST /api/v1/calls/group", requireDevice(startGroupCall(deps)))
	mux.HandleFunc("POST /api/v1/calls/{callID}/join", requireDevice(joinCall(deps)))
	mux.HandleFunc("POST /livekit/webhook", livekitWebhook(deps))

	mux.HandleFunc("POST /api/v1/voice-rooms", requireDevice(createVoiceRoom(deps)))
	mux.HandleFunc("GET /api/v1/voice-rooms", requireDevice(listVoiceRooms(deps)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/join", requireDevice(joinVoiceRoom(deps)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/hand", requireDevice(raiseHand(deps)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/grant", requireDevice(grantSpeaker(deps)))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/revoke", requireDevice(revokeSpeaker(deps)))
}
