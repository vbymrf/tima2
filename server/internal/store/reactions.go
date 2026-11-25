package store

// Эмоции, пальцы, комментарии к сообщениям уровня 0 и настройки «Описания» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.18,
// СЦ37–СЦ40; заказчик 2026-11-25; миграция 0093).
//
// Свободными функциями, а не методами: у методов хранилища — бюджет (architecture_test.go).

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// Emotions — шкала «Оценки»: восемь эмоций четырьмя осями и покой (doc/Layout-UI-light/телефон/подокна/оценка.html).
// Порядок — порядок осей; хранится ключ, знак рисует клиент.
var Emotions = []string{"approval", "contempt", "laughter", "pain", "rage", "fear", "interest", "boredom", "calm"}

// ErrMessageNotFound — сообщения уровня 0 или записи канала нет (или удалено).
var ErrMessageNotFound = errors.New("сообщение не найдено")

// EmotionCount — сколько человек поставили эмоцию.
type EmotionCount struct {
	Emotion string `json:"emotion"`
	Count   int    `json:"count"`
}

// Reactions — эмоции сообщения: счёт по эмоциям (по убыванию) и своя; пусто — своей нет.
type Reactions struct {
	Counts []EmotionCount
	Mine   string
}

// IsEmotion — ключ из шкалы.
func IsEmotion(e string) bool {
	for _, x := range Emotions {
		if x == e {
			return true
		}
	}
	return false
}

// ReactionTargetExists — есть ли у сущности сообщение, которому ставят эмоцию: у канала — запись (корень, не
// удалена), у группы — сообщение уровня 0 без ветки, у сообщества — сообщение описания.
func ReactionTargetExists(ctx context.Context, s *Store, kind, entityID string, messageID int64) (bool, error) {
	var q string
	switch kind {
	case "channel":
		q = `SELECT EXISTS (SELECT 1 FROM channel_posts WHERE channel_id::text = $1 AND post_id = $2
		       AND parent_post_id IS NULL AND NOT deleted)`
	case "group":
		q = `SELECT EXISTS (SELECT 1 FROM group_messages WHERE group_id::text = $1 AND message_id = $2
		       AND level = 0 AND gk_version IS NULL AND NOT deleted)`
	case "community":
		q = `SELECT EXISTS (SELECT 1 FROM community_messages WHERE community_id::text = $1 AND message_id = $2
		       AND NOT deleted)`
	default:
		return false, nil
	}
	var ok bool
	err := s.pool.QueryRow(ctx, q, entityID, messageID).Scan(&ok)
	return ok, err
}

// SetReaction — своя эмоция: ставит или меняет; пустая — снимает. Права и шкалу проверяет вызывающий.
func SetReaction(ctx context.Context, s *Store, kind, entityID string, messageID int64, userID, emotion string) error {
	if emotion == "" {
		_, err := s.pool.Exec(ctx, `DELETE FROM entity_reactions
			WHERE kind = $1 AND entity_id::text = $2 AND message_id = $3 AND user_id::text = $4`,
			kind, entityID, messageID, userID)
		return err
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO entity_reactions (kind, entity_id, message_id, user_id, emotion) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (kind, entity_id, message_id, user_id) DO UPDATE SET emotion = EXCLUDED.emotion, created_at = now()`,
		kind, entityID, messageID, userID, emotion)
	if isBadUUID(err) {
		return ErrEntityNotFound
	}
	return err
}

// ReactionsOf — эмоции сообщений сущности: номер → счёт и своя. Сообщений без эмоций в ответе нет.
func ReactionsOf(ctx context.Context, s *Store, kind, entityID string, ids []int64, viewerID string) (map[int64]Reactions, error) {
	out := map[int64]Reactions{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT message_id, emotion, count(*)::int, bool_or(user_id::text = $4)
		  FROM entity_reactions
		 WHERE kind = $1 AND entity_id::text = $2 AND message_id = ANY($3)
		 GROUP BY message_id, emotion
		 ORDER BY message_id, count(*) DESC, emotion`, kind, entityID, ids, viewerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var e string
		var n int
		var mine bool
		if err := rows.Scan(&id, &e, &n, &mine); err != nil {
			return nil, err
		}
		r := out[id]
		r.Counts = append(r.Counts, EmotionCount{Emotion: e, Count: n})
		if mine {
			r.Mine = e
		}
		out[id] = r
	}
	return out, rows.Err()
}

