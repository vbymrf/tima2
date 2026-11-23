// Приглашения в группу, канал, сообщество (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2).
//
// В секретную сущность войти можно только так; в остальные — тоже можно, это просто поимённый путь. Карточку
// приглашения человеку показывает клиент пригласившего — сообщением в их личной переписке, сквозным
// шифрованием; заблокирован пригласивший — карточки не будет, как любого его сообщения. Сервер держит состояние
// и по нему пускает.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// inviteTTL — срок приглашения: месяц, как у остальных «ждём ответа» в сущностях.
const inviteTTL = 30 * 24 * time.Hour

// InviteStore — что нужно приглашениям от хранилища.
type InviteStore interface {
	GetGroup(ctx context.Context, groupID string) (store.Group, error)
	GroupRole(ctx context.Context, groupID, userID string) (string, error)
	AddGroupMember(ctx context.Context, groupID, userID, role string) error
	GetChannel(ctx context.Context, channelID string) (store.Channel, error)
	ChannelModerators(ctx context.Context, channelID string) ([]string, error)
	IsSubscribed(ctx context.Context, channelID, userID string) (bool, error)
	Subscribe(ctx context.Context, channelID, userID string) error
	GetCommunity(ctx context.Context, communityID string) (store.Community, error)
	CommunityAdmins(ctx context.Context, communityID string) ([]string, error)
	IsSubscribedToCommunity(ctx context.Context, communityID, userID string) (bool, error)
	SubscribeCommunity(ctx context.Context, communityID, userID string, on bool) error
	CreateInvite(ctx context.Context, kind, entityID, inviterID, inviteeID string, ttl time.Duration) (store.Invite, error)
	MyInvites(ctx context.Context, userID string) ([]store.Invite, error)
	AnswerInvite(ctx context.Context, inviteID, inviteeID string, accept bool) (store.Invite, error)
	ListUserDevices(ctx context.Context, userID string) ([]store.UserDevice, error)
	ActiveMemberDevices(ctx context.Context, groupID, exceptDevice string) ([]string, error)
}

var _ InviteStore = (*store.Store)(nil)

// RegisterInvites — приглашения: пригласить в сущность, свои приглашения, ответ.
//
// groups — зависимости групп: принятое приглашение в личную группу меняет состав, а значит и ключ (ADR-0017 §2),
// и напоминание участникам о ротации идёт тем же путём, что при приёме заявки.
func RegisterInvites(mux *http.ServeMux, st InviteStore, groups groupsDeps, n *Notifier, requireDevice Middleware) {
	mux.HandleFunc("POST /api/v1/groups/{entityID}/invites", requireDevice(inviteTo(st, n, "group")))
	mux.HandleFunc("POST /api/v1/channels/{entityID}/invites", requireDevice(inviteTo(st, n, "channel")))
	mux.HandleFunc("POST /api/v1/communities/{entityID}/invites", requireDevice(inviteTo(st, n, "community")))
	mux.HandleFunc("GET /api/v1/users/me/invites", requireDevice(myInvites(st)))
	mux.HandleFunc("PUT /api/v1/invites/{inviteID}", requireDevice(answerInvite(st, groups, n)))
}

func inviteJSON(v store.Invite) map[string]any {
	return map[string]any{
		"invite_id": v.InviteID, "entity_kind": v.EntityKind, "entity_id": v.EntityID,
		"inviter_id": v.InviterID, "invitee_id": v.InviteeID, "state": v.State,
		"title": v.Title, "hidden": v.Hidden, "personal": v.Personal,
		"created_at": v.CreatedAt.UTC().Format(time.RFC3339), "expires_at": v.ExpiresAt.UTC().Format(time.RFC3339),
	}
}

