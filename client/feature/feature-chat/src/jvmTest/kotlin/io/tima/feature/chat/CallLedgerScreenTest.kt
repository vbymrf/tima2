package io.tima.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.tima.core.ui.TimaColors
import io.tima.domain.chat.Section
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Журнал звонка и настройка группового звонка (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ5, ГЗ6): полоса
 * действий видна, у создателя — кнопки у всех, у участника — только у себя.
 */
class CallLedgerScreenTest {

    private val me = CallMember("me", "Евгений", "Е", CallMemberState.Self, microphoneOn = true, cameraOn = true, creator = true)
    private val anna = CallMember("u1", "Анна Петрова", "АП", CallMemberState.In, microphoneOn = true, cameraOn = true)
    private val boris = CallMember("u2", "Борис", "Б", CallMemberState.In, microphoneOn = false, cameraOn = false, micForbidden = true)
    private val vera = CallMember("u3", "Вера", "В", CallMemberState.Invited)
    private val people = listOf(
        CallCandidate("u4", "Галина", "Г", sectionId = "s1"),
        CallCandidate("u5", "Дмитрий", "Д"),
        CallCandidate("u6", "Елена", "Е", sectionId = "s1"),
    )
    private val sections = listOf(Section(id = "s1", name = "Работа"))

    @Test
    fun настройка_звонка_две_галочки_и_две_кнопки() {
        val shot = capture("групповой-настройка", 400, 700, dark = false) {
            GroupCallSetup(ring = false, video = true, fromGroup = false, onRing = {}, onVideo = {}, onPick = {}, onCreateChat = {})
        }
        assertTrue(shot.has(TimaColors.light.navigation, tolerance = 0.06), "отмеченное «Включить видео» не выделено")
    }

    @Test
    fun журнал_до_звонка_участники_и_главная_кнопка() {
        capture("групповой-журнал-сбор", 400, 760, dark = false) {
            Column(Modifier.fillMaxSize()) {
                CallLedgerPage(
                    members = listOf(me, anna.copy(state = CallMemberState.Added), boris.copy(state = CallMemberState.Added)),
                    candidates = people, sections = sections, live = false, mine = true, fromGroup = false, max = 25,
                    modifier = Modifier.weight(1f),
                    setup = CallLedgerSetup(ring = false, video = true, primary = "Позвонить", onRing = {}, onVideo = {}, onPrimary = {}),
                )
            }
        }
    }

    @Test
    fun журнал_идущего_звонка_у_создателя_кнопки_у_всех() {
        val creator = capture("групповой-журнал-создатель", 400, 760, dark = false) {
            Column(Modifier.fillMaxSize()) {
                CallLedgerPage(
                    members = listOf(me, anna, boris, vera), candidates = people, sections = sections,
                    live = true, mine = true, fromGroup = true, max = 25, modifier = Modifier.weight(1f),
                )
            }
        }
        val member = capture("групповой-журнал-участник", 400, 760, dark = false) {
            Column(Modifier.fillMaxSize()) {
                CallLedgerPage(
                    members = listOf(me.copy(creator = false), anna.copy(creator = true), boris, vera),
                    candidates = people, sections = sections,
                    live = true, mine = false, fromGroup = true, max = 25, modifier = Modifier.weight(1f),
                )
            }
        }
        assertTrue(creator.difference(member) > 0.01, "у создателя и участника журнал одинаков — кнопок по ролям нет")
    }

    @Test
    fun подсказка_журнала_звонка() {
        capture("групповой-журнал-подсказка", 400, 900, dark = false) { CallLedgerHelpPage() }
    }

    @Test
    fun полоса_в_группе_и_приглашение() {
        capture("групповой-полоса-приглашение", 400, 300, dark = false) {
            Column {
                GroupCallBanner(text = "Идёт звонок · в звонке 3 · 14 мин", joinLabel = "Присоединиться", onJoin = {})
                CallInviteCard(CallInvite.Live("Планёрка"), onJoin = {})
                CallInviteCard(CallInvite.Ended("Планёрка"), onJoin = {})
            }
        }
    }
}
