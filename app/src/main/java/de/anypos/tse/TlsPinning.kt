package de.anypos.tse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS zum Drucker (ePOS Device XML, Port 8143) mit festgelegtem Zertifikat.
 *
 * Der Drucker hat ein selbstsigniertes Zertifikat; eine Prüfung über Zertifizierungsstellen und Hostnamen ist
 * daher nicht möglich. Stattdessen bestätigt der Admin den SHA-256-Fingerabdruck einmal ([fetchCertificate]),
 * danach akzeptiert die Verbindung nur genau dieses Zertifikat ([pinnedSocketFactory]).
 */
object TlsPinning {

    /** SHA-256 über das DER-codierte Zertifikat, als `AB:CD:…`. */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** Nur Verbindungen zum Zertifikat mit [expectedFingerprint]; leer = noch nicht bestätigt, alles wird abgelehnt. */
    fun pinnedSocketFactory(expectedFingerprint: String): SocketFactory {
        val expected = normalize(expectedFingerprint)
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("nicht vorgesehen")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (expected.isEmpty()) throw CertificateException("Druckerzertifikat noch nicht bestätigt (Einstellungen → TSE)")
                val actual = chain.firstOrNull()?.let(::fingerprint) ?: throw CertificateException("Drucker sendet kein Zertifikat")
                if (normalize(actual) != expected) throw CertificateException("Druckerzertifikat hat sich geändert – nicht vertrauenswürdig")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
    }

    /**
     * Holt das Zertifikat des Druckers zur Bestätigung durch den Admin. Es wird nur der TLS-Handshake ausgeführt;
     * es werden keine Daten gesendet.
     */
    suspend fun fetchCertificate(host: String, port: Int, timeoutMs: Int = 5_000): X509Certificate = withContext(Dispatchers.IO) {
        var seen: X509Certificate? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("nicht vorgesehen")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) { seen = chain.firstOrNull() }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.startHandshake()
        }
        seen ?: throw TseTransportException("Drucker hat kein Zertifikat gesendet")
    }

    private fun normalize(fp: String) = fp.uppercase().filter { it.isLetterOrDigit() }
}
