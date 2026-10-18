package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** «С оформл.» (Решения §5): из HTML — только текст и простое оформление знаками. */
class HtmlToMarksTest {

    private fun md(html: String) = HtmlToMarks.convert(html)

    @Test
    fun оформление_внутри_строки() {
        assertEquals("сбор в **9:00** у *входа*", md("<p>сбор в <b>9:00</b> у <em>входа</em></p>"))
        assertEquals("a ++b++ ~~c~~ ==d== `e`", md("a <u>b</u> <s>c</s> <mark>d</mark> <code>e</code>"))
        assertEquals("слово **жирное** дальше", md("слово<strong> жирное </strong>дальше"), "пробелы — снаружи знаков")
        assertEquals("**a *b***", md("<b>a <i>b</b></i>"), "кривая вложенность закрывается целиком")
    }

    @Test
    fun ссылки_и_картинки_без_адресов() {
        assertEquals("читать тут", md("""читать <a href="https://evil.example">тут</a><img src="x.png">"""))
        assertEquals("текст", md("<script>alert(1)</script><style>p{}</style>текст"))
    }

    @Test
    fun заголовки_списки_цитаты() {
        val html = "<h1>План</h1><p>вводная</p><ul><li>раз</li><li>два<ul><li>вложенный</li></ul></li></ul><blockquote>цитата</blockquote><h5>мелкий</h5>"
        assertEquals("# План\nвводная\n- раз\n- два\n    - вложенный\n--> цитата\n### мелкий", md(html))
    }

    @Test
    fun код_и_таблица() {
        assertEquals("```\nfun x() {\n  *y*\n}\n```", md("<pre>fun x() {\n  *y*\n}</pre>"), "в коде знаки не трогаются")
        val table = "<table><tr><th>Кто</th><th>Когда</th></tr><tr><td><b>Аня</b></td><td>9|10</td></tr></table>"
        assertEquals("| Кто | Когда |\n|---|---|\n| **Аня** | 9\\|10 |", md(table))
    }

    @Test
    fun знаки_текста_экранируются_и_сущности_раскрываются() {
        assertEquals("2\\*3\\*4 &lt; 5", md("2*3*4 &amp;lt; 5"))
        assertEquals("\\# не заголовок", md("<p># не заголовок</p>"))
        assertEquals("\\- не пункт", md("- не пункт"))
        assertEquals("«ёлка» — 😀", md("&laquo;ёлка&raquo; &mdash; &#x1F600;"))
        assertEquals("фрагмент", md("<html><body>шум<!--StartFragment-->фрагмент<!--EndFragment-->шум</body></html>"))
    }
}
