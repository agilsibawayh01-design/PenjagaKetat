package com.mas.strictguard

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Enkripsi opsional untuk berkas backup: PBKDF2-SHA256 + AES-256-GCM. Kata sandi tidak pernah disimpan. */
object BackupCrypto {
    const val MARK = "PenjagaKetatEnc"
    private const val ITER = 150_000

    fun isEnvelope(text: String): Boolean = try {
        JSONObject(text).optString("app") == MARK
    } catch (t: Throwable) {
        false
    }

    private fun key(pass: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, iter, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: String, pass: CharArray): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass, salt, ITER), GCMParameterSpec(128, iv))
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val o = JSONObject()
        o.put("app", MARK)
        o.put("format", 1)
        o.put("iter", ITER)
        o.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        o.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        o.put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        return o.toString()
    }

    /** Mengembalikan teks asli, atau null bila kata sandi salah / berkas rusak. */
    fun decrypt(envelope: String, pass: CharArray): String? = try {
        val o = JSONObject(envelope)
        val iter = o.getInt("iter")
        if (iter < 10_000 || iter > 2_000_000) null else {
            val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(o.getString("data"), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pass, salt, iter), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }
}
