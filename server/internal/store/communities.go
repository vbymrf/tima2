// Сообщества: контейнер, который связывает готовое (ПЛАН-СООБЩЕСТВ С1…С3).
//
// **Ничего не пересылает и не забирает себе чужого.** Внесли группу — поменялась одна
// ссылка; переписка, участники и ключи остались там, где лежали. Отсюда и отсутствие в
// этом файле чего-либо про содержимое групп и каналов: его здесь нет и быть не должно.
//
// Своё содержимое у сообщества ровно одно — сообщения уровня 0, то есть описание
// (уточнение заказчика 2026-09-08).
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

var ErrCommunityNotFound = errors.New("сообщество не найдено")

// ErrElementBusy — элемент уже в другом сообществе.
//
// Отдельная ошибка, а не «нельзя»: человеку надо сказать, что группа не свободна, а не
// что ему не разрешено. Разрешено — просто она уже занята.
var ErrElementBusy = errors.New("элемент уже в сообществе")

type Community struct {
	CommunityID string
	Title       string
	OwnerID     string
	IsPublic    bool
}

// CommunityView — сообщество глазами просящего.
type CommunityView struct {
	Community
	Subscribed bool
	Owner      bool
	Admin      bool
}

// CommunityItem — элемент состава: группа или канал.
type CommunityItem struct {
	Kind  string // group | channel
	ID    string
	Title string
	// Personal — личная группа (`groups.kind = 'private'`): та, что с ключом и составом.
	// В списке страницы она не показывается никогда (ADR-0018 п. 5), и признак нужен
	// именно для этого.
	Personal bool
}

// CommunityMessage — сообщение уровня 0: описание сообщества.
type CommunityMessage struct {
	MessageID       int64
	CommunityID     string
	AuthorID        string
	Nodes           []string
	Markup          []byte
	MarkupVersion   int32
	CreatedAtUnixMs int64
}

// CreateCommunity заводит сообщество и подписывает владельца.
func (s *Store) CreateCommunity(ctx context.Context, c Community) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	var id string
	if err := tx.QueryRow(ctx, `
		INSERT INTO communities (title, owner_id, is_public)
		VALUES ($1,$2,$3) RETURNING community_id`,
		c.Title, c.OwnerID, c.IsPublic).Scan(&id); err != nil {
		return "", err
	}
	// Владелец подписан со дня основания: иначе своё же сообщество не попадёт в его
	// список, и он будет искать его в каталоге среди чужих.
	if _, err := tx.Exec(ctx, `
		INSERT INTO community_subscriptions (community_id, subscriber_id) VALUES ($1,$2)`,
		id, c.OwnerID); err != nil {
		return "", err
	}
	return id, tx.Commit(ctx)
}

func (s *Store) GetCommunity(ctx context.Context, communityID string) (Community, error) {
	var c Community
	err := s.pool.QueryRow(ctx, `
		SELECT community_id, title, owner_id, is_public
		  FROM communities WHERE community_id = $1 AND deleted_at IS NULL`, communityID).
		Scan(&c.CommunityID, &c.Title, &c.OwnerID, &c.IsPublic)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return c, ErrCommunityNotFound
	}
	return c, err
}

// MyCommunities — сообщества, где человек владелец, админ или подписчик.
func (s *Store) MyCommunities(ctx context.Context, userID string) ([]CommunityView, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT c.community_id, c.title, c.owner_id, c.is_public,
		       TRUE AS subscribed,
		       (c.owner_id = $1) AS owner,
		       EXISTS (SELECT 1 FROM community_admins a
		                WHERE a.community_id = c.community_id AND a.user_id = $1) AS admin
		  FROM communities c
		  JOIN community_subscriptions s
		    ON s.community_id = c.community_id AND s.subscriber_id = $1
		 WHERE c.deleted_at IS NULL
		 ORDER BY c.created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	return scanCommunityViews(rows)
}

