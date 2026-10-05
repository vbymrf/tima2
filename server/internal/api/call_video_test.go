package api

import "testing"

// Потолок видео звонка: умолчание, настройка и опечатка (ПЛАН-(В)-ВИДЕО.md В5б).
func TestVideoLimitsFromEnv(t *testing.T) {
	env := func(values map[string]string) func(string) string {
		return func(name string) string { return values[name] }
	}

	if got := VideoLimitsFromEnv(env(nil)); got != DefaultVideoLimits {
		t.Fatalf("без переменных — умолчание, а вышло %+v", got)
	}
	if DefaultVideoLimits != (VideoLimits{Width: 1280, Height: 720, FPS: 24, Bitrate: 800_000}) {
		t.Fatalf("умолчание — решение заказчика 1280×720, 24, 800 кбит/с, а стоит %+v", DefaultVideoLimits)
	}

	got := VideoLimitsFromEnv(env(map[string]string{
		"CALL_VIDEO_WIDTH": "640", "CALL_VIDEO_HEIGHT": "480", "CALL_VIDEO_FPS": "15", "CALL_VIDEO_BITRATE": "500000",
	}))
	if got != (VideoLimits{Width: 640, Height: 480, FPS: 15, Bitrate: 500_000}) {
		t.Fatalf("заданное не взято: %+v", got)
	}

	// Опечатка в одной переменной не обнуляет видео: остальные поля — как заданы.
	got = VideoLimitsFromEnv(env(map[string]string{"CALL_VIDEO_FPS": "двадцать", "CALL_VIDEO_BITRATE": "0"}))
	if got.FPS != DefaultVideoLimits.FPS || got.Bitrate != DefaultVideoLimits.Bitrate {
		t.Fatalf("негодное значение должно дать умолчание поля: %+v", got)
	}
}

func TestVideoLimitsOrDefault(t *testing.T) {
	if (VideoLimits{}).orDefault() != DefaultVideoLimits {
		t.Fatal("незаданный потолок — умолчание")
	}
	own := VideoLimits{Width: 640, Height: 360, FPS: 30, Bitrate: 1_000_000}
	if own.orDefault() != own {
		t.Fatal("заданный потолок не подменяется")
	}
}
