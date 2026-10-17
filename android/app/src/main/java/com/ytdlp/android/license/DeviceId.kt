package com.ytdlp.android.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设备码：给「这台手机」算一个稳定、不可反推的标识。
 *
 * 为什么不用手机序列号 / IMEI：Android 10 起普通应用已经读不到了，
 * 系统只对电话与运营商类应用开放。所以实际可行的是 ANDROID_ID ——
 * 它在「同一设备 + 同一用户 + 同一签名」下是稳定的，卸载重装不会变。
 *
 * 也不把 Build.FINGERPRINT 掺进来：系统升级会改它，一升级授权就失效，
 * 对用户来说等同于「无缘无故掉授权」。
 *
 * 换一台手机 → ANDROID_ID 一定不同 → 设备码不同 → 服务端拒绝，这正是要的效果。
 */
object DeviceId {

    fun get(ctx: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val raw = listOf(
            "wm-license-v1",
            androidId,
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
        ).joinToString("|")

        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(32)
    }
}
