package com.airachabanda.app.provider.local.ondevice

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens the catalog HTTPS URL and follows HTTPS redirects. It does not accept another scheme.
 */
object HttpsModelSource {
    fun open(url: String, offset: Long): InputStream {
        var current = url
        for (attempt in 1..5) {
            if (!current.startsWith("https://")) {
                error("Only HTTPS is accepted.")
            }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("The redirect had no location.")
                connection.disconnect()
                current = if (location.startsWith("https://")) location else URL(URL(current), location).toString()
                continue
            }
            if (offset > 0L && status != 206) error("The server did not resume the download.")
            if (offset == 0L && status != 200) error("The download was refused with status $status.")
            return connection.inputStream
        }
        error("The download redirected too many times.")
    }
}
