package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.data.SourceType
import com.gassplayer.android.data.XtreamCredentials
import com.gassplayer.android.ui.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun IosLoginDialog(app: GassPlayerApplication, vm: MainViewModel, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(SourceType.XTREAM) }
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    IosDialogFrame("Aggiungi la tua lista IPTV", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { IosChip("Xtream Codes", mode == SourceType.XTREAM) { mode = SourceType.XTREAM }; IosChip("M3U / M3U8", mode == SourceType.M3U8) { mode = SourceType.M3U8 } }
        if (mode == SourceType.XTREAM) {
            IosTextField(host, { host = it }, "Host")
            IosTextField(username, { username = it }, "Username")
            IosTextField(password, { password = it }, "Password", isPassword = true)
        } else IosTextField(m3u, { m3u = it }, "URL playlist M3U/M3U8")
        error?.let { Text(it, color = IosRed, fontSize = 12.sp) }
        IosGlassPrimaryButton(if (loading) "Verifica in corso…" else "Accedi", Icons.Default.Login, enabled = !loading) {
            scope.launch {
                loading = true; error = null
                if (mode == SourceType.XTREAM) {
                    if (host.isBlank() || username.isBlank() || password.isBlank()) { error = "Compila tutti i campi."; loading = false; return@launch }
                    val credentials = XtreamCredentials(host.trim(), username.trim(), password)
                    runCatching { app.xtream.authenticate(credentials) }.onSuccess {
                        val source = MediaSourceConfig(UUID.randomUUID().toString(), "Xtream IPTV", SourceType.XTREAM, credentials.host, credentials.username, credentials.password)
                        app.sources.addOrUpdate(source); app.sources.setActive(source.id); onDismiss()
                    }.onFailure { error = it.message ?: "Autenticazione non riuscita." }
                } else {
                    val url = runCatching { java.net.URI(m3u.trim()) }.getOrNull()
                    if (url?.scheme !in setOf("http", "https")) error = "URL playlist non valido." else {
                        val source = MediaSourceConfig(UUID.randomUUID().toString(), "Playlist M3U", SourceType.M3U8, m3u.trim(), playlistUrl = m3u.trim())
                        app.sources.addOrUpdate(source); app.sources.setActive(source.id); onDismiss()
                    }
                }
                loading = false
            }
        }
    }
}
