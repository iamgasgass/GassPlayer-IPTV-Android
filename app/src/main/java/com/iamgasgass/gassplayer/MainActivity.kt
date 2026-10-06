package com.iamgasgass.gassplayer

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iamgasgass.gassplayer.ui.GassPlayerApp
import com.iamgasgass.gassplayer.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Content() }
    }

    @Composable
    private fun Content() {
        val vm: MainViewModel = viewModel(
            factory = MainViewModel.factory(
                (application as GassPlayerApplication).store,
            ),
        )
        GassPlayerApp(vm)
    }

    fun enterPip() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        inPip: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
    }
}
