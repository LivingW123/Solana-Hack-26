package app.taptrade.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Real backend over HTTP (Phase 4 implements these routes). */
class HttpBackendApi(private val baseUrl: String) : BackendApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listings(): List<Listing> =
        json.decodeFromString(request("GET", "/listings"))

    override suspend fun listing(pubkey: String): Listing =
        json.decodeFromString(request("GET", "/listings/$pubkey"))

    override suspend fun reputation(wallet: String): Reputation =
        json.decodeFromString(request("GET", "/reputation/$wallet"))

    override suspend fun fundTx(listing: String, buyer: String): TxResponse =
        json.decodeFromString(
            request("POST", "/tx/fund", buildJsonObject {
                put("listing", listing)
                put("buyer", buyer)
            }.toString())
        )

    override suspend fun handoff(escrow: String, seller: String): HandoffTicket =
        json.decodeFromString(
            request("POST", "/tx/release/nonce", buildJsonObject {
                put("escrow", escrow)
                put("seller", seller)
            }.toString())
        )

    private suspend fun request(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 5_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IOException("$method $path → HTTP $code: $text")
                text
            } finally {
                conn.disconnect()
            }
        }
}
