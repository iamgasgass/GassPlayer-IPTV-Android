package com.gassplayer.android.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.os.Build
import com.gassplayer.android.data.NetworkApi
import com.gassplayer.android.data.VPNConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy
import java.util.Base64

class VpnController(private val context: Context, private val api: NetworkApi) {
    private val manager: VpnManager? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VpnManager::class.java) else null

    fun provisionIkev2(config: VPNConfig): Intent? {
        check(Build.VERSION.SDK_INT >= 30) { "IKEv2 provisioning richiede Android 11+" }
        check(config.protocol.equals("ikev2", true))
        check(config.isValid)
        val profile = Ikev2VpnProfile.Builder(config.serverEndpoint, config.username)
            .setAuthUsernamePassword(config.username, config.password, null)
            .setBypassable(false)
            .setMetered(false)
            .setAutomaticIpVersionSelectionEnabled(true)
            .build()
        return manager?.provisionVpnProfile(profile)
    }

    fun startIkev2() {
        if (Build.VERSION.SDK_INT < 30) error("IKEv2 non disponibile")
        val m = manager ?: error("VpnManager non disponibile")
        if (Build.VERSION.SDK_INT >= 33) m.startProvisionedVpnProfileSession() else m.startProvisionedVpnProfile()
    }

    fun stopIkev2() { if (Build.VERSION.SDK_INT >= 30) manager?.stopProvisionedVpnProfile() }

    suspend fun providerDiscovery(host: String, username: String, password: String): VPNConfig? = withContext(Dispatchers.IO) {
        val base = host.trimEnd('/')
        val candidates = listOf("$base/api/vpn", "$base/api/vpn/config", "$base/vpn/config", "$base/api/get_vpn")
        for (url in candidates) {
            runCatching {
                val o = api.getJson(url).jsonObject
                val protocol = o["protocol"]?.toString()?.trim('"').orEmpty()
                if (protocol.isNotBlank() && o["server"] != null) return@withContext VPNConfig(protocol, o["server"]!!.toString().trim('"'), username, password, o["server_public_key"]?.toString()?.trim('"'), o["preshared_key"]?.toString()?.trim('"'), o["client_private_key"]?.toString()?.trim('"'), o["client_address"]?.toString()?.trim('"'), o["dns"]?.toString()?.trim('[',']','"')?.split(',')?.filter { it.isNotBlank() } ?: listOf("1.1.1.1"), o["allowed_ips"]?.toString()?.trim('[',']','"')?.split(',')?.filter { it.isNotBlank() } ?: listOf("0.0.0.0/0", "::/0"), o["mtu"]?.toString()?.trim('"')?.toIntOrNull() ?: 1400)
            }
        }
        null
    }

    suspend fun setWireGuard(config: VPNConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(Build.VERSION.SDK_INT >= 21)
            require(config.isValid)
            val tunnelConfig = buildWireGuardConfig(config)
            WireGuardReflection(context).setUp(tunnelConfig)
        }
    }

    private fun buildWireGuardConfig(c: VPNConfig): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${c.clientPrivateKey}")
        appendLine("Address = ${c.clientAddress ?: "10.66.66.2/32"}")
        appendLine("DNS = ${c.dns.joinToString(", ")}")
        appendLine("MTU = ${c.mtu}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${c.serverPublicKey}")
        c.presharedKey?.takeIf { it.isNotBlank() }?.let { appendLine("PresharedKey = $it") }
        appendLine("AllowedIPs = ${c.allowedIps.joinToString(", ")}")
        appendLine("Endpoint = ${c.serverEndpoint}")
        appendLine("PersistentKeepalive = 25")
    }
}

/** Runtime adapter around the official WireGuard Android tunnel library.
 * Reflection keeps this app source resilient to small API surface changes in the upstream backend.
 */
private class WireGuardReflection(private val context: Context) {
    fun setUp(configText: String) {
        val configClass = Class.forName("com.wireguard.config.Config")
        val parse = configClass.methods.firstOrNull { it.name == "parse" && it.parameterTypes.size == 1 } ?: error("WireGuard Config.parse non trovato")
        val cfg = java.io.StringReader(configText).let { reader -> parse.invoke(null, reader) }
        val backendClass = Class.forName("com.wireguard.android.backend.GoBackend")
        val backend = backendClass.getConstructor(Context::class.java).newInstance(context)
        val tunnelClass = Class.forName("com.wireguard.android.backend.Tunnel")
        val callback = Proxy.newProxyInstance(tunnelClass.classLoader, arrayOf(tunnelClass)) { _, method, args ->
            when (method.name) { "getName" -> "GassPlayer"; "onStateChange" -> Unit }
            null
        }
        val stateClass = Class.forName("com.wireguard.android.backend.Tunnel\$State")
        val up = stateClass.enumConstants.firstOrNull { (it as? Enum<*>)?.name == "UP" } ?: error("WireGuard Tunnel state UP non trovato")
        val setState = backendClass.methods.firstOrNull { it.name == "setState" && it.parameterTypes.size == 3 } ?: error("WireGuard backend API non trovato")
        setState.invoke(backend, callback, up, cfg)
    }
}
