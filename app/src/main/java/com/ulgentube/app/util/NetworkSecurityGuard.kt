package com.ulgentube.app.util

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale

/**
 * SSRF, DNS Rebinding ve yerel ağ yönlendirme saldırılarına karşı ağ güvenlik koruyucusu.
 * Loopback, link-local, RFC1918 özel ağları, CGNAT, IPv6 ULA ve yerel alan adlarını engeller.
 */
object NetworkSecurityGuard {

    private val BLOCKED_HOST_NAMES = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata"
    )

    private val BLOCKED_HOST_SUFFIXES = listOf(
        ".localhost",
        ".local",
        ".internal",
        ".home.arpa",
        ".lan",
        ".localdomain"
    )

    private val STRICT_IPV4_OCTET_REGEX = Regex("""^(0|[1-9]\d{0,2})$""")
    private val NUMERIC_OR_HEX_LABEL_REGEX = Regex("""^(0x[0-9a-f]+|0[0-7]+|\d+)$""", RegexOption.IGNORE_CASE)

    /**
     * Verilen host adının güvenli, herkese açık bir hedef olup olmadığını kontrol eder.
     * Kısaltılmış (`127.1`), octal (`0177.0.0.1`), hex (`0x7f.0.0.1`) ve sayısal IP hilelerini engeller.
     */
    fun isSafePublicHost(rawHost: String?): Boolean {
        if (rawHost.isNullOrBlank()) return false
        val host = rawHost.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
        if (host.isBlank()) return false

        if (host in BLOCKED_HOST_NAMES) return false
        if (BLOCKED_HOST_SUFFIXES.any { host.endsWith(it) }) return false

        // IPv6 literal kontrolü
        if (host.contains(':')) {
            val parsed = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return isPublicAddress(parsed)
        }

        // Tek parça sayısal/hex IP veya noktasız yerel adları reddet
        if (!host.contains('.')) return false

        val labels = host.split('.')
        if (labels.any { it.isBlank() }) return false

        // Tüm parçalar sayısal/octal/hex ise: yalnızca standart 4 oktetli ondalık IPv4'e izin ver ve doğrula
        if (labels.all { NUMERIC_OR_HEX_LABEL_REGEX.matches(it) }) {
            if (labels.size != 4) return false
            val validDecimalOctets = labels.all { label ->
                STRICT_IPV4_OCTET_REGEX.matches(label) && (label.toIntOrNull() in 0..255)
            }
            if (!validDecimalOctets) return false
            val parsed = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return isPublicAddress(parsed)
        }

        // Geçerli bir DNS TLD'si tamamen sayısal/hex olamaz (örn. 127.0.0.1 türevleri)
        if (NUMERIC_OR_HEX_LABEL_REGEX.matches(labels.last())) return false

        return true
    }

    /**
     * Verilen URL'nin geçerli `http`/`https` şemasında ve herkese açık bir host'a ait olduğunu doğrular.
     */
    fun isSafePublicUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            (scheme == "http" || scheme == "https") && isSafePublicHost(uri.host)
        }.getOrDefault(false)
    }

    /**
     * Çözümlenmiş bir [InetAddress] kaydının yalnızca genel internete ait (public) olup olmadığını doğrular.
     */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF

        // 0.0.0.0/8 (This network)
        if (b0 == 0) return false
        // 10.0.0.0/8 (Private)
        if (b0 == 10) return false
        // 100.64.0.0/10 (CGNAT / Shared Address Space)
        if (b0 == 100 && b1 in 64..127) return false
        // 127.0.0.0/8 (Loopback)
        if (b0 == 127) return false
        // 169.254.0.0/16 (Link-local / Cloud Metadata)
        if (b0 == 169 && b1 == 254) return false
        // 172.16.0.0/12 (Private)
        if (b0 == 172 && b1 in 16..31) return false
        // 192.0.0.0/24, 192.0.2.0/24 (IETF / TEST-NET-1)
        if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) return false
        // 192.168.0.0/16 (Private)
        if (b0 == 192 && b1 == 168) return false
        // 198.18.0.0/15 (Benchmarking)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return false
        // 198.51.100.0/24 (TEST-NET-2)
        if (b0 == 198 && b1 == 51 && b2 == 100) return false
        // 203.0.113.0/24 (TEST-NET-3)
        if (b0 == 203 && b1 == 0 && b2 == 113) return false
        // 224.0.0.0/4+ (Multicast & Reserved / Broadcast)
        if (b0 >= 224) return false

        return true
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // fc00::/7 (Unique Local Address)
        if ((b0 and 0xFE) == 0xFC) return false
        // fe80::/10 (Link-local)
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return false
        // ff00::/8 (Multicast)
        if (b0 == 0xFF) return false

        // ::ffff:0:0/96 (IPv4-mapped IPv6) -> gömülü IPv4'ü kontrol et
        val isIpv4Mapped = (0..9).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        if (isIpv4Mapped) {
            return isPublicIpv4(bytes.copyOfRange(12, 16))
        }

        // :: (Unspecified) veya ::1 (Loopback)
        val allLeadingZero = (0..14).all { bytes[it].toInt() == 0 }
        if (allLeadingZero && (bytes[15].toInt() == 0 || bytes[15].toInt() == 1)) {
            return false
        }

        return true
    }

    /**
     * OkHttp için özel DNS çözümleyici: HTTP yönlendirmeleri ve DNS rebinding dahil
     * tüm bağlantıların yalnızca genel (public) IP adreslerine çıkmasını zorunlu kılar.
     */
    object SafePublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!isSafePublicHost(hostname)) {
                throw UnknownHostException("Yerel veya özel ağ adresine erişim engellendi.")
            }
            val resolved = Dns.SYSTEM.lookup(hostname)
            val safeAddresses = resolved.filter { isPublicAddress(it) }
            if (safeAddresses.isEmpty()) {
                throw UnknownHostException("Hedef adres güvenli genel ağ dışında.")
            }
            return safeAddresses
        }
    }
}
