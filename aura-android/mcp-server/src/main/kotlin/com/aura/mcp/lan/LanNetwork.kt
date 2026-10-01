package com.aura.mcp.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aura.mcp.bridge.PairingCrypto
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/**
 * The Android side of the LAN transport: which interfaces are mobile data, which addresses to
 * show the user, network-change events, and the mDNS advert the bridge discovers.
 *
 * - Contract: every method is safe to call from any thread and never throws; a failing system
 *   service degrades to "no information" (empty sets, no advert).
 * - Change together: [SERVICE_TYPE] and the TXT keys with `aura-mcp-connect/src/lan.js`
 *   (`SERVICE_TYPE`, `parseMdnsResponse`).
 */
internal class LanNetwork(context: Context, private val log: (String) -> Unit = { Log.i(TAG, it) }) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Names of interfaces that currently carry mobile data (for example `rmnet_data0`). */
    @Suppress("DEPRECATION") // allNetworks: the callback replacement needs state we'd only use here.
    fun cellularInterfaces(): Set<String> = runCatching {
        cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
            cm.getLinkProperties(n)?.interfaceName
        }.toSet()
    }.getOrDefault(emptySet())

    /**
     * True when [socket] arrived on a mobile-data interface. Such peers are refused even with a
     * private address: carriers put subscribers in private and `100.64/10` ranges too.
     */
    fun arrivedOverCellular(socket: Socket): Boolean {
        val iface = runCatching { NetworkInterface.getByInetAddress(socket.localAddress) }.getOrNull()
            ?: return false
        return iface.name in cellularInterfaces()
    }

    /** The phone's LAN IPv4 addresses (Wi-Fi, hotspot, USB tethering, VPN), for the MCP Center. */
    fun lanAddresses(): List<String> = runCatching {
        val cellular = cellularInterfaces()
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.name !in cellular }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter(LanPolicy::isLanIpv4)
            .distinct()
    }.getOrDefault(emptyList())

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val debounced = Runnable { changeListener?.invoke() }
    @Volatile private var changeListener: (() -> Unit)? = null

    /** Calls [onChange] (on the main thread, debounced by 1 s) when a network appears, goes, or is re-addressed. */
    @Synchronized fun watch(onChange: () -> Unit) {
        unwatch()
        changeListener = onChange
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun poke() {
                main.removeCallbacks(debounced)
                main.postDelayed(debounced, 1_000)
            }
            override fun onAvailable(network: Network) = poke()
            override fun onLost(network: Network) = poke()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = poke()
        }
        // No INTERNET capability required: a Wi-Fi with no internet is still a fine LAN.
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { log("Network watch unavailable: ${it.message}") }
    }

    @Synchronized fun unwatch() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        changeListener = null
        main.removeCallbacks(debounced)
    }

    private var registration: NsdManager.RegistrationListener? = null

    /**
     * Advertises `_aura-mcp._tcp` on [port] with TXT `id=<deviceId>` and `proto=<protocol>`, replacing any
     * earlier advert.
     *
     * - Fails: logs and carries on. The bridge also finds the phone by its saved address and by
     *   scanning the subnet, so a missing advert only slows discovery.
     */
    @Synchronized fun advertise(port: Int, deviceId: String) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "AURA-${deviceId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("proto", PairingCrypto.PROTOCOL_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) = log("mDNS advert up: ${s.serviceName}")
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) = log("mDNS advert failed ($code)")
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registration = listener }
            .onFailure { log("mDNS advert unavailable: ${it.message}") }
    }

    @Synchronized fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    companion object {
        private const val TAG = "LanNetwork"
        const val SERVICE_TYPE = "_aura-mcp._tcp"
    }
}
