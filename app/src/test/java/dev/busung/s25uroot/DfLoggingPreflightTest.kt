package dev.busung.s25uroot

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DfLoggingPreflightTest {
    @Test
    fun `enabled levels including an unset property do not change logging`() {
        for (tag in listOf("", "I", "D", "V", "W", "E")) {
            var wrote = false
            val result = DfLoggingPreflight({ tag }, { wrote = true; true }).check()
            assertEquals(tag, DfLoggingStatus.Enabled, result)
            assertFalse(tag, wrote)
        }
    }

    @Test
    fun `Off blocks a standalone run without attempting an exploit`() {
        for (tag in listOf("Settings", "Settings,I", "S", "silent")) {
            assertEquals(tag, DfLoggingStatus.Disabled, DfLoggingPreflight({ tag }).check())
        }
    }

    @Test
    fun `repair removes the Off sentinel and reads back the effective value`() {
        var tag = "Settings,Settings,I"
        var reads = 0
        val result = DfLoggingPreflight(
            readTag = { reads++; tag },
            writeTag = { tag = it; true },
        ).check()
        assertEquals("I", tag)
        assertEquals(2, reads)
        assertEquals(DfLoggingStatus.Restored, result)
    }

    @Test
    fun `Off without a previous level restores the enabled default`() {
        var tag = "Settings"
        assertEquals(
            DfLoggingStatus.Restored,
            DfLoggingPreflight({ tag }, { tag = it; true }).check(),
        )
        assertEquals("", tag)
    }

    @Test
    fun `a silent previous level is not restored as a successful repair`() {
        var tag = "Settings,S"
        assertEquals(
            DfLoggingStatus.Restored,
            DfLoggingPreflight({ tag }, { tag = it; true }).check(),
        )
        assertEquals("", tag)
    }

    @Test
    fun `a refused or timed out write blocks the run`() {
        var reads = 0
        assertEquals(
            DfLoggingStatus.Disabled,
            DfLoggingPreflight({ reads++; "Settings" }, { false }).check(),
        )
        assertEquals(1, reads)
    }

    @Test
    fun `a zero exit without a changed property is not success`() {
        assertEquals(
            DfLoggingStatus.Disabled,
            DfLoggingPreflight({ "Settings" }, { true }).check(),
        )
    }

    @Test
    fun `an unreadable property is not confused with an enabled empty property`() {
        var wrote = false
        assertEquals(
            DfLoggingStatus.Unavailable,
            DfLoggingPreflight({ null }, { wrote = true; true }).check(),
        )
        assertFalse(wrote)
    }

    @Test
    fun `a repair that cannot be verified blocks the run`() {
        var reads = 0
        assertEquals(
            DfLoggingStatus.Unavailable,
            DfLoggingPreflight({ if (reads++ == 0) "Settings" else null }, { true }).check(),
        )
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never treated as an enabled logger`() {
        DfLoggingPreflight({ throw CancellationException("stopped") }).check()
    }
}

