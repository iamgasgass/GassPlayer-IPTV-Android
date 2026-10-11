package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDefaultsTest {
    @Test
    fun darkThemeIsTheFactoryDefault() {
        assertEquals("dark", AppSettings().theme)
    }

    @Test
    fun resumePromptIsLimitedToOnDemandMediaAndNeverLiveTv() {
        assertEquals(false, MediaKind.LIVE.supportsResumePlayback())
        assertEquals(true, MediaKind.MOVIE.supportsResumePlayback())
        assertEquals(true, MediaKind.SERIES.supportsResumePlayback())
        assertEquals(true, MediaKind.EPISODE.supportsResumePlayback())
    }
}