// DiscoverCommunities — публичные сообщества, на которые человек ещё не подписан.
func (s *Store) DiscoverCommunities(ctx context.Context, userID string, limit int) ([]CommunityView, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows, err := s.pool.Query(ctx, `
		SELECT c.community_id, c.title, c.owner_id, c.is_public,
		       FALSE AS subscribed, (c.owner_id = $1) AS owner, FALSE AS admin
		  FROM communities c
		 WHERE c.deleted_at IS NULL AND c.is_public
		   AND NOT EXISTS (SELECT 1 FROM community_subscriptions s
		                    WHERE s.community_id = c.community_id AND s.subscriber_id = $1)
		 ORDER BY c.created_at DESC
		 LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	return scanCommunityViews(rows)
}

func scanCommunityViews(rows pgx.Rows) ([]CommunityView, error) {
	defer rows.Close()
	var out []CommunityView
	for rows.Next() {
		var v CommunityView
		if err := rows.Scan(&v.CommunityID, &v.Title, &v.OwnerID, &v.IsPublic,
			&v.Subscribed, &v.Owner, &v.Admin); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// CommunityItems — состав: группы и каналы, связанные с сообществом.
//
// Личные группы отдаются с признаком, а не отсеиваются здесь: решение «показывать или
// нет» принимает выдача, которая знает, кто спрашивает. Отсев в хранилище означал бы, что
// владелец не видит собственного состава.
func (s *Store) CommunityItems(ctx context.Context, communityID string) ([]CommunityItem, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT 'group' AS kind, group_id::text, title, (kind = 'private') AS personal
		  FROM groups WHERE community_id = $1 AND deleted_at IS NULL
		UNION ALL
		SELECT 'channel', channel_id::text, title, FALSE
		  FROM channels WHERE community_id = $1 AND deleted_at IS NULL
		 ORDER BY kind, title`, communityID)
	if err != nil {
		if isBadUUID(err) {
			return nil, ErrCommunityNotFound
		}
		return nil, err
	}
	defer rows.Close()
	var out []CommunityItem
	for rows.Next() {
		var it CommunityItem
		if err := rows.Scan(&it.Kind, &it.ID, &it.Title, &it.Personal); err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// LinkItem связывает группу или канал с сообществом, LinkItem с community="" — отвязывает.
//
// **Право: владелец сообщества И владелец элемента одновременно** (решение заказчика по
// развилке С-2). Иначе связывание становится способом присвоить чужую группу.
//
// Оба условия стоят в запросе, а не читаются перед ним: между чтением и записью владелец
// элемента может смениться, и проверка «до» разрешила бы то, что уже запрещено.
func (s *Store) LinkItem(ctx context.Context, communityID, kind, itemID, actorID string) error {
	table, key := "groups", "group_id"
	if kind == "channel" {
		table, key = "channels", "channel_id"
	}
	// Элемент уже в другом сообществе — отдельный ответ: он не «запрещён», он занят.
	var current *string
	err := s.pool.QueryRow(ctx,
		`SELECT community_id::text FROM `+table+` WHERE `+key+` = $1 AND deleted_at IS NULL`,
		itemID).Scan(&current)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return ErrCommunityNotFound
	}
	if err != nil {
		return err
	}
	if current != nil && *current != communityID {
		return ErrElementBusy
	}

	ct, err := s.pool.Exec(ctx, `
		UPDATE `+table+` SET community_id = $1
		 WHERE `+key+` = $2 AND deleted_at IS NULL AND owner_id = $3
		   AND EXISTS (SELECT 1 FROM communities c
		                WHERE c.community_id = $1 AND c.owner_id = $3 AND c.deleted_at IS NULL)`,
		communityID, itemID, actorID)
	if err != nil {
		if isBadUUID(err) {
			return ErrCommunityNotFound
		}
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrNotAllowed
	}
	return nil
}

// UnlinkItem возвращает элемент в отдельное состояние.
//
// Право то же: владелец сообщества и владелец элемента. Отвязать чужую группу из своего
// сообщества нельзя — она чужая; вынуть свою из чужого сообщества тоже нельзя, потому что
// связал её туда владелец сообщества.
func (s *Store) UnlinkItem(ctx context.Context, communityID, kind, itemID, actorID string) error {
	table, key := "groups", "group_id"
	if kind == "channel" {
		table, key = "channels", "channel_id"
	}
	ct, err := s.pool.Exec(ctx, `
		UPDATE `+table+` SET community_id = NULL
		 WHERE `+key+` = $2 AND community_id = $1 AND owner_id = $3
		   AND EXISTS (SELECT 1 FROM communities c
		                WHERE c.community_id = $1 AND c.owner_id = $3)`,
		communityID, itemID, actorID)
	if err != nil {
		if isBadUUID(err) {
			return ErrCommunityNotFound
		}
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrNotAllowed
	}
	return nil
}

// SubscribeCommunity — подписка на сообщество: одно действие на весь контейнер.
//
// Подписка НЕ даёт членства в личных группах внутри: там ключи и состав, и вступление
// остаётся заявкой (ADR-0018). Здесь это выражено тем, что метод не трогает ни одну
// таблицу групп.
func (s *Store) SubscribeCommunity(ctx context.Context, communityID, userID string, on bool) error {
	var err error
	if on {
		_, err = s.pool.Exec(ctx, `
			INSERT INTO community_subscriptions (community_id, subscriber_id)
			SELECT $1, $2 FROM communities WHERE community_id = $1 AND deleted_at IS NULL
			ON CONFLICT DO NOTHING`, communityID, userID)
	} else {
		_, err = s.pool.Exec(ctx, `
			DELETE FROM community_subscriptions
			 WHERE community_id = $1 AND subscriber_id = $2`, communityID, userID)
	}
	if err != nil && isBadUUID(err) {
		return ErrCommunityNotFound
	}
	return err
}

// IsSubscribedToCommunity — подписан ли человек.
func (s *Store) IsSubscribedToCommunity(ctx context.Context, communityID, userID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (SELECT 1 FROM community_subscriptions
		                WHERE community_id = $1 AND subscriber_id = $2)`,
		communityID, userID).Scan(&ok)
	if err != nil && isBadUUID(err) {
		return false, nil
	}
	return ok, err
}

// SetCommunityAdmin — назначить или снять админа сообщества. Только владелец.
func (s *Store) SetCommunityAdmin(ctx context.Context, communityID, ownerID, userID string, on bool) error {
	var (
		ct  commandTag
		err error
	)
	if on {
		ct, err = s.pool.Exec(ctx, `
			INSERT INTO community_admins (community_id, user_id)
			SELECT $1, $3 FROM communities
			 WHERE community_id = $1 AND owner_id = $2 AND deleted_at IS NULL
			ON CONFLICT DO NOTHING`, communityID, ownerID, userID)
	} else {
		ct, err = s.pool.Exec(ctx, `
			DELETE FROM community_admins a
			 WHERE a.community_id = $1 AND a.user_id = $3
			   AND EXISTS (SELECT 1 FROM communities c
			                WHERE c.community_id = $1 AND c.owner_id = $2)`,
			communityID, ownerID, userID)
	}
	if err != nil {
		if isBadUUID(err) {
			return ErrCommunityNotFound
		}
		return err
	}
	if ct.RowsAffected() == 0 {
		var owner bool
		if err := s.pool.QueryRow(ctx,
			`SELECT EXISTS (SELECT 1 FROM communities WHERE community_id = $1 AND owner_id = $2)`,
			communityID, ownerID).Scan(&owner); err != nil {
			return err
		}
		if !owner {
			return ErrNotAllowed
		}
	}
	return nil
}

// CommunityAdmins — кто распоряжается сообществом, кроме владельца.
func (s *Store) CommunityAdmins(ctx context.Context, communityID string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT user_id FROM community_admins WHERE community_id = $1 ORDER BY added_at`, communityID)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// AddCommunityMessage — описание сообщества: сообщение уровня 0.
//
// Пишут владелец и админы (решение Н-4). Уровень в запрос не принимается вовсе: у
// сообщества бывает только нулевой, и параметр означал бы, что бывает другой.
func (s *Store) AddCommunityMessage(ctx context.Context, m CommunityMessage, actorID string) (int64, error) {
	var markupParam any
	if len(m.Markup) > 0 {
		markupParam = string(m.Markup)
	}
	var id int64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO community_messages
		    (community_id, author_id, nodes, markup, markup_version, created_at_unix_ms)
		SELECT $1, $2, $3, $4, $5, $6
		 WHERE EXISTS (SELECT 1 FROM communities c
		                WHERE c.community_id = $1 AND c.deleted_at IS NULL
		                  AND (c.owner_id = $2
		                    OR EXISTS (SELECT 1 FROM community_admins a
		                                WHERE a.community_id = $1 AND a.user_id = $2)))
		RETURNING message_id`,
		m.CommunityID, actorID, m.Nodes, markupParam, m.MarkupVersion, m.CreatedAtUnixMs).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrNotAllowed
	}
	if err != nil && isBadUUID(err) {
		return 0, ErrCommunityNotFound
	}
	return id, err
}

// CommunityMessages — описание: всё, что уровня 0. Старые сверху: описание читают с начала.
func (s *Store) CommunityMessages(ctx context.Context, communityID string, limit int) ([]CommunityMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows, err := s.pool.Query(ctx, `
		SELECT message_id, community_id, author_id, nodes, markup, markup_version, created_at_unix_ms
		  FROM community_messages
		 WHERE community_id = $1 AND NOT deleted
		 ORDER BY message_id ASC LIMIT $2`, communityID, limit)
	if err != nil {
		if isBadUUID(err) {
			return nil, ErrCommunityNotFound
		}
		return nil, err
	}
	defer rows.Close()
	var out []CommunityMessage
	for rows.Next() {
		var m CommunityMessage
		if err := rows.Scan(&m.MessageID, &m.CommunityID, &m.AuthorID, &m.Nodes, &m.Markup,
			&m.MarkupVersion, &m.CreatedAtUnixMs); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
