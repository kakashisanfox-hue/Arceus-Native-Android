package com.matheus.arceusnative

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NcaHeaderCrypto {
    fun parseHeaderKey(text: String): ByteArray? {
        val line = text.lineSequence().map { it.trim() }
            .firstOrNull {
                it.substringBefore("=").trim()
                    .equals("header_key", ignoreCase = true) &&
                "=" in it
            } ?: return null

        val value = line.substringAfter("=")
            .substringBefore("#").trim()
            .removePrefix("0x")

        if (!value.matches(Regex("[0-9a-fA-F]{64}")))
            return null

        return ByteArray(32) { i ->
            value.substring(i * 2, i * 2 + 2)
                .toInt(16).toByte()
        }
    }

    private fun multiplyAlpha(t: ByteArray) {
        var carry = 0
        for (i in 0 until 16) {
            val v = t[i].toInt() and 255
            val next = v ushr 7
            t[i] = ((v shl 1) or carry).toByte()
            carry = next
        }
        if (carry != 0) {
            t[0] = (t[0].toInt() xor 0x87).toByte()
        }
    }

    fun decryptHeader(
        encrypted: ByteArray,
        key: ByteArray
    ): ByteArray {
        require(key.size == 32)
        require(encrypted.size == 0x400)

        val dataCipher = Cipher.getInstance("AES/ECB/NoPadding")
        dataCipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.copyOfRange(0, 16), "AES")
        )

        val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding")
        tweakCipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.copyOfRange(16, 32), "AES")
        )

        val output = ByteArray(encrypted.size)

        for (sector in 0 until 2) {
            val sectorNumber = ByteArray(16)
            // Nintendo XTS: numero do setor em big endian.
            sectorNumber[15] = sector.toByte()
            val tweak = tweakCipher.doFinal(sectorNumber)

            for (block in 0 until 32) {
                val offset = sector * 0x200 + block * 16
                val mixed = ByteArray(16)

                for (i in 0 until 16) {
                    mixed[i] = (
                        encrypted[offset + i].toInt() xor
                        tweak[i].toInt()
                    ).toByte()
                }

                val decrypted = dataCipher.doFinal(mixed)

                for (i in 0 until 16) {
                    output[offset + i] = (
                        decrypted[i].toInt() xor
                        tweak[i].toInt()
                    ).toByte()
                }

                multiplyAlpha(tweak)
            }
        }

        return output
    }
}
