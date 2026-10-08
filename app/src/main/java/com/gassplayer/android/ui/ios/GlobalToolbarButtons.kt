package com.gassplayer.android.ui.ios

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun GlassSearchButton(onClick: () -> Unit) = IosGlassIconButton(Icons.Default.Search, "Cerca", onClick, size = 42)

@Composable
fun GlassSettingsButton(onClick: () -> Unit) = IosGlassIconButton(Icons.Default.Settings, "Impostazioni", onClick, size = 42)
