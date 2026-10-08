package com.gassplayer.android.ui.ios

import androidx.compose.runtime.Composable
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.ui.MainViewModel

@Composable
fun ContentView(vm: MainViewModel, app: GassPlayerApplication) = IosParityNavHost(vm, app)
