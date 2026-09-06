// Отчёты о проблеме, присланные с устройств (ПЛАН-ОТЛАДКИ.md, Б4).
package store

import (
	"context"
	"time"
)

// ProblemReport — отчёт, как он ложится в базу.
//
// Тип живёт здесь, а не в `api`, по простой причине: `api` уже импортирует `store`, и
// обратная ссылка замкнула бы круг. Идентификаторы аккаунта и устройства проставляет
// вызывающий — из токена, а не из тела запроса.
type ProblemReport struct {
	Number   string
	UserID   string
	DeviceID string
	Kind     string
	Body     string
	Origin   string
	Platform string
	Model    string
	OS       string
	Build    string
	Stream   string
	Nickname string
	Log      string
	FromAddr string
}

// SaveProblemReport кладёт отчёт и возвращает его короткий номер.
//
// **Идентификаторы личности и устройства пишутся как есть — то есть теми, что сервер
// достал из токена.** Пустые строки законны: отчёт принимается и без входа, и такой
// отчёт ложится неопознанным. NULL вместо пустой строки нужен потому, что на колонках
// стоят внешние ключи: пустая строка не UUID и не сошлась бы с `users`.
func (s *Store) SaveProblemReport(ctx context.Context, report ProblemReport) (string, error) {
	var number string
	err := s.pool.QueryRow(ctx, `
        INSERT INTO problem_reports (
            number, user_id, device_id, kind, body, origin,
            platform, model, os, build, stream, nickname, log, from_addr
        ) VALUES (
            $1, NULLIF($2,'')::uuid, NULLIF($3,'')::uuid, $4, $5, $6,
            $7, $8, $9, $10, $11, $12, $13, $14
        )
        RETURNING number
    `,
		report.Number, report.UserID, report.DeviceID,
		report.Kind, report.Body, report.Origin,
		report.Platform, report.Model, report.OS, report.Build, report.Stream,
		report.Nickname, report.Log, report.FromAddr,
	).Scan(&number)
	return number, err
}

// CountProblemReportsFrom считает неопознанные отчёты с адреса за окно.
//
// Считаются только те, у которых нет входа: предел частоты защищает ручку от мусора
// без входа, а у вошедшего злоупотребление видно по его же аккаунту, и закрывать ему
// отправку значит терять настоящие жалобы.
func (s *Store) CountProblemReportsFrom(ctx context.Context, addr string, since time.Time) (int, error) {
	var count int
	err := s.pool.QueryRow(ctx, `
        SELECT count(*) FROM problem_reports
        WHERE from_addr = $1 AND user_id IS NULL AND created_at >= $2
    `, addr, since).Scan(&count)
	return count, err
}
