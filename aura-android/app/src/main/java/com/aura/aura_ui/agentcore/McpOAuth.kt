package com.aura.aura_ui.agentcore

import com.aura.aura_ui.agent.mcpbridge.client.McpAuthConfig
import com.aura.aura_ui.agent.mcpbridge.client.McpOAuthCoordinator
import com.aura.aura_ui.agent.mcpbridge.client.McpOAuthFlow
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A signed-in MCP server's tokens and where to refresh them. Stored encrypted as JSON. */
@Serializable
data class OAuthTokens(
    val access: String,
    val refresh: String? = null,
    /** Epoch millis; 0 when the server gave no lifetime. */
    val expiresAt: Long = 0,
    val tokenEndpoint: String,
    val clientId: String,
    val resource: String,
)

/**
 * Signs AURA in to a remote MCP server with the MCP authorization spec: protected-resource
 * discovery, dynamic client registration, Authorization Code with PKCE (S256), refresh tokens.
 *
 * - Contract: [start] returns the sign-in page to open. The browser comes back to
 *   [McpOAuthFlow.REDIRECT_URI]; `MainActivity` hands the checked code to [onRedirect], which
 *   swaps it for tokens and calls `onSignedIn(id, url)`. [accessToken] refreshes a token that
 *   has under a minute left. [status] says how the last sign-in went, for the Connectors page.
 * - Fails: closed. A refused registration, exchange or refresh leaves the server signed out;
 *   [accessToken] returns null and the connector lists no tools until the user signs in again.
 * - Reads/Writes: `ProviderKeyStore` keys `oauth_<id>` ([OAuthTokens]) and `oauth_client_<host>`
 *   (the registered client id); network hosts named by the server's own metadata.
 * - Change together: `McpOAuthCoordinator` (PKCE state), `MainActivity.handleMcpOAuthRedirect`.
 */
