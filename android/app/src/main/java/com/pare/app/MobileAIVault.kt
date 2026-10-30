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
import kotlinx.serialization.Serializable

/** This file and Keystore alias are separate from auth credentials and the synchronized database. */
@Serializable class MobileAIPrivateData(val config:MobileAIConfig=MobileAIConfig(),val apiKey:String="",val history:List<MobileAITrace> = emptyList())
interface MobileAIStore {
    fun load():MobileAIPrivateData
    fun save(data:MobileAIPrivateData)
    fun clear()
}
class MobileAIVault(context:Context):MobileAIStore {
    private val prefs=context.getSharedPreferences("mobile-ai-vault",Context.MODE_PRIVATE)
    private val alias="pare.mobile.ai.private.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun load():MobileAIPrivateData {
        val encoded=prefs.getString("encrypted",null) ?: return MobileAIPrivateData()
        val bytes=Base64.decode(encoded,Base64.NO_WRAP)
        require(bytes.size in 13..600_000)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return wire.decodeFromString(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8))
    }
    @Synchronized override fun save(data:MobileAIPrivateData) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val plain=wire.encodeToString(data).toByteArray(Charsets.UTF_8)
        require(plain.size<=500_000)
        val bytes=cipher.iv+cipher.doFinal(plain)
        check(prefs.edit().putString("encrypted",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())
    }
    @Synchronized override fun clear() {check(prefs.edit().clear().commit())}
}
