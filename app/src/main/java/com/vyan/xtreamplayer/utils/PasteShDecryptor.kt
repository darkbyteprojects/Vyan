package com.vyan.xtreamplayer.utils

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PasteShDecryptor {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko)"

    suspend fun decrypt(urlWithHash: String): String = withContext(Dispatchers.IO) {
        try {
            val hashIdx = urlWithHash.indexOf('#')
            if (hashIdx <= 0) return@withContext ""

            val baseUrl = urlWithHash.substring(0, hashIdx)
            val clientKey = urlWithHash.substring(hashIdx + 1)
            val id = baseUrl.substringAfterLast('/')

            val request = Request.Builder().url("$baseUrl.txt").header("User-Agent", UA).build()
            val raw = client.newCall(request).execute().body?.string() ?: return@withContext ""

            val lines = raw.lines()
            if (lines.isEmpty()) return@withContext ""
            val serverKey = lines.first().trim()
            val b64 = lines.drop(1).joinToString("").trim()
            if (b64.isEmpty()) return@withContext ""

            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)
            if (cipherBytes.size < 17) return@withContext ""

            val salt = cipherBytes.copyOfRange(8, 16)
            val ct = cipherBytes.copyOfRange(16, cipherBytes.size)
            // FIXED: Explicit variable curly braces to prevent string interpolation error
            val password = "${id}${serverKey}${clientKey}https://paste.sh"

            // 1. PBKDF2-HMAC-SHA512
            try {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                val spec = PBEKeySpec(password.toCharArray(), salt, 1, 48 * 8)
                val keyIv = factory.generateSecret(spec).encoded

                val key = keyIv.copyOfRange(0, 32)
                val iv = keyIv.copyOfRange(32, 48)

                val decrypted = aesCbcDecrypt(ct, key, iv)
                if (decrypted.isNotEmpty()) return@withContext decrypted
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. OpenSSL EVP_BytesToKey Fallback
            try {
                val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt, 32, 16)
                val decrypted = aesCbcDecrypt(ct, key, iv)
                if (decrypted.isNotEmpty()) return@withContext decrypted
            } catch (e: Exception) {
                e.printStackTrace()
            }

            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun aesCbcDecrypt(ct: ByteArray, key: ByteArray, iv: ByteArray): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val out = mutableListOf<Byte>()
        var prev = byteArrayOf()

        while (out.size < keyLen + ivLen) {
            md5.reset()
            md5.update(prev)
            md5.update(password)
            md5.update(salt)
            prev = md5.digest()
            out.addAll(prev.toList())
        }

        val all = out.toByteArray()
        val key = all.copyOfRange(0, keyLen)
        val iv = all.copyOfRange(keyLen, keyLen + ivLen)
        return Pair(key, iv)
    }
}