// Заведомо плохой файл раздела: он доказывает, что два заградительных правила ловят.
//
// Оба правила сегодня не нарушены ни разу — потому и нужен образец. Правило, у
// которого нет ни одного срабатывания, невозможно отличить от выключенного, а
// заградительные правила именно такими и живут: годами, до первой чужой строки.
//
// Лежит в src/test/fixtures/, вне наборов исходников: не компилируется и в общую
// проверку не попадает.
package bad

import io.tima.core.network.GroupsOverHttp
import io.tima.feature.chat.ChatStore

class SampleFeature(val foreignScreen: ChatStore, val transport: GroupsOverHttp)
