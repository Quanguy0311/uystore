package io.github.wingedpanther.openshelf

import android.content.Context
import android.os.Build
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Tls {
    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) return
        runCatching {
            val system = trustManager(null)
            val extra = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                ctx.resources.openRawResource(R.raw.isrg_root_x1).use {
                    setCertificateEntry("isrg_root_x1", CertificateFactory.getInstance("X.509").generateCertificate(it))
                }
            }
            val bundled = trustManager(extra)
            val both = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        bundled.checkServerTrusted(chain, authType)
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + bundled.acceptedIssuers
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(both), null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        }
    }

    private fun trustManager(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(store)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }
}