// SetVote — палец вверх (1) или вниз (−1) у записи канала; 0 — снять. Видит только сам.
func SetVote(ctx context.Context, s *Store, channelID string, postID int64, userID string, vote int) error {
	if vote == 0 {
		_, err := s.pool.Exec(ctx, `DELETE FROM channel_post_votes
			WHERE channel_id::text = $1 AND post_id = $2 AND user_id::text = $3`, channelID, postID, userID)
		return err
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO channel_post_votes (channel_id, post_id, user_id, vote) VALUES ($1, $2, $3, $4)
		ON CONFLICT (channel_id, post_id, user_id) DO UPDATE SET vote = EXCLUDED.vote, created_at = now()`,
		channelID, postID, userID, vote)
	if isBadUUID(err) {
		return ErrChannelNotFound
	}
	return err
}

// VotesOf — свои пальцы у записей канала: номер → 1 или −1. Чужих не отдаёт никогда.
func VotesOf(ctx context.Context, s *Store, channelID string, ids []int64, viewerID string) (map[int64]int, error) {
	out := map[int64]int{}
	if len(ids) == 0 || viewerID == "" {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `SELECT post_id, vote FROM channel_post_votes
		WHERE channel_id::text = $1 AND post_id = ANY($2) AND user_id::text = $3`, channelID, ids, viewerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var v int
		if err := rows.Scan(&id, &v); err != nil {
			return nil, err
		}
		out[id] = v
	}
	return out, rows.Err()
}

// Level0Comment — комментарий к сообщению уровня 0 группы или сообщества.
type Level0Comment struct {
	CommentID       int64
	AuthorID        string
	Text            string
	Body            []byte
	CreatedAtUnixMs int64
}

// AddLevel0Comment — комментарий к сообщению «Описания». Права, настройку и существование проверяет вызывающий.
func AddLevel0Comment(ctx context.Context, s *Store, kind, entityID string, messageID int64, authorID, text string, body []byte) (int64, error) {
	var id int64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO level0_comments (kind, entity_id, message_id, author_id, text, body, created_at_unix_ms)
		VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING comment_id`,
		kind, entityID, messageID, authorID, text, body, time.Now().UnixMilli()).Scan(&id)
	return id, err
}

// Level0Comments — комментарии к сообщению «Описания» по возрастанию, после номера `after`.
func Level0Comments(ctx context.Context, s *Store, kind, entityID string, messageID, after int64, limit int) ([]Level0Comment, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT comment_id, author_id::text, text, body, created_at_unix_ms FROM level0_comments
		 WHERE kind = $1 AND entity_id::text = $2 AND message_id = $3 AND comment_id > $4 AND NOT deleted
		 ORDER BY comment_id LIMIT $5`, kind, entityID, messageID, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Level0Comment
	for rows.Next() {
		var c Level0Comment
		if err := rows.Scan(&c.CommentID, &c.AuthorID, &c.Text, &c.Body, &c.CreatedAtUnixMs); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// Level0CommentCounts — число комментариев у сообщений «Описания»: номер → число; без комментариев — нет в ответе.
func Level0CommentCounts(ctx context.Context, s *Store, kind, entityID string, ids []int64) (map[int64]int, error) {
	out := map[int64]int{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `SELECT message_id, count(*)::int FROM level0_comments
		WHERE kind = $1 AND entity_id::text = $2 AND message_id = ANY($3) AND NOT deleted GROUP BY message_id`,
		kind, entityID, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}

// ShowcaseSettings — настройки «Описания» сущности (§1.18, 11): целиком (иначе обрезкой) и комментарии к уровню 0.
type ShowcaseSettings struct {
	Whole    bool `json:"whole"`
	Comments bool `json:"comments"`
}

// ShowcaseSettingsOf — настройки «Описания»; ErrEntityNotFound — сущности нет.
func ShowcaseSettingsOf(ctx context.Context, s *Store, kind, id string) (ShowcaseSettings, error) {
	table, key := entityTable(kind)
	var out ShowcaseSettings
	err := s.pool.QueryRow(ctx, `SELECT showcase_whole, showcase_comments FROM `+table+`
		WHERE `+key+`::text = $1 AND deleted_at IS NULL`, id).Scan(&out.Whole, &out.Comments)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return out, ErrEntityNotFound
	}
	return out, err
}

// SetShowcaseSettings — поменять настройки «Описания»; nil — оставить. Права проверяет вызывающий.
func SetShowcaseSettings(ctx context.Context, s *Store, kind, id string, whole, comments *bool) (ShowcaseSettings, error) {
	table, key := entityTable(kind)
	var out ShowcaseSettings
	err := s.pool.QueryRow(ctx, `UPDATE `+table+`
		   SET showcase_whole = COALESCE($2, showcase_whole), showcase_comments = COALESCE($3, showcase_comments)
		 WHERE `+key+`::text = $1 AND deleted_at IS NULL
		RETURNING showcase_whole, showcase_comments`, id, whole, comments).Scan(&out.Whole, &out.Comments)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return out, ErrEntityNotFound
	}
	return out, err
}
