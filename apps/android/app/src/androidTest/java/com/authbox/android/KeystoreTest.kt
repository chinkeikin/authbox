package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.AlgorithmParameters
import java.math.BigInteger

class KeystoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val box = KeystoreBox(context, "authbox.test.keystore")

    @Test fun encryptionAndIdentity() {
        val message = "synthetic-local-secret".toByteArray()
        val encrypted = box.seal("state", message)
        assertFalse(String(encrypted).contains(String(message)))
        assertArrayEquals(message, box.open("state", encrypted))
        assertFalse(encrypted.contentEquals(box.seal("state", message)))
        for (candidate in listOf(encrypted.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })) {
            assertThrows(Exception::class.java) { box.open("state", candidate) }
        }
        assertThrows(Exception::class.java) { box.open("other", encrypted) }
        assertFalse(box.privateKeyExportable())
        val jwk = box.publicKey()
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(
            BigInteger(1, unb64(jwk.getString("x"))), BigInteger(1, unb64(jwk.getString("y")))), parameters))
        val signature = unb64(box.sign("AuthBox synthetic signature"))
        assertEquals(64, signature.size)
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update("AuthBox synthetic signature".toByteArray()) }
        assertTrue(verifier.verify(KeystoreBox.signatureDer(signature)))
        println("AuthBox signing key securityLevel=" + box.signingKeyInfo().securityLevel)
    }

    @Test fun survivesProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("phase", "write")
        if (phase == "write") {
            box.write("restart", JSONObject().put("marker", "synthetic-restart").put("publicKey", box.publicKey()).toString().toByteArray())
        } else {
            val saved = JSONObject(String(requireNotNull(box.read("restart"))))
            assertEquals("synthetic-restart", saved.getString("marker"))
            assertEquals(saved.getJSONObject("publicKey").toString(), box.publicKey().toString())
            box.delete("restart")
        }
    }
}
