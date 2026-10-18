// Описание — сообщения уровня 0 (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО2). Есть у
// каждой сущности — группы, канала, сообщества (заказчик 2026-10-18: «0 уровень — это описание.
// Содержат все сущности»). Забирается, только когда человек открыл описание: в ленту, уведомления и
// счёт нового оно не идёт. Не больше ShowcaseLimit сообщений; у каждой сущности есть место под одно
// суперсообщение владельца — по умолчанию пустое.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ShowcaseLimit — сколько сообщений уровня 0 бывает у сущности (заказчик 2026-10-18: «не более 50»).
const ShowcaseLimit = 50

// Виды сущности описания. Группа и канал совпадают с видами закрепления.
const ShowcaseCommunity = "community"

// showcaseCount — живые сообщения уровня 0 сущности; правки и комментарии не считаются.
var showcaseCount = map[string]string{
	PinGroup: `SELECT count(*) FROM group_messages WHERE group_id = $1::uuid AND level = 0 AND NOT deleted
		AND edit_of = 0`,
	PinChannel: `SELECT count(*) FROM channel_posts WHERE channel_id = $1::uuid AND level = 0 AND NOT deleted
		AND parent_post_id IS NULL`,
	ShowcaseCommunity: `SELECT count(*) FROM community_messages WHERE community_id = $1::uuid AND NOT deleted`,
}

// showcaseSuper — суперсообщение сущности; одна строка на вид.
var showcaseSuper = map[string]string{
	PinGroup:          `SELECT COALESCE(showcase_super, 0) FROM groups WHERE group_id = $1::uuid`,
	PinChannel:        `SELECT COALESCE(showcase_super, 0) FROM channels WHERE channel_id = $1::uuid`,
	ShowcaseCommunity: `SELECT COALESCE(showcase_super, 0) FROM communities WHERE community_id = $1::uuid`,
}

// showcaseIsOwner — владелец ли $2 сущности $1. У группы владелец — роль в составе (её можно
// передать), у канала и сообщества — колонка owner_id.
var showcaseIsOwner = map[string]string{
	PinGroup: `SELECT EXISTS(SELECT 1 FROM memberships WHERE target_type = 'group' AND target_id = $1::uuid
		AND user_id = $2 AND role = 'owner' AND left_at IS NULL)`,
	PinChannel:        `SELECT EXISTS(SELECT 1 FROM channels WHERE channel_id = $1::uuid AND owner_id = $2)`,
	ShowcaseCommunity: `SELECT EXISTS(SELECT 1 FROM communities WHERE community_id = $1::uuid AND owner_id = $2)`,
}

var showcaseSet = map[string]string{
	PinGroup:          `UPDATE groups SET showcase_super = $2 WHERE group_id = $1::uuid`,
	PinChannel:        `UPDATE channels SET showcase_super = $2 WHERE channel_id = $1::uuid`,
	ShowcaseCommunity: `UPDATE communities SET showcase_super = $2 WHERE community_id = $1::uuid`,
}

// ErrShowcaseNotOwner — суперсообщение ставит и снимает только владелец.
var ErrShowcaseNotOwner = errors.New("суперсообщение описания ставит владелец")

// ShowcaseCount — сколько у сущности сообщений уровня 0: предел проверяется перед записью нового.
func (s *Store) ShowcaseCount(ctx context.Context, kind, container string) (int, error) {
	q, ok := showcaseCount[kind]
	if !ok {
		return 0, errors.New("неизвестный вид сущности описания: " + kind)
	}
	var n int
	err := s.pool.QueryRow(ctx, q, container).Scan(&n)
	if isBadUUID(err) {
		return 0, nil
	}
	return n, err
}

// ShowcaseSuper — номер суперсообщения; 0 — места не заняли. Удалённое суперсообщение выдача
// отбросит сама: номер останется, а сообщения в описании не будет.
func (s *Store) ShowcaseSuper(ctx context.Context, kind, container string) (int64, error) {
	q, ok := showcaseSuper[kind]
	if !ok {
		return 0, errors.New("неизвестный вид сущности описания: " + kind)
	}
	var super int64
	err := s.pool.QueryRow(ctx, q, container).Scan(&super)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return 0, nil
	}
	return super, err
}

