package com.namanbansal.forge.video

import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The progress-notification state machine: what each job state looks like, and when the notification is refreshed. */
@RunWith(RobolectricTestRunner::class)
class JobStateTest {
    private val done = JobState.Done(ToolOutcome(SavedOutput(android.net.Uri.parse("content://x/1"), "a.mp4", 1, "video/mp4"), "Video trimmed"))

    @Test fun runningShowsPercentAndCancel() {
        val s = JobNotifications.spec("Compressing video", JobState.Running(0.426f))
        assertEquals(42, s.percent); assertEquals("42%", s.text); assertTrue(s.ongoing); assertTrue(s.showCancel)
    }

    @Test fun unknownProgressIsIndeterminate() {
        val s = JobNotifications.spec("Trimming video", JobState.Running(null))
        assertNull(s.percent); assertEquals("Working…", s.text); assertTrue(s.ongoing)
    }

    @Test fun progressIsClampedTo0To100() {
        assertEquals(100, JobNotifications.spec("x", JobState.Running(1.7f)).percent)
        assertEquals(0, JobNotifications.spec("x", JobState.Running(-1f)).percent)
    }

    @Test fun finishedStatesAreNotOngoingAndHaveNoCancel() {
        val d = JobNotifications.spec("Making GIF", done)
        assertFalse(d.ongoing); assertFalse(d.showCancel); assertEquals("Making GIF finished", d.title); assertEquals("Video trimmed", d.text)
        val f = JobNotifications.spec("Making GIF", JobState.Failed("No encoder"))
        assertEquals("Making GIF failed", f.title); assertEquals("No encoder", f.text); assertFalse(f.ongoing)
        val c = JobNotifications.spec("Making GIF", JobState.Cancelled)
        assertEquals("Making GIF cancelled", c.title); assertFalse(c.ongoing)
    }

    @Test fun terminalFlag() {
        assertFalse(JobState.Running(0f).terminal); assertTrue(done.terminal); assertTrue(JobState.Cancelled.terminal); assertTrue(JobState.Failed("x").terminal)
    }

    @Test fun updatesAreThrottledToWholePercentChanges() {
        val a = JobNotifications.spec("J", JobState.Running(0.101f))
        assertTrue(JobNotifications.shouldUpdate(null, a))
        assertFalse(JobNotifications.shouldUpdate(a, JobNotifications.spec("J", JobState.Running(0.104f)))) // same whole percent
        assertTrue(JobNotifications.shouldUpdate(a, JobNotifications.spec("J", JobState.Running(0.11f))))
        assertTrue(JobNotifications.shouldUpdate(a, JobNotifications.spec("J", JobState.Cancelled)))
    }
}
