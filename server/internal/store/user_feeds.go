// Лента пользователя и перенос ссылкой (ADR-0019 §7, ПЛАН-СОЦИУМА Г8).
//
// Лента — это канал, который ищут по человеку (решение заказчика 2026-09-04). Не новая
// подсистема: посты, подписчики, удаление и уведомления у канала уже есть. Здесь добавлены
// три вещи — связь «человек → его канал», пост-ссылка вместо копии и выдача, которая
// раскрывает ссылку из оригинала.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ErrNoFeed — ленты у человека ещё нет. Не поломка: она заводится при первом обращении
// владельца, и до тех пор чужой странице нечего показать.
var ErrNoFeed = errors.New("ленты нет")

// ErrCannotCarry — эту запись не выносят: уровень 3 отдан поимённо, шифр читают по ключу
// (ADR-0019 §7). Ни то, ни другое ссылкой не передаётся.
var ErrCannotCarry = errors.New("запись не выносится")

// FeedItem — строка ленты: своя запись или ссылка на чужую.
//
// У ссылки содержимое приходит из оригинала, поэтому автор здесь **не** владелец ленты:
// принёсший назван отдельно. Иначе на странице чужая запись выглядела бы написанной
// хозяином страницы — ровно та подмена авторства, которой ссылка и избегает.
type FeedItem struct {
	PostID          uint64
	Level           int16
	CreatedAtUnixMs int64
	AuthorID        string
	Text            string
	Nodes           []string
	// CarriedBy — кто принёс. Пусто у собственной записи.
	CarriedBy string
	// RefGroupID/RefMessageID — адрес оригинала; пусто у собственной записи.
	RefGroupID   string
	RefMessageID int64
	// SourceTitle — от чьего лица показывать: название группы, откуда принесено.
	SourceTitle string
	// Payload/Signature/SenderDevice/Kind — содержимое оригинала как есть, чтобы клиент
	// проверил подпись автора. Пересобирать их нельзя: подпись считается по тем байтам,
	// что были отправлены.
	Payload      []byte
	Signature    []byte
	SenderDevice string
	Kind         int32
}

// FeedOf — канал-лента этого человека.
func (s *Store) FeedOf(ctx context.Context, userID string) (string, error) {
	var channelID string
	err := s.pool.QueryRow(ctx,
		`SELECT channel_id FROM user_feeds WHERE user_id = $1`, userID).Scan(&channelID)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return "", ErrNoFeed
	}
	return channelID, err
}

// EnsureFeed — лента этого человека; заводит её, если ленты ещё нет.
//
// **Лениво, а не при регистрации.** У аккаунта, который ничего себе не приносил и на
// странице не писал, пустой канал был бы записью ни о чём. Заводит ленту только сам
// владелец — чужое обращение получает ErrNoFeed, потому что показывать ему нечего.
func (s *Store) EnsureFeed(ctx context.Context, userID, title string) (string, error) {
	if channelID, err := s.FeedOf(ctx, userID); err == nil {
		return channelID, nil
	} else if !errors.Is(err, ErrNoFeed) {
		return "", err
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck — no-op после Commit

	var channelID string
	// is_public = false: лента не лежит в каталоге каналов и не ищется там. Её находят по
	// человеку, а не среди каналов — иначе страница каждого попала бы в общий список.
	if err := tx.QueryRow(ctx, `
		INSERT INTO channels (title, owner_id, is_public)
		VALUES ($1, $2, FALSE)
		RETURNING channel_id`, title, userID).Scan(&channelID); err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO channel_subscriptions (channel_id, subscriber_id)
		VALUES ($1, $2) ON CONFLICT DO NOTHING`, channelID, userID); err != nil {
		return "", err
	}
	// Гонка двух устройств одного человека: второе получит DO NOTHING и возьмёт ленту,
	// заведённую первым, — оттого возврат идёт повторным чтением, а не переменной.
	if _, err := tx.Exec(ctx, `
		INSERT INTO user_feeds (user_id, channel_id) VALUES ($1, $2)
		ON CONFLICT (user_id) DO NOTHING`, userID, channelID); err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return s.FeedOf(ctx, userID)
}

// SubscribeToFeed — открыть свою ленту человеку.
//
// **Исправлено 2026-09-05.** Здесь было «подписчиком становится тот, кто ленту открыл»:
// прохожий, заглянувший на страницу, становился своим. Теперь подписывает ВЛАДЕЛЕЦ —
// его клиент сводит книгу со своим списком друзей и просит открыть ленту каждому.
//
// Повтор безвреден: вторая подписка не заводится.
func (s *Store) SubscribeToFeed(ctx context.Context, channelID, userID string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO channel_subscriptions (channel_id, subscriber_id)
		VALUES ($1, $2) ON CONFLICT DO NOTHING`, channelID, userID)
	return err
}