// inviteRights — может ли человек приглашать в сущность и состоит ли в ней приглашаемый.
//
// Приглашают те, кто распоряжается составом (§3 плана): в группе — модератор и выше; в канале — владелец и
// модераторы; в сообществе — владелец и админы. Сущности нет или она закрыта от просящего — «не найдено»:
// разница выдала бы секретную.
func inviteRights(ctx context.Context, st InviteStore, kind, entityID, actorID, inviteeID string) (allowed, already bool, err error) {
	switch kind {
	case "group":
		if _, err = st.GetGroup(ctx, entityID); err != nil {
			return false, false, err
		}
		role, err := st.GroupRole(ctx, entityID, actorID)
		if err != nil && !errors.Is(err, store.ErrNotMember) {
			return false, false, err
		}
		if role == "" {
			return false, false, store.ErrGroupNotFound
		}
		other, err := st.GroupRole(ctx, entityID, inviteeID)
		if err != nil && !errors.Is(err, store.ErrNotMember) {
			return false, false, err
		}
		return roleRank[role] >= rankModerator, other != "", nil
	case "channel":
		ch, err := st.GetChannel(ctx, entityID)
		if err != nil {
			return false, false, err
		}
		allowed := ch.OwnerID == actorID
		if !allowed {
			mods, err := st.ChannelModerators(ctx, entityID)
			if err != nil {
				return false, false, err
			}
			for _, m := range mods {
				if m == actorID {
					allowed = true
					break
				}
			}
		}
		if !allowed {
			if sub, _ := st.IsSubscribed(ctx, entityID, actorID); !sub && ch.Hidden {
				return false, false, store.ErrChannelNotFound
			}
		}
		sub, err := st.IsSubscribed(ctx, entityID, inviteeID)
		return allowed, sub, err
	case "community":
		c, err := st.GetCommunity(ctx, entityID)
		if err != nil {
			return false, false, err
		}
		allowed := c.OwnerID == actorID
		if !allowed {
			admins, err := st.CommunityAdmins(ctx, entityID)
			if err != nil {
				return false, false, err
			}
			for _, a := range admins {
				if a == actorID {
					allowed = true
					break
				}
			}
		}
		if !allowed {
			if sub, _ := st.IsSubscribedToCommunity(ctx, entityID, actorID); !sub && c.Hidden {
				return false, false, store.ErrCommunityNotFound
			}
		}
		sub, err := st.IsSubscribedToCommunity(ctx, entityID, inviteeID)
		return allowed, sub, err
	}
	return false, false, store.ErrInviteNotFound
}

func notFound(err error) bool {
	return errors.Is(err, store.ErrGroupNotFound) || errors.Is(err, store.ErrChannelNotFound) ||
		errors.Is(err, store.ErrCommunityNotFound) || errors.Is(err, store.ErrInviteNotFound)
}

