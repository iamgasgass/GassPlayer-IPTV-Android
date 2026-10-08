package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.VPNConfig
import kotlinx.coroutines.launch

@Composable
fun IosPersonalVpnView(app: GassPlayerApplication) {
    val config by app.prefs.vpnFlow.collectAsState(initial = VPNConfig())
    var local by remember(config) { mutableStateOf(config) }
    var status by remember { mutableStateOf("Disconnessa") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        IosSectionHeader("VPN personale", "IKEv2, WireGuard e profili VPN Android")
        IosChoiceCardVpn("Protocollo", local.protocol, listOf("ikev2" to "IKEv2", "wireguard" to "WireGuard", "openvpn" to "OpenVPN")) { local = local.copy(protocol = it) }
        IosTextField(local.serverEndpoint, { local = local.copy(serverEndpoint = it) }, "Server endpoint")
        if (local.protocol == "ikev2") {
            IosTextField(local.username, { local = local.copy(username = it) }, "Username")
            IosTextField(local.password, { local = local.copy(password = it) }, "Password", isPassword = true)
        }
        if (local.protocol == "wireguard") {
            IosTextField(local.serverPublicKey.orEmpty(), { local = local.copy(serverPublicKey = it) }, "Server public key")
            IosTextField(local.clientPrivateKey.orEmpty(), { local = local.copy(clientPrivateKey = it) }, "Client private key", isPassword = true)
            IosTextField(local.clientAddress.orEmpty(), { local = local.copy(clientAddress = it) }, "Client address")
        }
        IosGlassPrimaryButton(if (status == "Connessa") "Disconnetti" else "Connetti", if (status == "Connessa") Icons.Default.VpnLock else Icons.Default.VpnKey, enabled = local.serverEndpoint.isNotBlank()) {
            scope.launch {
                app.prefs.saveVpn(local)
                status = if (status == "Connessa") "Disconnessa" else "Connessa"
            }
        }
        Text("Stato: $status", color = if (status == "Connessa") IosGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
        IosGlassCard { IosGlassRow(Icons.Default.Info, "Protezione credenziali", "Le configurazioni vengono memorizzate in DataStore con protezione locale.", showChevron = false) }
    }
}

@Composable
private fun IosChoiceCardVpn(title: String, current: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IosGlassCard(padding = PaddingValues(12.dp)) {
        IosGlassRow(Icons.Default.VpnKey, title, options.firstOrNull { it.first == current }?.second ?: current, IosBlue, showChevron = true, onClick = { expanded = !expanded })
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { options.forEach { (id, label) -> IosGlassRow(Icons.Default.Check, label, showChevron = false, onClick = { onSelected(id); expanded = false }) } }
    }
}
