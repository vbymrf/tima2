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
	// Images — снимки к отчёту (0057, ПЛАН-ВИДЕО.md В6): кадр собеседника, снимок экрана.
	Images []ProblemImage
}

// ProblemImage — один снимок: тип и байты как есть.
type ProblemImage struct {
	Mime string
	Data []byte
}

// SaveProblemReport кладёт отчёт и возвращает его короткий номер.
//
// **Идентификаторы личности и устройства пишутся как есть — то есть теми, что сервер
// достал из токена.** Пустые строки законны: отчёт принимается и без входа, и такой
// отчёт ложится неопознанным. NULL вместо пустой строки нужен потому, что на колонках
// стоят внешние ключи: пустая строка не UUID и не сошлась бы с `users`.
//
// Снимки ложатся в той же транзакции: отчёт без обещанного кадра хуже отчёта, который не
// дошёл, — по нему решат, что беды на картинке не было.
func (s *Store) SaveProblemReport(ctx context.Context, report ProblemReport) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var number, reportID string
	err = tx.QueryRow(ctx, `
        INSERT INTO problem_reports (
            number, user_id, device_id, kind, body, origin,
            platform, model, os, build, stream, nickname, log, from_addr
        ) VALUES (
            $1, NULLIF($2,'')::uuid, NULLIF($3,'')::uuid, $4, $5, $6,
            $7, $8, $9, $10, $11, $12, $13, $14
        )
        RETURNING number, report_id
    `,
		report.Number, report.UserID, report.DeviceID,
		report.Kind, report.Body, report.Origin,
		report.Platform, report.Model, report.OS, report.Build, report.Stream,
		report.Nickname, report.Log, report.FromAddr,
	).Scan(&number, &reportID)
	if err != nil {
		return "", err
	}
	for i, image := range report.Images {
		if _, err := tx.Exec(ctx, `
            INSERT INTO problem_report_images (report_id, position, mime, data)
            VALUES ($1, $2, $3, $4)
        `, reportID, i+1, image.Mime, image.Data); err != nil {
			return "", err
		}
	}
	return number, tx.Commit(ctx)
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

// ProblemReportImages — снимки отчёта по его номеру, в том порядке, в каком их приложили.
// Нужны разбору отчёта (ПЛАН-ОТЛАДКИ.md): кадр собеседника и снимки экрана.
func (s *Store) ProblemReportImages(ctx context.Context, number string) ([]ProblemImage, error) {
	rows, err := s.pool.Query(ctx, `
        SELECT i.mime, i.data
        FROM problem_report_images i
        JOIN problem_reports r ON r.report_id = i.report_id
        WHERE r.number = $1
        ORDER BY i.position
    `, number)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ProblemImage
	for rows.Next() {
		var image ProblemImage
		if err := rows.Scan(&image.Mime, &image.Data); err != nil {
			return nil, err
		}
		out = append(out, image)
	}
	return out, rows.Err()
}
