package com.android.purebilibili.feature.audio.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsDisplayPolicyTest {

    private fun line(startMs: Long, endMs: Long, text: String = "歌词") = LyricLine(
        startTimeMs = startMs,
        endTimeMs = endMs,
        text = text,
    )

    @Test
    fun `last started index survives inter-line gaps`() {
        val lines = listOf(
            line(0L, 1_000L, "a"),
            line(2_000L, 3_000L, "b"),
            line(4_000L, 5_000L, "c"),
        )
        assertEquals(0, resolveLastStartedLyricIndex(lines, 1_500L))
        assertEquals(1, resolveLastStartedLyricIndex(lines, 2_500L))
        assertEquals(2, resolveLastStartedLyricIndex(lines, 4_500L))
    }

    @Test
    fun `stable index holds previous row during short backward sample noise`() {
        val lines = listOf(
            line(0L, 1_000L),
            line(1_000L, 2_000L),
            line(2_000L, 3_000L),
        )
        // Position just before line 2 start but previous is 2 → keep 2 within hysteresis.
        assertEquals(2, resolveStableLyricIndex(lines, 1_900L, previousIndex = 2))
        // Clearly seeked before line 2 → accept line 1.
        assertEquals(1, resolveStableLyricIndex(lines, 1_200L, previousIndex = 2))
    }

    @Test
    fun `pseudo secondary filters music-note wrapped rows`() {
        assertTrue(
            isPseudoSecondaryLine(
                primaryText = "心有灵犀的你也会感应到吗",
                secondaryText = "♪ 留下心有灵犀的你也会感应听到吗 ♪",
            )
        )
        assertFalse(
            isPseudoSecondaryLine(
                primaryText = "心有灵犀的你也会感应到吗",
                secondaryText = "Can you feel it too",
            )
        )
    }

    @Test
    fun `romanization only shows for phonetic scripts`() {
        assertFalse(needsPhoneticAnnotation("留下心有灵犀的你"))
        assertTrue(needsPhoneticAnnotation("こころ"))
        val (_, romanization) = resolveDisplaySecondaryRows(
            primaryText = "心有灵犀的你也会感应到吗",
            translation = "Can you feel it",
            romanization = "留下心有灵犀的你也会感应听到吗",
            showTranslation = true,
        )
        assertEquals(null, romanization)
    }
}
