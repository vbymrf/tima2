// Передача владения сущностью по ключу личности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6, СЦ11).
//
// Владелец подписывает акт `tima.entity-transfer.v1|…` ключом последнего звена (не текущим ключом аккаунта: после
// административной смены личности он другой, и передать нельзя); принимающий подписывает тот же акт своим ключом
// личности. Сервер проверяет обе подписи, роль принимающего сейчас, номер звена и срок — и одной транзакцией
// добавляет звено, меняет `owner_id` и роли. Кода нет (§1.3, 3): сама подпись и есть подтверждение владения.
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/store"
)

// ownershipTTL — сколько живёт предложение (как у передачи виртуального).
const ownershipTTL = 30 * time.Minute

// OwnershipEntities — что владению нужно от сущностей.
type OwnershipEntities interface {
	GetGroup(ctx context.Context, groupID string) (store.Group, error)
	GroupRole(ctx context.Context, groupID, userID string) (string, error)
	GetChannel(ctx context.Context, channelID string) (store.Channel, error)
	ChannelModerators(ctx context.Context, channelID string) ([]string, error)
	GetCommunity(ctx context.Context, communityID string) (store.Community, error)
	CommunityAdmins(ctx context.Context, communityID string) ([]string, error)
}

var _ OwnershipEntities = (*store.Store)(nil)

type ownershipDeps struct {
	st  OwnershipEntities
	own store.Ownership
	n   *Notifier
	now func() time.Time
}

// RegisterOwnership — цепочка, предложение, принятие, отказ; свои предложения.
func RegisterOwnership(mux *http.ServeMux, st OwnershipEntities, own store.Ownership, n *Notifier, requireDevice Middleware) {
	d := ownershipDeps{st: st, own: own, n: n, now: time.Now}
	for _, k := range []struct{ kind, plural string }{{"group", "groups"}, {"channel", "channels"}, {"community", "communities"}} {
		base := "/api/v1/" + k.plural + "/{entityID}/ownership"
		mux.HandleFunc("GET "+base, requireDevice(ownershipChain(d, k.kind)))
		mux.HandleFunc("POST "+base+"/offer", requireDevice(offerOwnership(d, k.kind)))
		mux.HandleFunc("DELETE "+base+"/offer", requireDevice(dropOwnershipOffer(d, k.kind)))
		mux.HandleFunc("POST "+base+"/accept", requireDevice(acceptOwnership(d, k.kind)))
	}
	mux.HandleFunc("GET /api/v1/users/me/ownership-offers", requireDevice(myOwnershipOffers(d)))
}

// entityOwner — владелец сущности по указателю `owner_id`; сущности нет — ErrInviteNotFound-подобное «не найдено».
func entityOwner(ctx context.Context, st OwnershipEntities, kind, id string) (string, error) {
	switch kind {
	case "group":
		g, err := st.GetGroup(ctx, id)
		return g.OwnerID, err
	case "channel":
		c, err := st.GetChannel(ctx, id)
		return c.OwnerID, err
	default:
		c, err := st.GetCommunity(ctx, id)
		return c.OwnerID, err
	}
}

// mayReceive — может ли человек принять владение сейчас (§1.2, 9): в группе — модератор и выше; в канале —
// модератор; в сообществе — админ.
func mayReceive(ctx context.Context, st OwnershipEntities, kind, id, userID string) (bool, error) {
	switch kind {
	case "group":
		role, err := st.GroupRole(ctx, id, userID)
		if errors.Is(err, store.ErrNotMember) {
			return false, nil
		}
		return roleRank[role] >= rankModerator, err
	case "channel":
		mods, err := st.ChannelModerators(ctx, id)
		return listHas(mods, userID), err
	default:
		admins, err := st.CommunityAdmins(ctx, id)
		return listHas(admins, userID), err
	}
}

func listHas(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}

func entityMissing(err error) bool {
	return errors.Is(err, store.ErrGroupNotFound) || errors.Is(err, store.ErrChannelNotFound) || errors.Is(err, store.ErrCommunityNotFound)
}

func offerJSON(f store.TransferOffer) map[string]any {
	return map[string]any{
		"entity_kind": f.EntityKind, "entity_id": f.EntityID, "link_no": f.LinkNo, "from_id": f.FromID,
		"to_id": f.ToID, "expires_unix": f.ExpiresUnix, "title": f.Title,
	}
}