class McpOAuth(
    private val keys: ProviderKeyStore,
    private val onSignedIn: suspend (id: String, url: String) -> Result<Int>,
) {
    val status = MutableStateFlow<String?>(null)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    /** What [onRedirect] needs that the coordinator does not keep. */
    private data class Pending(val id: String, val url: String, val tokenEndpoint: String, val clientId: String)

    @Volatile
    private var pending: Pending? = null

    /** The sign-in page for MCP server [url], to be saved as connector [id]. */
    suspend fun start(id: String, url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            status.value = "Opening sign-in"
            val meta = discover(url)
            val authorize = meta.string("authorization_endpoint")
            val token = meta.string("token_endpoint")
            val clientId = register(meta)
            val scopes = meta["scopes_supported"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it in WANTED_SCOPES }.orEmpty()
            val page = McpOAuthCoordinator.begin(id, McpAuthConfig.OAuth(authorize, token, clientId, scopes), url).getOrThrow()
            pending = Pending(id, url, token, clientId)
            Result.success("$page&resource=${enc(url)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.value = "Could not start sign-in: ${e.message}"
            Result.failure(e)
        }
    }

    /** Takes the redirect `MainActivity` checked; swaps the code for tokens off the main thread. */
    fun onRedirect(result: Result<McpOAuthCoordinator.AuthCode>) {
        val code = result.getOrElse {
            status.value = "Sign-in was not completed: ${it.message}"
            return
        }
        val waiting = pending?.takeIf { it.id == code.serverId } ?: run {
            status.value = "Sign-in expired. Try again."
            return
        }
        pending = null
        scope.launch {
            status.value = "Finishing sign-in"
            try {
                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code.code)
                    .add("redirect_uri", McpOAuthFlow.REDIRECT_URI)
                    .add("client_id", waiting.clientId)
                    .add("code_verifier", code.codeVerifier)
                    .add("resource", waiting.url)
                    .build()
                val tokens = parseTokens(post(waiting.tokenEndpoint, form), waiting.tokenEndpoint, waiting.clientId, waiting.url, null, System.currentTimeMillis())
                save(waiting.id, tokens)
                status.value = onSignedIn(waiting.id, waiting.url).fold({ null }, { "Signed in, but the server did not answer: ${it.message}" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Sign-in failed: ${e.message}"
            }
        }
    }

    /** A usable access token for [id], refreshed when it is about to expire; null when signed out. */
    suspend fun accessToken(id: String): String? = refreshLock.withLock {
        val tokens = load(id) ?: return@withLock null
        val now = System.currentTimeMillis()
        if (tokens.expiresAt == 0L || tokens.expiresAt - now > EARLY_MS) return@withLock tokens.access
        val refresh = tokens.refresh ?: return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", tokens.clientId)
                    .add("resource", tokens.resource)
                    .build()
                parseTokens(post(tokens.tokenEndpoint, form), tokens.tokenEndpoint, tokens.clientId, tokens.resource, refresh, now)
            }.onSuccess { save(id, it) }.getOrNull()?.access
        }
    }

    /** Adds [id] again from tokens already saved, when the sign-in worked but the server did not answer. */
    suspend fun reconnect(id: String, url: String) {
        status.value = "Connecting"
        status.value = onSignedIn(id, url).fold({ null }, { "Signed in, but the server did not answer: ${it.message}" })
    }

    fun isSignedIn(id: String): Boolean = load(id) != null

    fun signOut(id: String) = keys.clearKey("oauth_$id")

    private fun load(id: String): OAuthTokens? =
        keys.getKey("oauth_$id")?.let { runCatching { Json.decodeFromString(OAuthTokens.serializer(), it) }.getOrNull() }

    private fun save(id: String, tokens: OAuthTokens) {
        check(keys.setKey("oauth_$id", Json.encodeToString(OAuthTokens.serializer(), tokens))) { "This phone cannot store secrets safely." }
    }

    /** RFC 9728 then RFC 8414: the server's protected-resource document names its authorization server. */
    private fun discover(url: String): JsonObject {
        val resource = get(wellKnown(url, "oauth-protected-resource"))
        val server = resource["authorization_servers"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: error("The server does not say where to sign in.")
        require(server.startsWith("https://")) { "The sign-in server must use https." }
        return get(wellKnown(server, "oauth-authorization-server"))
    }

    /** RFC 7591, once per authorization server; the id is kept and reused. */
    private fun register(meta: JsonObject): String {
        val issuer = URI(meta.string("issuer")).host
        keys.getKey("oauth_client_$issuer")?.let { return it }
        val endpoint = meta.string("registration_endpoint")
        val body = buildJsonObject {
            put("client_name", "AURA")
            put("redirect_uris", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(McpOAuthFlow.REDIRECT_URI)) })
            put("grant_types", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("authorization_code")); add(kotlinx.serialization.json.JsonPrimitive("refresh_token")) })
            put("response_types", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("code")) })
            put("token_endpoint_auth_method", "none")
        }.toString().toRequestBody(JSON)
        val id = post(endpoint, body)["client_id"]?.jsonPrimitive?.contentOrNull ?: error("The sign-in server did not register AURA.")
        keys.setKey("oauth_client_$issuer", id)
        return id
    }

    private fun get(url: String): JsonObject = send(Request.Builder().url(url).header("Accept", "application/json").build())

    private fun post(url: String, body: okhttp3.RequestBody): JsonObject =
        send(Request.Builder().url(url).header("Accept", "application/json").post(body).build())

    private fun send(request: Request): JsonObject {
        require(request.url.isHttps) { "Sign-in addresses must use https." }
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val reason = json?.get("error_description")?.jsonPrimitive?.contentOrNull ?: json?.get("error")?.jsonPrimitive?.contentOrNull
                error(reason ?: "HTTP ${response.code}")
            }
            return json ?: error("The sign-in server sent something unreadable.")
        }
    }

    companion object {
        /** Refresh this long before expiry, so a call never carries a token that dies in flight. */
        private const val EARLY_MS = 60_000L
        private val JSON = "application/json".toMediaType()
        private val WANTED_SCOPES = setOf("openid", "profile", "email", "offline_access")

        /** `https://host/.well-known/<name>` for any URL on that host. */
        fun wellKnown(url: String, name: String): String = URI(url).let { "https://${it.authority}/.well-known/$name" }

        /**
         * A token response as [OAuthTokens].
         * - Contract: pure. A response without `refresh_token` keeps [oldRefresh] (RFC 6749 6).
         */
        fun parseTokens(json: JsonObject, tokenEndpoint: String, clientId: String, resource: String, oldRefresh: String?, now: Long): OAuthTokens {
            val access = json["access_token"]?.jsonPrimitive?.contentOrNull ?: error("No access token in the answer.")
            val life = json["expires_in"]?.jsonPrimitive?.longOrNull
            return OAuthTokens(
                access = access,
                refresh = json["refresh_token"]?.jsonPrimitive?.contentOrNull ?: oldRefresh,
                expiresAt = life?.let { now + it * 1000 } ?: 0,
                tokenEndpoint = tokenEndpoint,
                clientId = clientId,
                resource = resource,
            )
        }

        private fun JsonObject.string(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") || key == "issuer" } ?: error("The sign-in server has no $key.")

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
