package com.ytdlp.android.license

import com.ytdlp.android.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 授权服务的客户端。只有两个动作：激活、校验。
 *
 * 地址来自 BuildConfig.LICENSE_BASE_URL（打包时用 -PlicenseBaseUrl=... 指定）。
 * 没配置时直接给出明确提示，而不是抛一个网络异常让用户以为是自己的问题。
 */
object LicenseApi {

    data class Activated(val key: String, val token: String, val exp: Long)

    /**
     * @param fatal true 表示「服务器明确拒绝了这份授权」，本地凭证应当作废；
     *              false 表示网络/服务端临时问题，应当保留本地凭证、按离线宽限处理。
     */
    class LicenseException(message: String, val fatal: Boolean = false) : Exception(message)

    private const val TIMEOUT_MS = 12_000

    fun activate(rawKey: String, device: String): Activated {
        val body = JSONObject().put("key", rawKey).put("device", device)
        val json = post("/api/activate", body)
        if (!json.optBoolean("ok")) {
            throw LicenseException(json.optString("message", "激活失败"), fatal = true)
        }
        val token = json.optString("token")
        if (token.isBlank()) {
            throw LicenseException("授权服务器没有返回凭证，请稍后再试")
        }
        return Activated(
            key = json.optString("key", rawKey),
            token = token,
            exp = json.optLong("exp", 0L),
        )
    }

    /** 续期校验。服务器认为还没到续期时间时，返回的 token 为空，沿用本地那份。 */
    fun verify(key: String, device: String, token: String): Activated {
        val body = JSONObject()
            .put("key", key)
            .put("device", device)
            .put("token", token)
        val json = post("/api/verify", body)
        if (!json.optBoolean("ok")) {
            val code = json.optString("code")
            val fatal = code in FATAL_CODES
            throw LicenseException(json.optString("message", "校验失败"), fatal)
        }
        return Activated(
            key = key,
            token = json.optString("token").ifBlank { token },
            exp = json.optLong("exp", 0L),
        )
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val base = BuildConfig.LICENSE_BASE_URL.trim().trimEnd('/')
        if (base.isBlank() || base.contains("not-configured")) {
            throw LicenseException(
                "授权服务器还没有配置。这是开发者需要先完成的一步，不是你的问题。"
            )
        }

        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) {
                throw LicenseException("授权服务器返回了空内容（HTTP ${conn.responseCode}）")
            }
            JSONObject(text)
        } catch (e: LicenseException) {
            throw e
        } catch (e: Exception) {
            throw LicenseException(
                "连不上授权服务器，请检查网络后重试。\n（${e.message ?: e.javaClass.simpleName}）"
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 这几种是「服务器说你这份授权无效」，本地必须清掉；其余按临时故障处理。 */
    private val FATAL_CODES = setOf(
        "revoked",
        "bound_elsewhere",
        "invalid_key",
        "bad_token",
        "token_expired",
    )
}
