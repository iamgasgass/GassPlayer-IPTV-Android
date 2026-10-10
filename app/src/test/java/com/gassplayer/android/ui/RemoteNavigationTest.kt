package com.gassplayer.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import android.content.res.Configuration
import org.junit.Test

class RemoteNavigationTest {
    @Test
    fun backReturnsFromCustomizeToTheLibraryBeingEdited() {
        assertEquals("live", destinationAfterRemoteBack("live-customize", "settings"))
        assertEquals("movies", destinationAfterRemoteBack("movies-customize", "settings"))
        assertEquals("series", destinationAfterRemoteBack("series-customize", "settings"))
        assertEquals("home", destinationAfterRemoteBack("home-customize", "settings"))
    }

    @Test
    fun nestedSettingsRoutesReturnToSettings() {
        assertEquals("settings", destinationAfterRemoteBack("vpn", "settings"))
        assertEquals("settings", destinationAfterRemoteBack("parental", "settings"))
        assertEquals("settings", destinationAfterRemoteBack("diagnostics", "settings"))
        assertEquals("settings", destinationAfterRemoteBack("backup", "settings"))
    }

    @Test
    fun recognizesAmazonFireTvStickAndSmartTvFingerprintsWithoutMatchingOtherBrands() {
        assertEquals(true, isAmazonFireTvBuild("Amazon", "AFTSSS", "AFTSSS", "AFTSSS"))
        assertEquals(true, isAmazonFireTvBuild("amazon", "Ember TV", "AFTHA78451", "FireTV"))
        assertEquals(false, isAmazonFireTvBuild("Google", "AFTSSS", "AFTSSS", "AFTSSS"))
        assertEquals(false, isAmazonFireTvBuild("Amazon", "KFTRWI", "KFTRWI", "KFTRWI"))
    }

    @Test
    fun televisionDetectionMatchesGassTvFeatureChecksAndFireTvFallback() {
        val normal = Configuration.UI_MODE_TYPE_NORMAL
        val television = Configuration.UI_MODE_TYPE_TELEVISION
        assertEquals(true, isTelevisionEnvironment(television, normal, false, false, false))
        assertEquals(true, isTelevisionEnvironment(normal, television, false, false, false))
        assertEquals(true, isTelevisionEnvironment(normal, normal, true, false, false))
        assertEquals(true, isTelevisionEnvironment(normal, normal, false, true, false))
        assertEquals(true, isTelevisionEnvironment(normal, normal, false, false, true))
        assertEquals(false, isTelevisionEnvironment(normal, normal, false, false, false))
    }

    @Test
    fun epgManagerRestoresItsOriginAndRootDoesNotConsumeBack() {
        assertEquals("source-manager", destinationAfterRemoteBack("epg-manage", "source-manager"))
        assertEquals("settings", destinationAfterRemoteBack("epg-manage", "settings"))
        assertNull(destinationAfterRemoteBack("home", "settings"))
    }
}