// SetShowcaseSuper ставит (messageID > 0) или снимает (0) суперсообщение. Только владелец;
// поставить можно лишь живое сообщение уровня 0 этой сущности — иначе ErrMessageGone.
func (s *Store) SetShowcaseSuper(ctx context.Context, kind, container string, messageID int64, actorID string) error {
	q, ok := showcaseIsOwner[kind]
	if !ok {
		return errors.New("неизвестный вид сущности описания: " + kind)
	}
	var owner bool
	err := s.pool.QueryRow(ctx, q, container, actorID).Scan(&owner)
	if isBadUUID(err) {
		return ErrMessageGone
	} else if err != nil {
		return err
	}
	if !owner {
		return ErrShowcaseNotOwner
	}
	if messageID > 0 {
		var alive bool
		var check string
		switch kind {
		case PinGroup:
			check = `SELECT EXISTS(SELECT 1 FROM group_messages WHERE group_id = $1::uuid AND message_id = $2
				AND level = 0 AND NOT deleted AND edit_of = 0)`
		case PinChannel:
			check = `SELECT EXISTS(SELECT 1 FROM channel_posts WHERE channel_id = $1::uuid AND post_id = $2
				AND level = 0 AND NOT deleted AND parent_post_id IS NULL)`
		default:
			check = `SELECT EXISTS(SELECT 1 FROM community_messages WHERE community_id = $1::uuid
				AND message_id = $2 AND NOT deleted)`
		}
		if err := s.pool.QueryRow(ctx, check, container, messageID).Scan(&alive); err != nil {
			return err
		}
		if !alive {
			return ErrMessageGone
		}
	}
	_, err = s.pool.Exec(ctx, showcaseSet[kind], container, nullIfZero(messageID))
	return err
}

// GroupShowcase — сообщения уровня 0 группы от старых к новым, с правками (клиент накладывает их,
// как в переписке). Суперсообщение среди них: какое из них супер, говорит ShowcaseSuper.
func (s *Store) GroupShowcase(ctx context.Context, groupID string) ([]GroupMessage, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT message_id, sender_id, sender_device, kind, COALESCE(gk_version, 0), payload,
		       COALESCE(thread_root, 0), COALESCE(reply_to, 0), created_at_unix_ms, signature, level, key_commitment, edit_of,
		       only_continuation
		FROM group_messages
		WHERE group_id = $1::uuid AND level = 0 AND NOT deleted
		ORDER BY message_id ASC
		LIMIT $2`, groupID, ShowcaseLimit*4)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []GroupMessage
	for rows.Next() {
		m := GroupMessage{GroupID: groupID}
		if err := rows.Scan(&m.MessageID, &m.SenderID, &m.SenderDevice, &m.Kind, &m.GKVersion,
			&m.Payload, &m.ThreadRoot, &m.ReplyTo, &m.CreatedAtUnixMs, &m.Signature, &m.Level, &m.KeyCommitment, &m.EditOf,
			&m.OnlyContinuation); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// ChannelShowcase — записи уровня 0 канала от старых к новым; комментарии не входят.
func (s *Store) ChannelShowcase(ctx context.Context, channelID string) ([]ChannelPost, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT channel_id, post_id, author_id, text, nodes, markup, markup_version, created_at_unix_ms,
		       level, comments_closed, lang, country
		FROM channel_posts
		WHERE channel_id = $1::uuid AND level = 0 AND NOT deleted AND parent_post_id IS NULL
		ORDER BY post_id ASC LIMIT $2`, channelID, ShowcaseLimit)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []ChannelPost
	for rows.Next() {
		var p ChannelPost
		if err := rows.Scan(&p.ChannelID, &p.PostID, &p.AuthorID, &p.Text, &p.Nodes, &p.Markup,
			&p.MarkupVersion, &p.CreatedAtUnixMs, &p.Level, &p.CommentsClosed, &p.Lang, &p.Country); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
