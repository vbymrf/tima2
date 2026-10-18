// Поиск по открытым уровням (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §12): группа — по первому узлу текста,
// записанному при приёме; канал — по тексту записи.
package store

import (
	"context"
	"strings"
)

// SearchKey — ключ поиска: строчными по правилам Unicode, на сервере, а не в базе (см. 0075).
func SearchKey(text string) string { return strings.ToLower(strings.TrimSpace(text)) }

// SearchHit — найденное: номер, строка, время написания.
type SearchHit struct {
	Number int64
	Text   string
	AtMs   int64
}

// likeEscape — знаки шаблона LIKE в запросе человека — буквами, а не шаблоном.
func likeEscape(q string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q)
}

// SearchOpen — открытое (уровень 0…maxLevel) с запросом в тексте, новые первыми. channel=false —
// группа (container = group_id), true — канал. Один метод на оба вида: вопрос один.
func (s *Store) SearchOpen(ctx context.Context, channel bool, container, q string, maxLevel int16, limit int) ([]SearchHit, error) {
	sql := `SELECT message_id, search_text, created_at_unix_ms FROM group_messages
		WHERE group_id = $1 AND NOT deleted AND edit_of = 0 AND NOT only_continuation AND level BETWEEN 0 AND $3
		  AND search_key LIKE '%' || $2 || '%'
		ORDER BY message_id DESC LIMIT $4`
	if channel {
		sql = `SELECT post_id, left(text, 300), created_at_unix_ms FROM channel_posts
		WHERE channel_id = $1 AND NOT deleted AND parent_post_id IS NULL AND NOT only_continuation AND level BETWEEN 0 AND $3
		  AND search_key LIKE '%' || $2 || '%'
		ORDER BY post_id DESC LIMIT $4`
	}
	rows, err := s.pool.Query(ctx, sql, container, likeEscape(SearchKey(q)), maxLevel, limit)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []SearchHit
	for rows.Next() {
		var h SearchHit
		if err := rows.Scan(&h.Number, &h.Text, &h.AtMs); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// postSearchText — текст записи канала для поиска: все узлы (запись открыта целиком), без узлов — text.
func postSearchText(p ChannelPost) string {
	if len(p.Nodes) > 0 {
		return strings.Join(p.Nodes, "\n")
	}
	return p.Text
}
