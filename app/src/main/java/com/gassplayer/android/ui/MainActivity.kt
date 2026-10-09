package com.gassplayer.android.ui

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.ui.theme.GassPlayerTheme

class MainActivity : ComponentActivity() {
    val app get() = application as GassPlayerApplication
    private var playerModeActive = false
    private var originalRequestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var playerForcedLandscape = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GassPlayerRoot(app) }
    }
    /** Enter immersive playback without recreating MainActivity/losing the current navigation route. */
    fun enterPlayerMode() {
        if (playerModeActive) return
        playerModeActive = true
        originalRequestedOrientation = requestedOrientation
        val television = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        playerForcedLandscape = !television && resources.configuration.smallestScreenWidthDp < 600
        if (playerForcedLandscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Restore orientation and system bars when leaving playback. */
    fun exitPlayerMode() {
        if (!playerModeActive) return
        playerModeActive = false
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        if (playerForcedLandscape) requestedOrientation = originalRequestedOrientation
        playerForcedLandscape = false
    }

    fun enterPlayerPip() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        }
    }
}

@Composable
fun GassPlayerRoot(app: GassPlayerApplication) {
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(app))
    val settings by vm.settings.collectAsStateWithLifecycle()
    GassPlayerTheme(theme = settings.theme) { GassPlayerNavHost(vm, app) }
}
