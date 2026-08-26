package api

import "testing"

// Те же известные ответы, что у Kotlin (`PersonalChatIdTest.kt`).
//
// Числа здесь не «то, что вернул наш код»: они посчитаны сторонней реализацией
// (SHA-256 плюс биты UUID) и совпали с котлиновскими эталонами до того, как этот файл
// был написан. Разойдись две стороны — клиент выведет один идентификатор, сервер
// другой, и переписка не соберётся ни у кого, а выглядеть это будет как «сообщения не
// доходят».
func TestPersonalChatIDСовпадаетСKotlin(t *testing.T) {
	const (
		a = "0f8fad5b-d9cb-469f-a165-70867728950e"
		b = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
	)
	if got := personalChatID(a, b); got != "ec3863e2-d7fc-4806-b1a5-19b0bf1cde1c" {
		t.Fatalf("пара: %s", got)
	}
	// Чат с самим собой — отдельный идентификатор, а не пустой и не тот же.
	if got := personalChatID(a, a); got != "af2cf156-ee4d-49c0-8254-648e41ae977d" {
		t.Fatalf("сам с собой: %s", got)
	}
}

func TestPersonalChatIDПорядокНеВажен(t *testing.T) {
	const (
		a = "0f8fad5b-d9cb-469f-a165-70867728950e"
		b = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
	)
	if personalChatID(a, b) != personalChatID(b, a) {
		t.Fatal("порядок участников изменил идентификатор — у двоих будут разные чаты")
	}
	if personalChatID("", b) != "" || personalChatID(a, "") != "" {
		t.Fatal("пустой участник обязан давать пустой идентификатор, а не хэш от пустоты")
	}
}
