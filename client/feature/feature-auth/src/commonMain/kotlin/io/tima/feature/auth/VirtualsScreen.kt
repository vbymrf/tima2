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
import io.tima.core.ui.words
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
    val words = Tima.words.auth
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3)) {
            Secondary(words.virtualAboutShort)
        }

        state.trouble?.let {
            Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4)) { Trouble(it) }
        }

        if (state.accounts.isNotEmpty()) {
            SectionTitle(words.yourAccounts)
            state.accounts.forEach { account ->
                ListLine(
                    left = { Avatar(letters = account.nickname.take(1).uppercase().ifBlank { "?" }) },
                    middle = {
                        Column {
                            Name(account.nickname.ifBlank { account.userId.take(8) })
                            Tertiary(words.noPhoneFoundByNickname, lineOne = true)
                        }
                    },
                    right = {
                        Button(
                            label = words.give,
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
                Secondary(words.noVirtualsYet)
            }
        }

        SectionTitle(words.actions)
        ListLine(
            onClick = onCreate,
            left = { Avatar(letters = "＋") },
            middle = {
                Column {
                    Name(words.createVirtual)
                    Tertiary(words.fiveAtMostShort, lineOne = true)
                }
            },
        )
        ListLine(
            onClick = onTake,
            left = { Avatar(letters = "↓") },
            middle = {
                Column {
                    Name(words.takeAccount)
                    Tertiary(words.takeNeedsCodeAndPhrase, lineOne = true)
                }
            },
        )

        Column(modifier = Modifier.padding(TimaSpacing.about4)) {
            Tertiary(words.linkNotHiddenLong)
        }
    }
}
