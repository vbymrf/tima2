// Разделение секрета по Шамиру в GF(256) (полином AES 0x11b) — авторизация
// unseal-операции M-of-N (escrow-legal-access.md §3: минимум 3-of-5).
// Формат доли ФИКСИРОВАН (доли на руках у людей и переживают версии кода):
//   tima-escrow-share-v1:<x>:<base64url(payload)>
// где x — точка доли (1..n), payload — по байту значения полинома на каждый
// байт секрета.
package escrow

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

const sharePrefix = "tima-escrow-share-v1"

// gfMul — умножение в GF(2^8) с приведением по x^8+x^4+x^3+x+1 (0x11b).
func gfMul(a, b byte) byte {
	var p byte
	for b > 0 {
		if b&1 == 1 {
			p ^= a
		}
		hi := a & 0x80
		a <<= 1
		if hi != 0 {
			a ^= 0x1b
		}
		b >>= 1
	}
	return p
}

// gfInv — обратный элемент: a^254 (a^255 = 1 для a ≠ 0).
func gfInv(a byte) byte {
	if a == 0 {
		panic("gfInv(0)") // не достигается: точки долей ненулевые и различны
	}
	// a^254 = a^2 * a^4 * a^8 * ... * a^128 * a^2? — считаем степенно
	result := byte(1)
	base := a
	for e := 254; e > 0; e >>= 1 {
		if e&1 == 1 {
			result = gfMul(result, base)
		}
		base = gfMul(base, base)
	}
	return result
}

// SplitSecret делит secret на n долей с порогом k (k ≤ n ≤ 255).
func SplitSecret(secret []byte, n, k int) ([]string, error) {
	if k < 2 || n < k || n > 255 {
		return nil, fmt.Errorf("некорректные параметры Шамира: n=%d, k=%d", n, k)
	}
	// Полином на каждый байт секрета: a0 = байт, a1..a(k-1) случайные
	coeffs := make([][]byte, len(secret)) // coeffs[i] = [a1..a(k-1)] для байта i
	for i := range coeffs {
		c := make([]byte, k-1)
		if _, err := rand.Read(c); err != nil {
			return nil, err
		}
		coeffs[i] = c
	}
	shares := make([]string, 0, n)
	for x := 1; x <= n; x++ {
		payload := make([]byte, len(secret))
		for i, s := range secret {
			// Горнер: (((a(k-1)·x + a(k-2))·x + …)·x + a0
			y := byte(0)
			for j := k - 2; j >= 0; j-- {
				y = gfMul(y, byte(x)) ^ coeffs[i][j]
			}
			payload[i] = gfMul(y, byte(x)) ^ s
		}
		shares = append(shares,
			fmt.Sprintf("%s:%d:%s", sharePrefix, x, base64.RawURLEncoding.EncodeToString(payload)))
	}
	return shares, nil
}

var ErrBadShares = errors.New("доли некорректны или их меньше порога")

// CombineShares восстанавливает секрет интерполяцией Лагранжа в точке 0.
// Количество долей должно быть ровно порогом k (лишние — отрезать у вызывающего).
func CombineShares(shares []string) ([]byte, error) {
	type point struct {
		x    byte
		data []byte
	}
	pts := make([]point, 0, len(shares))
	seen := map[byte]bool{}
	var size int
	for _, s := range shares {
		parts := strings.Split(strings.TrimSpace(s), ":")
		if len(parts) != 3 || parts[0] != sharePrefix {
			return nil, fmt.Errorf("%w: не формат %s", ErrBadShares, sharePrefix)
		}
		x, err := strconv.Atoi(parts[1])
		if err != nil || x < 1 || x > 255 || seen[byte(x)] {
			return nil, fmt.Errorf("%w: точка доли", ErrBadShares)
		}
		data, err := base64.RawURLEncoding.DecodeString(parts[2])
		if err != nil || len(data) == 0 || (size != 0 && len(data) != size) {
			return nil, fmt.Errorf("%w: payload доли", ErrBadShares)
		}
		size = len(data)
		seen[byte(x)] = true
		pts = append(pts, point{byte(x), data})
	}
	if len(pts) < 2 {
		return nil, ErrBadShares
	}
	secret := make([]byte, size)
	for i := range secret {
		var y byte
		for a := range pts {
			// Коэффициент Лагранжа в 0: ∏_{b≠a} x_b / (x_b ⊕ x_a)
			num, den := byte(1), byte(1)
			for b := range pts {
				if b == a {
					continue
				}
				num = gfMul(num, pts[b].x)
				den = gfMul(den, pts[b].x^pts[a].x)
			}
			y ^= gfMul(pts[a].data[i], gfMul(num, gfInv(den)))
		}
		secret[i] = y
	}
	return secret, nil
}
