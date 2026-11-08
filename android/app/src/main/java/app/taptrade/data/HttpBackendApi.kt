package app.taptrade.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Real backend over HTTP (backend/src/server.ts). */
class HttpBackendApi(private val baseUrl: String) : BackendApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listings(): List<Listing> =
        json.decodeFromString(request("GET", "/listings"))

    override suspend fun listing(pubkey: String): Listing =
        json.decodeFromString(request("GET", "/listings/$pubkey"))

    override suspend fun reputation(wallet: String): Reputation =
        json.decodeFromString(request("GET", "/reputation/$wallet"))

    override suspend fun createListingTx(seller: String, title: String, price: Long): TxResponse =
        json.decodeFromString(
            request("POST", "/tx/create-listing", buildJsonObject {
                put("seller", seller)
                put("title", title)
                put("price", price)
            }.toString())
        )

    override suspend fun fundTx(listing: String, buyer: String): TxResponse =
        json.decodeFromString(
            request("POST", "/tx/fund?listing=$listing", buildJsonObject {
                put("account", buyer)
            }.toString())
        )

    override suspend fun transactionRequest(link: String, account: String): TxResponse =
        json.decodeFromString(
            requestUrl("POST", link, buildJsonObject { put("account", account) }.toString())
        )

    override suspend fun handoff(escrow: String, seller: String): HandoffTicket =
        json.decodeFromString(
            request("POST", "/tx/release/nonce", buildJsonObject {
                put("escrow", escrow)
                put("seller", seller)
            }.toString())
        )

    private suspend fun request(method: String, path: String, body: String? = null): String =
        requestUrl(method, baseUrl.trimEnd('/') + path, body)

    private suspend fun requestUrl(method: String, url: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val path = URL(url).path
            val conn = URL(url).openConnection() as HttpURLConnection
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
                if (code !in 200..299) {
                    // Backend errors are `{ "error": "…" }`; show that sentence, not the raw body.
                    val reason = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                    }.getOrNull()
                    throw IOException(reason ?: "$method $path → HTTP $code: $text")
                }
                text
            } finally {
                conn.disconnect()
            }
        }
}
