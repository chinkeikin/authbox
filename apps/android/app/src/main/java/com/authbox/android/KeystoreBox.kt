package com.authbox.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun unb64(value: String): ByteArray {
    require(value.matches(Regex("[A-Za-z0-9_-]*")))
    return Base64.getUrlDecoder().decode(value).also { require(b64(it) == value) }
}

class KeystoreBox(private val context: Context, private val namespace: String = "authbox.v1") {
    init { require(namespace.matches(Regex("[a-z0-9.-]{1,80}"))) }
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val aesAlias = "$namespace.storage"
    private val ecAlias = "$namespace.device"

    @Synchronized private fun storageKey(): SecretKey {
        if (!keys.containsAlias(aesAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(aesAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        return keys.getKey(aesAlias, null) as SecretKey
    }

    @Synchronized fun publicKey(): JSONObject {
        if (!keys.containsAlias(ecAlias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ecAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val key = keys.getCertificate(ecAlias).publicKey as ECPublicKey
        return JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", b64(unsigned(key.w.affineX))).put("y", b64(unsigned(key.w.affineY)))
    }

    @Synchronized fun sign(payload: String): String {
        publicKey()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.getKey(ecAlias, null) as java.security.PrivateKey)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val parts = ASN1Sequence.getInstance(signer.sign())
        require(parts.size() == 2)
        return b64(unsigned(ASN1Integer.getInstance(parts.getObjectAt(0)).positiveValue) +
            unsigned(ASN1Integer.getInstance(parts.getObjectAt(1)).positiveValue))
    }

    fun privateKeyExportable(): Boolean {
        publicKey()
        return keys.getKey(ecAlias, null).encoded != null
    }

    fun signingKeyInfo(): KeyInfo {
        publicKey()
        return KeyFactory.getInstance("EC", "AndroidKeyStore")
            .getKeySpec(keys.getKey(ecAlias, null), KeyInfo::class.java)
    }

    @Synchronized fun seal(name: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, storageKey())
        cipher.updateAAD(aad(name))
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }

    @Synchronized fun open(name: String, envelope: ByteArray): ByteArray {
        require(envelope.size >= 29 && envelope[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD(aad(name))
        return cipher.doFinal(envelope.copyOfRange(13, envelope.size))
    }

    private fun aad(name: String): ByteArray {
        require(name.matches(Regex("[a-z0-9.-]{1,100}")))
        return "AuthBox/local/v1/${context.packageName}/$namespace/$name".toByteArray(Charsets.UTF_8)
    }

    private fun file(name: String): AtomicFile {
        aad(name)
        val directory = File(context.noBackupFilesDir, namespace).apply { mkdirs() }
        return AtomicFile(File(directory, name))
    }

    @Synchronized fun write(name: String, data: ByteArray) {
        val encrypted = seal(name, data)
        val target = file(name)
        val stream = target.startWrite()
        try { stream.write(encrypted); target.finishWrite(stream) }
        catch (error: Throwable) { target.failWrite(stream); throw error }
    }

    @Synchronized fun read(name: String): ByteArray? {
        val target = file(name)
        return if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) null
        else open(name, target.readFully())
    }

    @Synchronized fun delete(name: String) { file(name).delete() }

    companion object {
        private fun unsigned(number: BigInteger): ByteArray {
            require(number.signum() >= 0 && number.bitLength() <= 256)
            val bytes = number.toByteArray().let { if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else it }
            return ByteArray(32 - bytes.size) + bytes
        }

        fun signatureDer(raw: ByteArray): ByteArray {
            require(raw.size == 64)
            return DERSequence(arrayOf(ASN1Integer(BigInteger(1, raw.copyOfRange(0, 32))),
                ASN1Integer(BigInteger(1, raw.copyOfRange(32, 64))))).encoded
        }
    }
}