// inviteTo — POST /{groups|channels|communities}/{id}/invites {user_id}.
func inviteTo(st InviteStore, n *Notifier, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			UserID string `json:"user_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.UserID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
			return
		}
		id, _ := auth.FromContext(r.Context())
		entityID := r.PathValue("entityID")
		if req.UserID == id.UserID {
			writeErr(w, http.StatusBadRequest, "self", "себя не приглашают")
			return
		}
		allowed, already, err := inviteRights(r.Context(), st, kind, entityID, id.UserID, req.UserID)
		if notFound(err) {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		} else if err != nil {
			log.Printf("inviteTo %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !allowed {
			writeErr(w, http.StatusForbidden, "forbidden", "приглашают те, кто распоряжается составом")
			return
		}
		if already {
			writeErr(w, http.StatusConflict, "already_member", "человек уже внутри")
			return
		}
		inv, err := st.CreateInvite(r.Context(), kind, entityID, id.UserID, req.UserID, inviteTTL)
		if errors.Is(err, store.ErrUserUnknown) {
			writeErr(w, http.StatusNotFound, "user_not_found", "пользователь не существует")
			return
		} else if err != nil {
			log.Printf("inviteTo %s: create: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Событие — приглашённому: список приглашений обновить. Карточку в переписке шлёт клиент.
		if n != nil {
			n.Users(r.Context(), []string{req.UserID}, "entity.invite", map[string]any{
				"invite_id": inv.InviteID, "entity_kind": kind, "entity_id": entityID, "inviter_id": id.UserID,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(inviteJSON(inv))
	}
}

// myInvites — GET /users/me/invites: живые приглашения мне.
func myInvites(st InviteStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		list, err := st.MyInvites(r.Context(), id.UserID)
		if err != nil {
			log.Printf("myInvites: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(list))
		for _, v := range list {
			out = append(out, inviteJSON(v))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"invites": out})
	}
}

// answerInvite — PUT /invites/{inviteID} {accept}: принять или отказаться.
//
// Принял — входит: в группу участником (личная — со сменой ключа, как при приёме заявки, Г3), на канал и в
// сообщество — подпиской.
func answerInvite(st InviteStore, groups groupsDeps, n *Notifier) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Accept *bool `json:"accept"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Accept == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен accept: true|false")
			return
		}
		id, _ := auth.FromContext(r.Context())
		inv, err := st.AnswerInvite(r.Context(), r.PathValue("inviteID"), id.UserID, *req.Accept)
		if errors.Is(err, store.ErrInviteNotFound) {
			writeErr(w, http.StatusNotFound, "invite_not_found", "приглашения нет или на него уже ответили")
			return
		} else if errors.Is(err, store.ErrInviteExpired) {
			writeErr(w, http.StatusGone, "invite_expired", "срок приглашения прошёл")
			return
		} else if err != nil {
			log.Printf("answerInvite: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if *req.Accept {
			if err := enterByInvite(r.Context(), st, groups, inv); notFound(err) {
				writeErr(w, http.StatusNotFound, "not_found", "сущности больше нет")
				return
			} else if errors.Is(err, store.ErrBanned) {
				writeErr(w, http.StatusForbidden, "banned", "вы забанены в этой сущности или в её сообществе")
				return
			} else if err != nil {
				log.Printf("answerInvite: вход: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
		}
		// Ответ — пригласившему: молчаливый отказ неотличим от «не дошло» (как у заявок).
		if n != nil {
			n.Users(r.Context(), []string{inv.InviterID}, "entity.invite_answered", map[string]any{
				"invite_id": inv.InviteID, "entity_kind": inv.EntityKind, "entity_id": inv.EntityID,
				"invitee_id": inv.InviteeID, "state": inv.State,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(inviteJSON(inv))
	}
}

func enterByInvite(ctx context.Context, st InviteStore, groups groupsDeps, inv store.Invite) error {
	// Забаненный в сущности или в её сообществе не входит и по приглашению (СЦ23).
	if err := bannedErr(ctx, st, inv.EntityKind, inv.EntityID, inv.InviteeID); err != nil {
		return err
	}
	switch inv.EntityKind {
	case "group":
		g, err := st.GetGroup(ctx, inv.EntityID)
		if err != nil {
			return err
		}
		if err := st.AddGroupMember(ctx, inv.EntityID, inv.InviteeID, "member"); err != nil && !errors.Is(err, store.ErrUserUnknown) {
			return err
		}
		// Состав личной сменился — ключ обязан смениться; ротирует первый открывший приложение (ADR-0017 §2).
		if g.Kind == "private" && groups.store != nil {
			if devices, err := st.ActiveMemberDevices(ctx, inv.EntityID, ""); err == nil {
				remindAboutRotation(groups, ctx, inv.EntityID, devices)
			}
		}
		return nil
	case "channel":
		if _, err := st.GetChannel(ctx, inv.EntityID); err != nil {
			return err
		}
		return st.Subscribe(ctx, inv.EntityID, inv.InviteeID)
	case "community":
		if _, err := st.GetCommunity(ctx, inv.EntityID); err != nil {
			return err
		}
		return st.SubscribeCommunity(ctx, inv.EntityID, inv.InviteeID, true)
	}
	return store.ErrInviteNotFound
}