// ownershipChain — GET …/ownership: цепочка и может ли просящий передать (его ключ = ключ последнего звена).
// Видят владелец и те, кто распоряжается вместе с ним; посторонним цепочка ничего не даёт.
func ownershipChain(d ownershipDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		entityID := r.PathValue("entityID")
		owner, err := entityOwner(r.Context(), d.st, kind, entityID)
		if entityMissing(err) {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		staff, _ := mayReceive(r.Context(), d.st, kind, entityID, id.UserID)
		if owner != id.UserID && !staff {
			writeErr(w, http.StatusForbidden, "forbidden", "цепочку видят владелец и те, кто распоряжается")
			return
		}
		chain, err := d.own.Chain(r.Context(), kind, entityID)
		if err != nil {
			log.Printf("ownershipChain: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		mine, _ := d.own.IdentityPub(r.Context(), id.UserID)
		out := make([]map[string]any, 0, len(chain))
		canTransfer := false
		for i, l := range chain {
			out = append(out, map[string]any{
				"link_no": l.LinkNo, "owner_id": l.OwnerID, "kind": l.Kind,
				"identity_pub": base64.RawURLEncoding.EncodeToString(l.IdentityPub),
				"created_at":   l.CreatedAt.UTC().Format(time.RFC3339),
			})
			if i == len(chain)-1 {
				canTransfer = owner == id.UserID && len(l.IdentityPub) == 32 && len(mine) == 32 && string(l.IdentityPub) == string(mine)
			}
		}
		answer := map[string]any{"chain": out, "can_transfer": canTransfer, "next_link": len(chain) + 1}
		if f, err := d.own.GetOffer(r.Context(), kind, entityID); err == nil && f.ExpiresUnix > d.now().Unix() {
			answer["offer"] = offerJSON(f)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(answer)
	}
}

func decodeSig(raw string) ([]byte, bool) {
	sig, err := base64.RawURLEncoding.DecodeString(raw)
	return sig, err == nil && len(sig) == 64
}

// offerOwnership — POST …/ownership/offer {to_user_id, expires_unix, signature}: владелец предлагает.
func offerOwnership(d ownershipDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ToUserID    string `json:"to_user_id"`
			ExpiresUnix int64  `json:"expires_unix"`
			Signature   string `json:"signature"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.ToUserID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны to_user_id, expires_unix, signature")
			return
		}
		sig, ok := decodeSig(req.Signature)
		if !ok {
			writeErr(w, http.StatusBadRequest, "bad_signature", "signature — 64 байта base64url")
			return
		}
		id, _ := auth.FromContext(r.Context())
		entityID := r.PathValue("entityID")
		owner, err := entityOwner(r.Context(), d.st, kind, entityID)
		if entityMissing(err) {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if owner != id.UserID {
			writeErr(w, http.StatusForbidden, "forbidden", "передаёт только владелец")
			return
		}
		now := d.now()
		if req.ExpiresUnix <= now.Unix() || req.ExpiresUnix > now.Add(ownershipTTL+time.Minute).Unix() {
			writeErr(w, http.StatusBadRequest, "bad_expiry", "срок акта — не больше 30 минут вперёд")
			return
		}
		if req.ToUserID == id.UserID {
			writeErr(w, http.StatusBadRequest, "self", "себе не передают")
			return
		}
		if ok, err := mayReceive(r.Context(), d.st, kind, entityID, req.ToUserID); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if !ok {
			writeErr(w, http.StatusConflict, "receiver_role", "принимающий — не ниже модератора (в сообществе — админ)")
			return
		}
		last, found, err := d.own.Last(r.Context(), kind, entityID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !found || len(last.IdentityPub) != 32 {
			writeErr(w, http.StatusConflict, "no_identity", "у звена владения нет ключа личности — передать нечем")
			return
		}
		bytes := timacrypto.EntityTransferBytes(kind, entityID, last.LinkNo+1, id.UserID, req.ToUserID, req.ExpiresUnix)
		if !timacrypto.VerifyEnvelopeSignature(last.IdentityPub, bytes, sig) {
			// Подпись не тем ключом — в том числе текущим ключом аккаунта после административной смены личности (§6.3).
			writeErr(w, http.StatusForbidden, "bad_signature", "акт подписан не ключом, которым владение получено")
			return
		}
		offer := store.TransferOffer{
			EntityKind: kind, EntityID: entityID, LinkNo: last.LinkNo + 1, FromID: id.UserID, ToID: req.ToUserID,
			ExpiresUnix: req.ExpiresUnix, OwnerSig: sig,
		}
		if err := d.own.Offer(r.Context(), offer); errors.Is(err, store.ErrUserUnknown) {
			writeErr(w, http.StatusNotFound, "user_not_found", "пользователь не существует")
			return
		} else if err != nil {
			log.Printf("offerOwnership: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if d.n != nil {
			d.n.Users(r.Context(), []string{req.ToUserID}, "entity.ownership_offer", offerJSON(offer))
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(offerJSON(offer))
	}
}

// dropOwnershipOffer — DELETE …/ownership/offer: владелец отзывает или принимающий отказывается.
func dropOwnershipOffer(d ownershipDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		entityID := r.PathValue("entityID")
		f, err := d.own.GetOffer(r.Context(), kind, entityID)
		if errors.Is(err, store.ErrOfferNotFound) || (err == nil && f.FromID != id.UserID && f.ToID != id.UserID) {
			writeErr(w, http.StatusNotFound, "offer_not_found", "предложения нет")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if err := d.own.DropOffer(r.Context(), kind, entityID); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if d.n != nil {
			other := f.FromID
			if id.UserID == f.FromID {
				other = f.ToID
			}
			d.n.Users(r.Context(), []string{other}, "entity.ownership_dropped", offerJSON(f))
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// acceptOwnership — POST …/ownership/accept {signature}: принимающий подписывает тот же акт своим ключом личности.
func acceptOwnership(d ownershipDeps, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Signature string `json:"signature"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужна signature")
			return
		}
		sig, ok := decodeSig(req.Signature)
		if !ok {
			writeErr(w, http.StatusBadRequest, "bad_signature", "signature — 64 байта base64url")
			return
		}
		id, _ := auth.FromContext(r.Context())
		entityID := r.PathValue("entityID")
		f, err := d.own.GetOffer(r.Context(), kind, entityID)
		if errors.Is(err, store.ErrOfferNotFound) || (err == nil && f.ToID != id.UserID) {
			writeErr(w, http.StatusNotFound, "offer_not_found", "предложения нет")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if f.ExpiresUnix <= d.now().Unix() {
			_ = d.own.DropOffer(r.Context(), kind, entityID)
			writeErr(w, http.StatusGone, "offer_expired", "срок предложения прошёл")
			return
		}
		// Роль — сейчас: пока шло предложение, принимающего могли понизить.
		if ok, err := mayReceive(r.Context(), d.st, kind, entityID, id.UserID); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		} else if !ok {
			_ = d.own.DropOffer(r.Context(), kind, entityID)
			writeErr(w, http.StatusConflict, "receiver_role", "принимающий больше не модератор (в сообществе — не админ)")
			return
		}
		mine, err := d.own.IdentityPub(r.Context(), id.UserID)
		if err != nil || len(mine) != 32 {
			writeErr(w, http.StatusConflict, "no_identity", "у аккаунта нет ключа личности — сначала войдите по фразе")
			return
		}
		bytes := timacrypto.EntityTransferBytes(kind, entityID, f.LinkNo, f.FromID, f.ToID, f.ExpiresUnix)
		if !timacrypto.VerifyEnvelopeSignature(mine, bytes, sig) {
			dropped, _ := d.own.FailedAttempt(r.Context(), kind, entityID)
			code := "bad_signature"
			if dropped {
				code = "offer_dropped"
			}
			writeErr(w, http.StatusForbidden, code, "подпись не прошла: фраза не та")
			return
		}
		if err := d.own.Complete(r.Context(), f, mine, sig); errors.Is(err, store.ErrChainMoved) {
			_ = d.own.DropOffer(r.Context(), kind, entityID)
			writeErr(w, http.StatusConflict, "chain_moved", "владение уже сменилось")
			return
		} else if err != nil {
			log.Printf("acceptOwnership: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if d.n != nil {
			d.n.Users(r.Context(), []string{f.FromID}, "entity.ownership_changed", offerJSON(f))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"owner_id": id.UserID, "link_no": f.LinkNo})
	}
}

// myOwnershipOffers — GET /users/me/ownership-offers: что мне передают.
func myOwnershipOffers(d ownershipDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		list, err := d.own.OffersTo(r.Context(), id.UserID, d.now())
		if err != nil {
			log.Printf("myOwnershipOffers: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(list))
		for _, f := range list {
			out = append(out, offerJSON(f))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"offers": out})
	}
}
