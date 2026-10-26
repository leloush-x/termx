package app.termx.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.io.File

/** Exposes Android's current DNS servers to Linux programs running under PRoot. */
object AndroidDnsResolver {
    fun writeConfig(context: Context, directory: File): File? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network: Network = connectivity.activeNetwork ?: return null
        val servers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress || it.isLinkLocalAddress }
        if (servers.isEmpty()) return null
        directory.mkdirs()
        File(directory, "resolv.conf").apply {
            writeText(servers.joinToString(separator = "\n", postfix = "\n") { "nameserver ${it.hostAddress}" })
        }
    }.getOrNull()
}
