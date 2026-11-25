// Название и описание сущности (doc_mig/ПЛАН-(ВС)-ВИДА-СПИСКОВ-И-КАТАЛОГА.md, 10–11; заказчик 2026-11-20). Отдельными
// функциями, а не методами `*Store`: у хранилища бюджет методов (architecture_test).
package store

import (
	"context"
	"errors"
)

// MaxAboutRunes — предел описания: тот же, что у описания при создании группы (клиент, MAX_DESCRIPTION_LENGTH).
const MaxAboutRunes = 4096

// ErrEntityNotFound — сущности с таким номером нет (или удалена).
var ErrEntityNotFound = errors.New("сущность не найдена")

// aboutColumn — где лежит описание: у группы и канала — `description` (с создания), у сообщества — `about` (0090).
func aboutColumn(kind string) string {
	if kind == "community" {
		return "about"
	}
	return "description"
}

// EntityAbouts — номер → описание; пустых нет в ответе. Описание открыто, как название (решение 10): секретная
// сущность отличается доступом к сообщениям, а не к описанию.
func EntityAbouts(ctx context.Context, s *Store, kind string, ids []string) (map[string]string, error) {
	out := map[string]string{}
	if len(ids) == 0 {
		return out, nil
	}
	table, key := entityTable(kind)
	col := aboutColumn(kind)
	rows, err := s.pool.Query(ctx, `
		SELECT `+key+`::text, COALESCE(`+col+`, '') FROM `+table+`
		 WHERE `+key+`::text = ANY($1) AND COALESCE(`+col+`, '') <> '' AND deleted_at IS NULL`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, about string
		if err := rows.Scan(&id, &about); err != nil {
			return nil, err
		}
		out[id] = about
	}
	return out, rows.Err()
}

// EntityTitles — названия несекретных сущностей по номерам (2026-11-25): «Описание», открытое ссылкой, не
// вступившему показывает название в шапке. Секретные и удалённые в ответ не попадают — их название знают только свои,
// и у своих оно есть в списке «Социума».
func EntityTitles(ctx context.Context, s *Store, kind string, ids []string) (map[string]string, error) {
	out := map[string]string{}
	if len(ids) == 0 {
		return out, nil
	}
	table, key := entityTable(kind)
	rows, err := s.pool.Query(ctx, `
		SELECT `+key+`::text, title FROM `+table+`
		 WHERE `+key+`::text = ANY($1) AND NOT hidden AND deleted_at IS NULL`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, title string
		if err := rows.Scan(&id, &title); err != nil {
			return nil, err
		}
		out[id] = title
	}
	return out, rows.Err()
}

// SetEntityAbout — название (если не nil) и описание (если не nil) одной записью. Права проверяет вызывающий.
func SetEntityAbout(ctx context.Context, s *Store, kind, id string, title, about *string) error {
	table, key := entityTable(kind)
	col := aboutColumn(kind)
	tag, err := s.pool.Exec(ctx, `
		UPDATE `+table+` SET title = COALESCE($2, title), `+col+` = COALESCE($3, `+col+`)
		 WHERE `+key+`::text = $1 AND deleted_at IS NULL`, id, title, about)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrEntityNotFound
	}
	return nil
}
