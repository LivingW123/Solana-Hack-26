package app.taptrade.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Account websocket events. */
sealed interface AccountEvent {
    /** Subscription is live; check current state once to cover anything that happened before it. */
    data object Subscribed : AccountEvent

    /** The account changed; `exists = false` once it is closed (lamports → 0). */
    data class Changed(val exists: Boolean) : AccountEvent
}

/**
 * Minimal Solana JSON-RPC client. The app talks to the chain directly for anything
 * that decides what the user sees about money (confirmations, "Paid ✓"), so the
 * backend is never the source of truth.
 */
class SolanaRpc(private val httpUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keeps the websocket alive between notifications
        .build()
    private val wsUrl = httpUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    private val jsonType = "application/json".toMediaType()

    private suspend fun call(method: String, params: JsonArray): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString()
        val req = Request.Builder().url(httpUrl).post(body.toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("RPC $method → HTTP ${res.code}: $text")
            val obj = json.parseToJsonElement(text).jsonObject
            obj["error"]?.let { throw IOException("RPC $method error: $it") }
            obj["result"] ?: JsonNull
        }
    }

    /** True while the account exists (has lamports) at `confirmed`. */
    suspend fun accountExists(pubkey: String): Boolean {
        val result = call("getAccountInfo", buildJsonArray {
            add(pubkey)
            addJsonObject {
                put("encoding", "base64")
                put("commitment", "confirmed")
            }
        }).jsonObject
        return result["value"].let { it != null && it !is JsonNull }
    }

    /** Poll until [signature] is confirmed; throws if the transaction failed or times out. */
    suspend fun awaitConfirmed(signature: String, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = runCatching {
                call("getSignatureStatuses", buildJsonArray {
                    addJsonArray { add(signature) }
                }).jsonObject["value"]!!.jsonArray.firstOrNull()
            }.getOrNull()
            if (status != null && status !is JsonNull) {
                val s = status.jsonObject
                s["err"]?.let { if (it !is JsonNull) throw IllegalStateException("Transaction failed on-chain: $it") }
                val level = s["confirmationStatus"]?.jsonPrimitive?.content
                if (level == "confirmed" || level == "finalized") return
            }
            delay(400)
        }
        throw IllegalStateException("Not confirmed after ${timeoutMs / 1000}s: $signature")
    }

    /** `accountSubscribe` over websocket at `confirmed`. Completes with an error if the socket drops. */
    fun watchAccount(pubkey: String): Flow<AccountEvent> = callbackFlow {
        val ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "accountSubscribe")
                    put("params", buildJsonArray {
                        add(pubkey)
                        addJsonObject {
                            put("encoding", "base64")
                            put("commitment", "confirmed")
                        }
                    })
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when {
                    msg["id"]?.jsonPrimitive?.intOrNull == 1 && msg["result"] != null ->
                        trySend(AccountEvent.Subscribed)
                    msg["method"]?.jsonPrimitive?.content == "accountNotification" -> {
                        val value = msg["params"]?.jsonObject?.get("result")?.jsonObject?.get("value")
                        val lamports = (value as? JsonObject)?.get("lamports")?.jsonPrimitive?.long ?: 0L
                        trySend(AccountEvent.Changed(exists = lamports > 0))
                    }
                    msg["error"] != null -> close(IOException("accountSubscribe error: ${msg["error"]}"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("websocket closed: $code $reason"))
            }
        })
        awaitClose { ws.close(1000, null) }
    }
}
