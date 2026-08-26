// Идентификатор личной переписки: сервер выводит его сам и сверяет с присланным.
//
// **Раскладка нормативна и продублирована байт-в-байт с Kotlin**
// (`messenger-crypto/PersonalChatId.kt`). Тот же случай, что у подписи привязки
// устройства: две реализации одного правила, и расхождение между ними — красный
// билд, а не «на сервере чуть иначе».
package api

import (
	"crypto/sha256"
	"encoding/hex"
)

// personalChatLabel — доменная метка. Без неё тот же хэш годился бы для другой роли.
const personalChatLabel = "tima.personal.chat|"

// personalChatID — идентификатор переписки двоих.
//
// Порядок участников не важен: пара сортируется, поэтому «А пишет Б» и «Б пишет А»
// дают один и тот же чат. Биты версии и варианта выставляются, чтобы получился
// валидный UUID, а не просто шестнадцатеричная строка: он ложится в столбцы типа
// uuid.
func personalChatID(userA, userB string) string {
	if userA == "" || userB == "" {
		return ""
	}
	lower, upper := userA, userB
	if userB < userA {
		lower, upper = userB, userA
	}
	sum := sha256.Sum256([]byte(personalChatLabel + lower + "|" + upper))
	h := sum[:16]
	h[6] = (h[6] & 0x0f) | 0x40
	h[8] = (h[8] & 0x3f) | 0x80
	s := hex.EncodeToString(h)
	return s[0:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:32]
}
