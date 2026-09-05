package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Trouble

/**
 * Виртуальные аккаунты — ПЛАН-КОНТАКТОВ.md, Д10…Д12.
 *
 * Одно место на три действия: завести, передать, принять. Разводить их по разным углам
 * приложения значило бы прятать: заводят виртуальный аккаунт редко, а ищут его там, где
 * видели остальные.
 *
 * **Про анонимность сказано честно и здесь тоже.** Виртуальный аккаунт прячет связь с
 * основным от собеседников, а не от нас: привязка лежит на сервере открыто, иначе он не
 * смог бы её проверять.
 */
@Composable
fun VirtualsScreen(
    state: VirtualsState,
    onCreate: () -> Unit,
    /** Передать этот аккаунт другому человеку. */
    onGive: (String) -> Unit,
    onTake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3)) {
            Secondary(
                "Виртуальный аккаунт — отдельный пользователь: своя переписка, свои ключи, " +
                    "своя секретная фраза. Телефона у него нет, находят его по нику.",
            )
        }

        state.trouble?.let {
            Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4)) { Trouble(it) }
        }

        if (state.accounts.isNotEmpty()) {
            SectionTitle("Ваши аккаунты")
            state.accounts.forEach { account ->
                ListLine(
                    left = { Avatar(letters = account.nickname.take(1).uppercase().ifBlank { "?" }) },
                    middle = {
                        Column {
                            Name(account.nickname.ifBlank { account.userId.take(8) })
                            Tertiary("нет телефона · находят по нику", lineOne = true)
                        }
                    },
                    right = {
                        Button(
                            label = "Передать",
                            onClick = { onGive(account.userId) },
                            kind = ButtonKind.Quiet,
                        )
                    },
                )
            }
        } else if (state.asked && !state.working) {
            // Пустоту называем словами только после ответа сервера: до него она означает
            // «мы ещё не спрашивали», а не «их нет».
            Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4)) {
                Secondary("Виртуальных аккаунтов пока нет.")
            }
        }

        SectionTitle("Действия")
        ListLine(
            onClick = onCreate,
            left = { Avatar(letters = "＋") },
            middle = {
                Column {
                    Name("Завести виртуальный аккаунт")
                    Tertiary("не больше пяти на номер", lineOne = true)
                }
            },
        )
        ListLine(
            onClick = onTake,
            left = { Avatar(letters = "↓") },
            middle = {
                Column {
                    Name("Принять аккаунт")
                    Tertiary("нужны код и фраза от того, кто передаёт", lineOne = true)
                }
            },
        )

        Column(modifier = Modifier.padding(TimaSpacing.about4)) {
            Tertiary(
                "Связь с вашим основным аккаунтом не видна собеседникам. От нас она не " +
                    "скрыта: привязка хранится на сервере — иначе её нечем было бы проверять.",
            )
        }
    }
}
