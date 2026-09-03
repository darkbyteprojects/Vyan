package com.vyan.xtreamplayer.sports

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SportsCrypto {

    val GETDATA_DEFAULT_TOKEN = reveal("217f2d7e717820787f7f7c7c7e7e2e7a707e787f7d7e7a2e717c7e7f727c7f78707a707e7d7e7e2871787f7f717c727e7c7c707e7a7f7e78717c7d7f717c7f", 25)
    private val V2_KEY = reveal("5a593c7e6966487b58615f396e66693f", 13)
    private val V2_IV = reveal("f791d5edc5cb9feaf492f0c7fe94d6e8", 166)

    fun reveal(str: String, key: Int): String {
        val bArrFromHex = fromHex(str)
        for (i in bArrFromHex.indices) {
            bArrFromHex[i] = (bArrFromHex[i].toInt() xor key).toByte()
        }
        return try {
            String(bArrFromHex, Charsets.UTF_8)
        } catch (th: Throwable) {
            ""
        }
    }

    private fun fromHex(str: String): ByteArray {
        val length = str.length
        val bArr = ByteArray(length / 2)
        var i = 0
        while (i < length) {
            val i10 = i + 2
            bArr[i / 2] = str.substring(i, i10).toInt(16).toByte()
            i = i10
        }
        return bArr
    }

    fun xorHex(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val sb = java.lang.StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = (b.toInt() xor 90) and 255
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    fun decryptConfig(rawText: String): String {
        var text = rawText.trim()
        if (text.startsWith("cfj1:")) {
            text = text.substring(5)
        }

        val clean = text.replace(Regex("[\\n\\r\\t ]"), "")
        val decoded = Base64.decode(clean, Base64.DEFAULT)

        val b = intArrayOf(29, 88, 17, 104, 66, 7, 91, 34, 113, 5, 47, 96)
        val b2 = intArrayOf(71, 12, 83, 44, 9, 121, 36, 58, 101, 22, 63)
        val b3 = intArrayOf(6, 39, 95, 14, 74, 52, 117, 27, 68, 3, 86, 41, 109)
        val mat = ByteArray(32)

        for (i in 0 until 32) {
            val i10 = b[i % 12] and 255
            val i11 = b2[((i * 3) + 1) % 11] and 255
            val i12 = i and 7
            val value = ((i11 ushr (8 - i12)) or (i11 shl i12)) and 255
            mat[i] = (((i10 xor value xor (b3[((i * 5) + 2) % 13] and 255)) xor 90) xor i).toByte()
        }

        val out = ByteArray(decoded.size)
        val l = decoded.size
        for (i in 0 until l) {
            val m = mat[i % mat.size].toInt() and 255
            val d = decoded[i].toInt() and 255
            val offset = ((i * 29) + 71) and 255
            out[(l - 1) - i] = (m xor d xor offset).toByte()
        }
        return String(out, Charsets.UTF_8).trim()
    }

    fun decryptV2Payload(encryptedBase64: String): String? {
        try {
            val raw1 = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val charArray = String(raw1, Charsets.UTF_8).toCharArray()

            swapPairs(charArray)
            charArray.reverse()

            val str2 = String(charArray)
            val suffix = "abcdefghijklmnop"

            if (!str2.endsWith(suffix)) {
                return null
            }

            val nativeInput = str2.substring(0, str2.length - suffix.length)
            return decodeNativeStage(nativeInput)
        } catch (e: Exception) {
            return null
        }
    }

    private fun decodeNativeStage(str: String): String? {
        try {
            val raw2 = Base64.decode(str, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(V2_KEY.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(V2_IV.toByteArray(Charsets.UTF_8))
            )
            val decryptedBytes = cipher.doFinal(raw2)

            val charArray = String(decryptedBytes, Charsets.UTF_8).toCharArray()
            swapPairs(charArray)
            charArray.reverse()

            val cleanedB64 = cleanBase64(String(charArray))
            val finalBytes = Base64.decode(cleanedB64, Base64.DEFAULT)
            var finalStr = String(finalBytes, Charsets.UTF_8)

            val repaired = repairJsonTail(finalStr)
            if (repaired.isNotEmpty()) {
                finalStr = repaired
            }

            return finalStr.trim()
        } catch (e: Exception) {
            return null
        }
    }

    private fun swapPairs(cArr: CharArray) {
        var i = 0
        while (i + 1 < cArr.size) {
            val c9 = cArr[i]
            cArr[i] = cArr[i + 1]
            cArr[i + 1] = c9
            i += 2
        }
    }

    private fun cleanBase64(str: String): String {
        val sb2 = java.lang.StringBuilder(str.length)
        for (i in 0 until str.length) {
            val c = str[i]
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/') {
                sb2.append(c)
            }
        }
        while (sb2.length % 4 != 0) {
            sb2.append('=')
        }
        return sb2.toString()
    }

    private fun repairJsonTail(str: String?): String {
        if (str == null) return ""
        val strTrim = str.trim()
        if (strTrim.startsWith("[")) {
            return repairJsonArrayTail(strTrim)
        }
        if (!strTrim.startsWith("{")) {
            return ""
        }
        return repairJsonObjectTail(strTrim)
    }

    private fun repairJsonArrayTail(str: String): String {
        var i = -1
        var i10 = -1
        var z10 = false
        var z11 = false
        var i11 = 0
        for (i12 in 0 until str.length) {
            val c = str[i12]
            if (z10) {
                if (z11) z11 = false
                else if (c == '\\') z11 = true
                else if (c == '\"') z10 = false
            } else if (c == '\"') {
                z10 = true
            } else if (c == '{' || c == '[') {
                if (c == '{' && i11 == 1 && i10 < 0) i10 = i12
                i11++
            } else if (c == '}' || c == ']') {
                i11--
                if (i11 == 1 && c == '}') i = i12
                else if (i11 == 0 && c == ']') return str.substring(0, i12 + 1)
            }
        }
        if (i > 0) return str.substring(0, i + 1) + "]"
        if (i10 >= 0) {
            val strRepairJsonObjectTail = repairJsonObjectTail(str.substring(i10))
            if (strRepairJsonObjectTail.isNotEmpty()) return "[$strRepairJsonObjectTail]"
        }
        return ""
    }

    private fun repairJsonObjectTail(str: String): String {
        var i = -1
        var z10 = false
        var z11 = false
        var i10 = 0
        for (i11 in 0 until str.length) {
            val c = str[i11]
            if (z10) {
                if (z11) z11 = false
                else if (c == '\\') z11 = true
                else if (c == '\"') z10 = false
            } else if (c == '\"') {
                z10 = true
            } else if (c == '{' || c == '[') {
                i10++
            } else if (c == '}' || c == ']') {
                i10--
                if (i10 == 0 && c == '}') return str.substring(0, i11 + 1)
            } else if (c == ',' && i10 == 1) {
                i = i11
            }
        }
        if (i > 0) return str.substring(0, i) + "}"
        return ""
    }
}