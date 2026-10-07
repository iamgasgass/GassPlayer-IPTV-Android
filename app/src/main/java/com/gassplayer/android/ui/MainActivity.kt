package com.gassplayer.android.ui

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.ui.theme.GassPlayerTheme

class MainActivity : ComponentActivity() {
    val app get() = application as GassPlayerApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GassPlayerRoot(app) }
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
