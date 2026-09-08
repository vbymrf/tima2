// Комментарии в каналах и лентах (ADR-0024, ПЛАН-КАНАЛОВ К1).
//
// Комментарий — обычная запись канала, у которой заполнен parent_post_id. Своей таблицы,
// своего вида и своего права у него нет: круг берётся у корня в момент выдачи, а не
// копируется при записи, — иначе сужение записи задним числом (ADR-0019 §6) оставило бы
// разговор под закрытой записью открытым.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ErrCommentRoot — корня нет, он удалён или он сам комментарий.
//
// Один ответ на три случая намеренно: «корень удалён» и «корень — комментарий» различает
// только тот, кто уже видит таблицу, а для пишущего это одно и то же — отвечать не на что.
var ErrCommentRoot = errors.New("корня для комментария нет")

// GetPost — одна запись канала: нужна, чтобы посчитать круг корня перед выдачей разговора.
//
// Удалённая не отдаётся: у неё нет и комментариев (ADR-0024 §9 — сироты выглядят как
// поломка, и это единственный случай, где каскад честнее пометки).
func (s *Store) GetPost(ctx context.Context, channelID string, postID uint64) (ChannelPost, error) {
	var p ChannelPost
	var parent *int64
	err := s.pool.QueryRow(ctx, `
		SELECT channel_id, post_id, author_id, text, nodes, markup, markup_version,
		       created_at_unix_ms, level, parent_post_id
		  FROM channel_posts
		 WHERE channel_id = $1 AND post_id = $2 AND NOT deleted`,
		channelID, postID).Scan(&p.ChannelID, &p.PostID, &p.AuthorID, &p.Text, &p.Nodes,
		&p.Markup, &p.MarkupVersion, &p.CreatedAtUnixMs, &p.Level, &parent)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return ChannelPost{}, ErrChannelNotFound
	}
	if parent != nil {
		p.ParentPostID = uint64(*parent)
	}
	return p, err
}

// CreateComment кладёт комментарий к записи rootID.
//
// Корень проверяется **внутри вставки**, а не чтением перед ней: два условия — «корень
// жив» и «корень сам не комментарий» — обязаны выполняться в тот же момент, когда строка
// появляется. Отдельный SELECT дал бы окно, в котором корень успевают удалить.
//
// Уровень у комментария не заполняется и не читается: колонка `level` остаётся с
// умолчанием 1 и ничего не значит. Круг считается по корню (ADR-0024 §2).
func (s *Store) CreateComment(ctx context.Context, p ChannelPost, rootID uint64) (uint64, error) {
	var markupParam any
	if len(p.Markup) > 0 {
		markupParam = string(p.Markup)
	}
	var id uint64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO channel_posts
		    (channel_id, author_id, text, nodes, markup, markup_version, created_at_unix_ms, parent_post_id)
		SELECT $1, $2, $3, $4, $5, $6, $7, $8
		 WHERE EXISTS (
		     SELECT 1 FROM channel_posts r
		      WHERE r.channel_id = $1 AND r.post_id = $8
		        AND r.parent_post_id IS NULL AND NOT r.deleted)
		RETURNING post_id`,
		p.ChannelID, p.AuthorID, p.Text, p.Nodes, markupParam, p.MarkupVersion,
		p.CreatedAtUnixMs, rootID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return 0, ErrCommentRoot
	}
	return id, err
}

// ListComments — разговор под записью, старые сверху.
//
// Порядок обратен ленте, и это решение заказчика 2026-09-08: разговор читают с начала, а
// ленту с конца. Отсюда и постраничность «после», а не «до».
func (s *Store) ListComments(ctx context.Context, channelID string, rootID, after uint64, limit int) ([]ChannelPost, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows, err := s.pool.Query(ctx, `
		SELECT channel_id, post_id, author_id, text, nodes, markup, markup_version,
		       created_at_unix_ms, parent_post_id
		  FROM channel_posts
		 WHERE channel_id = $1 AND parent_post_id = $2 AND post_id > $3 AND NOT deleted
		 ORDER BY post_id ASC LIMIT $4`, channelID, rootID, after, limit)
	if err != nil {
		if isBadUUID(err) {
			return nil, ErrChannelNotFound
		}
		return nil, err
	}
	defer rows.Close()
	var out []ChannelPost
	for rows.Next() {
		var p ChannelPost
		var parent *int64
		if err := rows.Scan(&p.ChannelID, &p.PostID, &p.AuthorID, &p.Text, &p.Nodes, &p.Markup,
			&p.MarkupVersion, &p.CreatedAtUnixMs, &parent); err != nil {
			return nil, err
		}
		if parent != nil {
			p.ParentPostID = uint64(*parent)
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// CommentCounts — число комментариев у перечисленных записей, ОДНИМ запросом.
//
// Иначе лента из двадцати записей превращается в двадцать один запрос (ADR-0024,
// следствие 4). Записи без комментариев в ответе отсутствуют — ноль подставляет
// вызывающий, и это дешевле, чем возвращать нули строками.
//
// Считаются все живые комментарии корня: круг у них не свой, а корневой, — значит тому,
// кому корень показан, показаны и они все. «Считает видимые, а не все» выполняется тем,
// что счётчик спрашивают только для записей, уже прошедших выдачу.
func (s *Store) CommentCounts(ctx context.Context, channelID string, rootIDs []uint64) (map[uint64]int, error) {
	out := make(map[uint64]int, len(rootIDs))
	if len(rootIDs) == 0 {
		return out, nil
	}
	ids := make([]int64, 0, len(rootIDs))
	for _, id := range rootIDs {
		ids = append(ids, int64(id))
	}
	rows, err := s.pool.Query(ctx, `
		SELECT parent_post_id, count(*)
		  FROM channel_posts
		 WHERE channel_id = $1 AND parent_post_id = ANY($2) AND NOT deleted
		 GROUP BY parent_post_id`, channelID, ids)
	if err != nil {
		if isBadUUID(err) {
			return out, nil
		}
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var root int64
		var n int
		if err := rows.Scan(&root, &n); err != nil {
			return nil, err
		}
		out[uint64(root)] = n
	}
	return out, rows.Err()
}
