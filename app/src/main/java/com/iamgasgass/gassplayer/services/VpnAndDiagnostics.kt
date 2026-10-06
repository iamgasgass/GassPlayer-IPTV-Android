package com.iamgasgass.gassplayer.services
import android.content.Context
import android.content.Intent
import android.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.*
data class NetworkReport(val connected:Boolean,val transports:List<String>,val validated:Boolean,val captivePortal:Boolean,val dns:List<String>,val latencyMs:Long?)
class NetworkDiagnostics(private val context:Context){suspend fun inspect(host:String="1.1.1.1"):NetworkReport=withContext(Dispatchers.IO){val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;val n=cm.activeNetwork;val c=n?.let(cm::getNetworkCapabilities);val t=buildList{if(c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)add("Wi-Fi");if(c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true)add("Ethernet");if(c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true)add("Cellulare");if(c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true)add("VPN")};val start=System.nanoTime();val latency=runCatching{Socket().use{it.connect(InetSocketAddress(host,443),3000)};(System.nanoTime()-start)/1_000_000}.getOrNull();NetworkReport(c!=null,t,c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true,c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true,n?.let{cm.getLinkProperties(it)?.dnsServers?.map(InetAddress::getHostAddress)}?:emptyList(),latency)}}
class VpnLauncher(private val context:Context){fun prepare():Intent?=VpnService.prepare(context)}
