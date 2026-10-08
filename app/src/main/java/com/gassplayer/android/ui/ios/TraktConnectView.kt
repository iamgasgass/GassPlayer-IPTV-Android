package com.gassplayer.android.ui.ios

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.TraktAccount
import com.gassplayer.android.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IosTraktConnectView(app: GassPlayerApplication, vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val account by app.prefs.traktFlow.collectAsStateWithLifecycle(TraktAccount())
    var device by remember { mutableStateOf<com.gassplayer.android.data.TraktDeviceCode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(device?.deviceCode) {
        val d = device ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + d.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(5000)
            val token = runCatching { app.trakt.pollDevice(settings.traktClientId, settings.traktClientSecret, d.deviceCode) }.getOrNull()
            if (token != null) { app.prefs.saveTrakt(token); device = null; message = "Trakt collegato"; break }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IosSectionHeader("Trakt.tv", "Collega l'account per rating e scrobbling")
        IosTextField(settings.traktClientId, { vm.updateSettings(settings.copy(traktClientId = it)) }, "Client ID")
        IosTextField(settings.traktClientSecret, { vm.updateSettings(settings.copy(traktClientSecret = it)) }, "Client Secret", isPassword = true)
        if (account.accessToken.isNotBlank()) {
            IosGlassCard { IosGlassRow(Icons.Default.CheckCircle, "Account collegato", "Il token Trakt è salvato su questo dispositivo.", IosGreen, showChevron = false) }
            IosGlassPrimaryButton("Disconnetti Trakt", Icons.Default.LinkOff, onClick = { scope.launch { app.prefs.saveTrakt(TraktAccount()) } })
        } else if (device != null) {
            IosGlassCard {
                IosSectionHeader("Autorizza dispositivo", "Apri Trakt e inserisci il codice")
                Spacer(Modifier.height(8.dp))
                Text(device!!.userCode, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(device!!.verificationUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IosGlassPrimaryButton("Apri Trakt", Icons.Default.OpenInBrowser, modifier = Modifier.weight(1f), onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (device!!.verificationUrl.startsWith("http")) device!!.verificationUrl else "https://${device!!.verificationUrl}"))) })
                    IosGlassPrimaryButton("Annulla", Icons.Default.Close, modifier = Modifier.weight(1f), onClick = { device = null })
                }
            }
        } else {
            IosGlassPrimaryButton("Connetti a Trakt.tv", Icons.Default.Link, enabled = settings.traktClientId.isNotBlank() && settings.traktClientSecret.isNotBlank()) {
                scope.launch { device = app.trakt.deviceCode(settings.traktClientId.trim(), settings.traktClientSecret.trim()); message = if (device == null) "Impossibile iniziare il collegamento." else "Autorizza il dispositivo su Trakt." }
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)) }
    }
}
