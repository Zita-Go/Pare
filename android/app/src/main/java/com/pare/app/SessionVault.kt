package com.pare.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens are never put in the database, logs, backups or ordinary plaintext preferences. */
class SessionVault(context:Context):SessionStore {
    private val prefs=context.getSharedPreferences("session-vault",Context.MODE_PRIVATE)
    private val alias="pare.auth.session.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun load():Session? {
        val encoded=prefs.getString("encrypted",null) ?: return null
        val data=Base64.decode(encoded,Base64.NO_WRAP)
        require(data.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,data.copyOfRange(0,12)))
        return wire.decodeFromString(cipher.doFinal(data.copyOfRange(12,data.size)).toString(Charsets.UTF_8))
    }
    override fun save(session:Session) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes=cipher.iv+cipher.doFinal(wire.encodeToString(session).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("encrypted",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit()) {"登录状态保存失败"}
    }
    override fun clear() {check(prefs.edit().clear().commit()) {"退出保存失败"}}
}