// CarryToFeed кладёт в ленту ссылку на чужую запись.
//
// level — круг, который назначает **принёсший**: у себя он решает, кому показать
// (ADR-0019 §7, «круг пересчитывается на каждом шаге»). visibleTo — граница, до которой
// оригинал показан самому принёсшему.
//
// Возвращает ErrCannotCarry, если оригинал не выносится, и ErrGroupMessageNotFound, если
// его нет или он удалён.
func (s *Store) CarryToFeed(
	ctx context.Context,
	channelID, carrierID, srcGroupID string,
	srcMessageID int64,
	level, visibleTo int16,
) (uint64, error) {
	var srcLevel int16
	err := s.pool.QueryRow(ctx, `
		SELECT level FROM group_messages
		 WHERE group_id = $1 AND message_id = $2 AND NOT deleted`,
		srcGroupID, srcMessageID).Scan(&srcLevel)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return 0, ErrGroupMessageNotFound
	}
	if err != nil {
		return 0, err
	}
	// Унести можно только показанное. Записи, которую сервер этому человеку не отдавал,
	// для него не существует — оттого «не найдено», а не «нельзя»: иначе отказ сам
	// сообщал бы, что запись есть.
	if srcLevel > visibleTo {
		return 0, ErrGroupMessageNotFound
	}
	if srcLevel < 0 || srcLevel > 2 {
		return 0, ErrCannotCarry
	}

	var postID uint64
	// text и nodes пустые: содержимого у ссылки нет по построению, а плоское поле text не
	// заполняется нигде (Plan.md §0.0 решение 9).
	err = s.pool.QueryRow(ctx, `
		INSERT INTO channel_posts
		    (channel_id, author_id, text, nodes, created_at_unix_ms, level, ref_group_id, ref_message_id)
		VALUES ($1, $2, '', '{}', (EXTRACT(EPOCH FROM now()) * 1000)::bigint, $3, $4, $5)
		RETURNING post_id`,
		channelID, carrierID, level, srcGroupID, srcMessageID).Scan(&postID)
	return postID, err
}

// ListFeed — лента с раскрытыми ссылками.
//
// **Ссылка отдаётся только вместе с живым оригиналом.** Оригинал удалён — строки нет; круг
// оригинала сужен до «по разрешению» — строки нет. Так и выполняются обещания ADR-0019 §7:
// «удалил оригинал — исчезло везде», «сузил доступ — подействовало везде». Проверка живёт
// в запросе, а не в обработчике: обойти её тогда нечем.
func (s *Store) ListFeed(
	ctx context.Context,
	channelID string,
	before uint64,
	limit int,
	maxLevel int16,
) ([]FeedItem, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if before == 0 {
		before = ^uint64(0) >> 1
	}
	rows, err := s.pool.Query(ctx, `
		SELECT p.post_id, p.level, p.created_at_unix_ms, p.text, p.nodes,
		       COALESCE(o.sender_id::text, p.author_id::text) AS author_id,
		       CASE WHEN p.ref_group_id IS NULL THEN '' ELSE p.author_id::text END AS carried_by,
		       COALESCE(p.ref_group_id::text, ''), COALESCE(p.ref_message_id, 0),
		       COALESCE(g.title, ''),
		       COALESCE(o.payload, ''::bytea), COALESCE(o.signature, ''::bytea),
		       COALESCE(o.sender_device::text, ''), COALESCE(o.kind, 0)
		  FROM channel_posts p
		  LEFT JOIN group_messages o
		         ON o.group_id = p.ref_group_id AND o.message_id = p.ref_message_id AND NOT o.deleted
		  LEFT JOIN groups g ON g.group_id = p.ref_group_id
		 WHERE p.channel_id = $1 AND p.post_id < $2 AND NOT p.deleted
		   AND p.level <= $4
		   AND (p.ref_group_id IS NULL OR (o.message_id IS NOT NULL AND o.level BETWEEN 0 AND 2))
		 ORDER BY p.post_id DESC
		 LIMIT $3`, channelID, before, limit, maxLevel)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []FeedItem
	for rows.Next() {
		var it FeedItem
		if err := rows.Scan(&it.PostID, &it.Level, &it.CreatedAtUnixMs, &it.Text, &it.Nodes,
			&it.AuthorID, &it.CarriedBy, &it.RefGroupID, &it.RefMessageID, &it.SourceTitle,
			&it.Payload, &it.Signature, &it.SenderDevice, &it.Kind); err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// RemoveFeedItem убирает запись со своей страницы.
//
// Помечает удалённой, а не стирает: пост-ссылка — местное решение владельца страницы, и
// его отмена не трогает ни оригинал, ни чужие ленты. Чужую ленту править нельзя вовсе —
// отсюда владелец в условии.
func (s *Store) RemoveFeedItem(ctx context.Context, channelID string, postID uint64, ownerID string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE channel_posts p SET deleted = TRUE
		 WHERE p.channel_id = $1 AND p.post_id = $2 AND NOT p.deleted
		   AND EXISTS (SELECT 1 FROM channels c
		                WHERE c.channel_id = p.channel_id AND c.owner_id = $3)`,
		channelID, postID, ownerID)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrGroupMessageNotFound
	}
	return nil
}

// FeedSubscribers — кому открыта эта лента.
//
// Список подписчиков ленты **и есть** список друзей владельца: отдельной таблицы друзей
// нет (миграция 0040), потому что она хранила бы то же отношение вторым способом.
//
// Отдаются текущие личности: клиент работает с ними, а не с аккаунтами.
func (s *Store) FeedSubscribers(ctx context.Context, channelID string) ([]string, error) {
	// Владелец подписан на свою ленту с её создания — так она попадает в его же
	// список каналов. В списке друзей ему делать нечего: «дружу сам с собой» не
	// значит ничего, а в счёт друзей эта строка попадала бы всегда.
	rows, err := s.pool.Query(ctx, `
		SELECT s.subscriber_id
		FROM channel_subscriptions s
		JOIN channels c ON c.channel_id = s.channel_id
		WHERE s.channel_id = $1 AND s.subscriber_id <> c.owner_id
		ORDER BY s.created_at`, channelID)
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
