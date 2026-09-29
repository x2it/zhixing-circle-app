package com.realtor.geeksales.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 第三方 API Key 安全存储：使用 Android Keystore 生成的 AES-256 密钥加密，
 * 密文 + IV 存 SharedPreferences。明文只存在于内存，密钥材料由系统硬件级保护。
 */
@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    companion object {
        private const val PREFS = "tma_prefs"
        private const val KEY_ALIAS = "tma_workbuddy_api_key"
        private const val KEY_CIPHER = "key_cipher"
        private const val DEVICE_ID = "device_id"
        private const val KEY_IV = "key_iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 设备唯一标识（首启生成 UUID 并持久化；卸载重装会变）——v2.7.3 设备握手/溯源用 */
    fun deviceId(): String = prefs.getString(DEVICE_ID, null)
        ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(DEVICE_ID, it).apply() }

    /** 保存 API Key（加密后落盘），返回是否成功 */
    fun save(apiKey: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    /** 读取 API Key；未配置或解密失败返回 null */
    fun load(): String? = runCatching {
        val cipherB64 = prefs.getString(KEY_CIPHER, null) ?: return null
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    /** 清除已保存的 API Key */
    fun clear() {
        prefs.edit().remove(KEY_CIPHER).remove(KEY_IV).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
