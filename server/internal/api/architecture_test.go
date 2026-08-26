package api

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// Бюджеты общих мест: сколько методов висит на едином receiver.
//
// Каждый handler, объявленный методом *Server, видит все 19 полей структуры — и
// значит любая новая функция обязана вписаться строкой в общий тип, который правят
// все. Два разработчика, делающие каналы и звонки, не пересекаются по смыслу, но
// обязательно пересекутся здесь.
//
// Программа архитектурных изменений выносит handler-ы в registrar-файлы со своими
// узкими интерфейсами. Бюджет — измеритель этого движения: он может только
// уменьшаться. Понижать его обязан тот же коммит, который вынес группу.
const (
	// 89 → 83: каналы уехали в channels.go свободными функциями (шаг 4).
	// 83 → 68: звонки, групповые звонки и аудио-комнаты — туда же.
	// 68 → 65: медиа.
	// 65 → 47: группы — состав, сообщения, ключи.
	// 47 → 40: устройства и привязка.
	// 40 → 34: чаты — архив, копии, восстановление.
	// 34 → 26: люди и аккаунт.
	// 26 → 22: личные сообщения и состояния — последняя группа шага 4.
	serverMethodBudget = 22
	storeMethodBudget  = 132
	// 71 → 65: семь маршрутов каналов свернулись в один RegisterChannels.
	routeBudget = 65
)

var (
	serverMethodRe = regexp.MustCompile(`(?m)^func \(s \*Server\)`)
	storeMethodRe  = regexp.MustCompile(`(?m)^func \(s \*Store\)`)
	routeRe        = regexp.MustCompile(`mux\.HandleFunc`)
)

// TestArchitectureBudgets намеренно НЕ вызывает setup(t).
//
// Тесты пакета уходят в t.Skipf, когда рядом нет базы, — и архитектурная проверка,
// написанная через setup, молча пропускалась бы на любой машине без Postgres. Здесь
// читаются исходники, база не нужна вовсе, и пропуска быть не может.
func TestArchitectureBudgets(t *testing.T) {
	cases := []struct {
		name    string
		dir     string
		pattern *regexp.Regexp
		budget  int
		why     string
	}{
		{
			name:    "методы *Server",
			dir:     ".",
			pattern: serverMethodRe,
			budget:  serverMethodBudget,
			why: "handler на общем receiver видит все поля Server. " +
				"Новый endpoint — это файл-registrar со своим узким интерфейсом, " +
				"а не ещё один метод общего типа.",
		},
		{
			name:    "методы *Store",
			dir:     "../store",
			pattern: storeMethodRe,
			budget:  storeMethodBudget,
			why: "API вызывает 106 из 132 методов Store и передаёт тот же " +
				"конкретный тип worker-у. Потребитель обязан объявлять узкий " +
				"интерфейс рядом с собой, а не получать всё хранилище.",
		},
	}

	for _, tc := range cases {
		got, err := countIn(tc.dir, tc.pattern)
		if err != nil {
			t.Fatalf("%s: %v", tc.name, err)
		}
		if got > tc.budget {
			t.Errorf("%s: стало %d при бюджете %d — бюджет может только уменьшаться.\n%s",
				tc.name, got, tc.budget, tc.why)
		}
		if got < tc.budget {
			t.Errorf("%s: стало %d при бюджете %d — понизьте бюджет тем же коммитом, "+
				"иначе он перестанет что-либо значить", tc.name, got, tc.budget)
		}
	}

	routes, err := countInFile("server.go", routeRe)
	if err != nil {
		t.Fatalf("маршруты: %v", err)
	}
	if routes > routeBudget {
		t.Errorf("маршрутов в Register стало %d при бюджете %d: Register — таблица "+
			"вызовов Register<Группа>, а не список из семидесяти строк",
			routes, routeBudget)
	}
}

func countIn(dir string, pattern *regexp.Regexp) (int, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0, err
	}
	total := 0
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		got, err := countInFile(filepath.Join(dir, name), pattern)
		if err != nil {
			return 0, err
		}
		total += got
	}
	return total, nil
}

func countInFile(path string, pattern *regexp.Regexp) (int, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	return len(pattern.FindAllIndex(data, -1)), nil
}
