"""Значок приложения для Windows: рисуется кодом, а не приносится картинкой.

Почему кодом. Художника у проекта нет, а значок нужен: без него в меню Пуск и на
панели задач стоит чайник Java, и человек не находит своё приложение среди чужих.
Нарисованный здесь значок — заглушка честная: салатовая плашка приложения и буква
«Т» на ней, те же цвета, что на экране (`core-ui/Tokens.kt`, navigation = #8AC44A).
Придёт настоящий — файл заменят, а этот скрипт скажет, что было до него.

Библиотек нет намеренно: PIL на машине сборки не стоит, а ставить её ради пяти
прямоугольников значило бы завести зависимость, о которой через месяц никто не
вспомнит. ICO собирается из BMP-кадров вручную — формат несложный.

Запуск:  python make-icon.py    (кладёт tima.ico рядом с собой)
"""

import struct
from pathlib import Path

# Салатовый — тот же, что у полосы навигации в приложении.
FILL = (0x8A, 0xC4, 0x4A)
LETTER = (0xFF, 0xFF, 0xFF)
SIZES = (16, 32, 48, 64, 128, 256)


def draw(size):
    """Пиксели кадра: BGRA, строками сверху вниз."""
    rows = []
    radius = max(2, size // 5)
    # Буква «Т»: перекладина сверху, ножка по центру. Доли подобраны так, чтобы на
    # 16 пикселях она ещё читалась, а на 256 не выглядела тонкой.
    bar_top = round(size * 0.26)
    bar_bottom = round(size * 0.40)
    bar_left = round(size * 0.22)
    bar_right = round(size * 0.78)
    stem_left = round(size * 0.43)
    stem_right = round(size * 0.57)
    stem_bottom = round(size * 0.76)

    for y in range(size):
        row = []
        for x in range(size):
            # Скруглённый угол: точка вне четверти круга остаётся прозрачной.
            cx = radius - 1 if x < radius else (size - radius if x >= size - radius else x)
            cy = radius - 1 if y < radius else (size - radius if y >= size - radius else y)
            dx, dy = x - cx, y - cy
            outside = (dx * dx + dy * dy) > radius * radius if (dx or dy) else False
            if outside:
                row.append(bytes(4))
                continue
            on_letter = (
                bar_top <= y < bar_bottom and bar_left <= x < bar_right
            ) or (
                bar_top <= y < stem_bottom and stem_left <= x < stem_right
            )
            b, g, r = (LETTER if on_letter else FILL)[::-1]
            row.append(bytes((b, g, r, 0xFF)))
        rows.append(b"".join(row))
    return rows


def frame(size):
    """Один кадр ICO: BITMAPINFOHEADER + XOR-пиксели + пустая AND-маска."""
    rows = draw(size)
    xor = b"".join(reversed(rows))  # BMP хранит строки снизу вверх
    mask_row = (size + 31) // 32 * 4  # маска — 1 бит на пиксель, строка кратна 4 байтам
    mask = bytes(mask_row * size)
    header = struct.pack(
        "<IiiHHIIiiII",
        40, size, size * 2, 1, 32, 0, len(xor) + len(mask), 0, 0, 0, 0,
    )
    return header + xor + mask


def main():
    frames = [(size, frame(size)) for size in SIZES]
    offset = 6 + 16 * len(frames)
    directory = b""
    body = b""
    for size, data in frames:
        directory += struct.pack(
            "<BBBBHHII",
            0 if size == 256 else size,
            0 if size == 256 else size,
            0, 0, 1, 32, len(data), offset,
        )
        body += data
        offset += len(data)
    out = Path(__file__).with_name("tima.ico")
    out.write_bytes(struct.pack("<HHH", 0, 1, len(frames)) + directory + body)
    print(f"{out} — {out.stat().st_size} байт, кадров {len(frames)}")


if __name__ == "__main__":
    main()
