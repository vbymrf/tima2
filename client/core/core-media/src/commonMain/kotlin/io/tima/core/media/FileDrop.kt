package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Перетаскивание файлов в окно (Решения §10.2: «ПК — перетаскивание файлов в редактор»). На ПК —
 * файлы из проводника, прочитанные целиком; на телефоне перетаскивать нечего — модификатор пустой.
 *
 * @param onHover файл несут над окном (`true`) или унесли (`false`) — подсветить место.
 */
@Composable
expect fun Modifier.fileDrop(onHover: (Boolean) -> Unit = {}, onFiles: (List<PickedFile>) -> Unit): Modifier
