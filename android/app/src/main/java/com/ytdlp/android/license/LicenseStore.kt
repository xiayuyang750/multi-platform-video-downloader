package com.ytdlp.android.license

import android.content.Context

/**
 * 授权状态与试用次数的本地存储。
 *
 * 只存三样东西：密钥、服务端签发的 token、token 到期时间。
 * 「是否已激活」完全由 token 是否存在决定 —— 不在本地单独记一个 bool，
 * 免得出现「bool 说激活了、token 却没了」这种自相矛盾的状态。
 *
 * 注意：这是本地存储，清应用数据会一起没掉。真正的绑定关系在服务端，
 * 所以清完数据重新输入同一个密钥，服务端仍然认这台设备（设备码没变）。
 */
class LicenseStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("license", Context.MODE_PRIVATE)

    val key: String get() = sp.getString(KEY_LICENSE, "").orEmpty()
    val token: String get() = sp.getString(KEY_TOKEN, "").orEmpty()
    val exp: Long get() = sp.getLong(KEY_EXP, 0L)

    /** 已用掉的免费解析次数。 */
    val trialUsed: Int get() = sp.getInt(KEY_TRIAL, 0)

    val activated: Boolean get() = token.isNotBlank()

    val trialLeft: Int get() = (TRIAL_TOTAL - trialUsed).coerceAtLeast(0)

    fun save(key: String, token: String, exp: Long) {
        sp.edit()
            .putString(KEY_LICENSE, key)
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXP, exp)
            .apply()
    }

    /** 只清凭证、保留密钥：密钥被停用或换绑时，输入框里还留着原来那串，方便排查。 */
    fun clearToken() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_EXP).apply()
    }

    fun addTrial() {
        sp.edit().putInt(KEY_TRIAL, (trialUsed + 1).coerceAtMost(TRIAL_TOTAL)).apply()
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }

    companion object {
        /** 未激活时允许免费解析的次数。 */
        const val TRIAL_TOTAL = 3

        private const val KEY_LICENSE = "license_key"
        private const val KEY_TOKEN = "license_token"
        private const val KEY_EXP = "license_exp"
        private const val KEY_TRIAL = "trial_used"
    }
}
