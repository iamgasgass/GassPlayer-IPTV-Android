package com.gassplayer.android.vpn

import android.content.Context
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.gassplayer.android.data.VPNConfig

/**
 * Isolated Android 11+ IKEv2 bridge. Keeping platform API 30 types in this class avoids making
 * older Fire OS runtimes resolve VpnManager/Ikev2VpnProfile while loading VpnController.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object Ikev2VpnAdapter {
    fun provision(context: Context, config: VPNConfig): Intent? {
        val manager = context.getSystemService(VpnManager::class.java) ?: return null
        val profile = Ikev2VpnProfile.Builder(config.serverEndpoint, config.username)
            .setAuthUsernamePassword(config.username, config.password, null)
            .setBypassable(false)
            .setMetered(false)
            .setAutomaticIpVersionSelectionEnabled(true)
            .build()
        return manager.provisionVpnProfile(profile)
    }

    fun start(context: Context) {
        val manager = context.getSystemService(VpnManager::class.java)
            ?: error("VpnManager non disponibile")
        if (Build.VERSION.SDK_INT >= 33) {
            manager.startProvisionedVpnProfileSession()
        } else {
            manager.startProvisionedVpnProfile()
        }
    }

    fun stop(context: Context) {
        context.getSystemService(VpnManager::class.java)?.stopProvisionedVpnProfile()
    }
}
