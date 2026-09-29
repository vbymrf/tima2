package api

import (
	"strconv"
)

// VideoLimits — потолок видео звонка, который задаёт сервер (ПЛАН-ВИДЕО.md В5б, решение
// заказчика 2026-09-29).
//
// ── ЗАЧЕМ НА СЕРВЕРЕ ─────────────────────────────────────────────────────────
//
// Раньше потолок был зашит в приложение, и поменять его значило выпустить новую
// версию на все телефоны. Отсюда он меняется между звонками: следующий звонок идёт с
// новым. Ниже потолка качеством управляет WebRTC сам — это не настройка качества, а
// граница, выше которой он не поднимется.
//
// Поле ответа новое и необязательное: приложение, не знающее его, берёт своё
// умолчание — те же числа ([DefaultVideoLimits]).
type VideoLimits struct {
	Width   int `json:"width"`
	Height  int `json:"height"`
	FPS     int `json:"fps"`
	Bitrate int `json:"bitrate"`
}

// DefaultVideoLimits — 1280×720, 24 кадра/с, 800 кбит/с (решение заказчика 2026-09-29).
var DefaultVideoLimits = VideoLimits{Width: 1280, Height: 720, FPS: 24, Bitrate: 800_000}

// VideoLimitsFromEnv — потолок из переменных CALL_VIDEO_WIDTH, CALL_VIDEO_HEIGHT,
// CALL_VIDEO_FPS, CALL_VIDEO_BITRATE. Пустое или негодное значение — умолчание этого
// поля: опечатка в одной переменной не должна обнулить видео у всех звонков.
func VideoLimitsFromEnv(get func(string) string) VideoLimits {
	limits := DefaultVideoLimits
	pick := func(name string, into *int, least int) {
		if v, err := strconv.Atoi(get(name)); err == nil && v >= least {
			*into = v
		}
	}
	pick("CALL_VIDEO_WIDTH", &limits.Width, 16)
	pick("CALL_VIDEO_HEIGHT", &limits.Height, 16)
	pick("CALL_VIDEO_FPS", &limits.FPS, 1)
	pick("CALL_VIDEO_BITRATE", &limits.Bitrate, 50_000)
	return limits
}

// orDefault — потолок, заданный сервером, или умолчание, если его не задали вовсе
// (тесты и сервер, собранный без cmd/tima, оставляют поле нулевым).
func (v VideoLimits) orDefault() VideoLimits {
	if v == (VideoLimits{}) {
		return DefaultVideoLimits
	}
	return v
}
