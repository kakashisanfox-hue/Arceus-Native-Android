package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Structural loader only: never executes Switch code. */
object NsoLoader {
    private const val MAX_SEGMENT = 64 * 1024 * 1024
    private const val MAX_IMAGE = 128 * 1024 * 1024

    private fun u32(b: ByteBuffer, at: Int): Long = b.getInt(at).toLong() and 0xffffffffL

    private fun decompressLz4(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var i = 0
        var j = 0
        while (i < src.size) {
            val token = src[i++].toInt() and 255
            var literal = token ushr 4
            if (literal == 15) {
                while (true) {
                    require(i < src.size) { "LZ4 literal truncado" }
                    val ext = src[i++].toInt() and 255
                    literal += ext
                    require(literal <= expected) { "LZ4 literal muito grande" }
                    if (ext != 255) break
                }
            }
            require(literal <= src.size - i && literal <= expected - j) { "LZ4 literal invalido" }
            System.arraycopy(src, i, out, j, literal)
            i += literal
            j += literal
            if (i == src.size) break
            require(i + 2 <= src.size) { "LZ4 deslocamento truncado" }
            val distance = (src[i].toInt() and 255) or ((src[i + 1].toInt() and 255) shl 8)
            i += 2
            require(distance in 1..j) { "LZ4 distancia invalida" }
            var count = (token and 15) + 4
            if ((token and 15) == 15) {
                while (true) {
                    require(i < src.size) { "LZ4 match truncado" }
                    val ext = src[i++].toInt() and 255
                    count += ext
                    require(count <= expected) { "LZ4 match muito grande" }
                    if (ext != 255) break
                }
            }
            require(count <= expected - j) { "LZ4 saida excedida" }
            repeat(count) { out[j] = out[j - distance]; j++ }
        }
        require(j == expected) { "LZ4 tamanho $j, esperado $expected" }
        return out
    }

    private fun readPlain(
        nsoOffset: Long, fileOffset: Long, count: Int,
        ncaAbsolute: Long, key: ByteArray, fs: ByteArray,
        readAt: (Long, Int) -> ByteArray
    ): ByteArray {
        val offset = nsoOffset + fileOffset
        require(offset >= 0 && count >= 0)
        val aligned = offset and -16L
        val skip = (offset - aligned).toInt()
        val bytes = ((skip + count + 15) / 16) * 16
        val encrypted = readAt(ncaAbsolute + aligned, bytes)
        require(encrypted.size == bytes) { "Leitura incompleta" }
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[i] = fs[0x147 - i]
        var block = aligned / 16L
        for (i in 15 downTo 8) { iv[i] = block.toByte(); block = block ushr 8 }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(encrypted)
        val result = decrypted.copyOfRange(skip, skip + count)
        encrypted.fill(0)
        decrypted.fill(0)
        return result
    }

    fun load(
        name: String, header: ByteArray, fileSize: Long,
        nsoOffset: Long, key: ByteArray, fs: ByteArray,
        ncaAbsolute: Long, readAt: (Long, Int) -> ByteArray
    ): String {
        val log = StringBuilder("\n===== NSO LOADER v3.1: $name =====\n")
        try {
            require(header.size >= 0x100 && String(header, 0, 4, Charsets.US_ASCII) == "NSO0") { "NSO0 invalido" }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val flags = b.getInt(0x0C)
            val field = intArrayOf(0x10, 0x20, 0x30)
            val packedField = intArrayOf(0x60, 0x64, 0x68)
            val names = arrayOf("TEXT", "RO", "DATA")
            val offsets = LongArray(3)
            val addresses = LongArray(3)
            val sizes = LongArray(3)
            val stored = LongArray(3)
            var end = 0L
            for (i in 0..2) {
                offsets[i] = u32(b, field[i])
                addresses[i] = u32(b, field[i] + 4)
                sizes[i] = u32(b, field[i] + 8)
                stored[i] = if ((flags and (1 shl i)) != 0) u32(b, packedField[i]) else sizes[i]
                require(sizes[i] <= MAX_SEGMENT && stored[i] <= MAX_SEGMENT) { "${names[i]} muito grande" }
                require(offsets[i] <= fileSize && stored[i] <= fileSize - offsets[i]) { "${names[i]} fora do NSO" }
                require(addresses[i] + sizes[i] <= MAX_IMAGE) { "Imagem excede memoria" }
                end = maxOf(end, addresses[i] + sizes[i])
            }
            val bss = u32(b, 0x3C)
            end = maxOf(end, addresses[2] + sizes[2] + bss)
            require(end <= MAX_IMAGE) { "BSS excede memoria" }
            for (i in 0..2) for (j in i+1..2) {
                require(addresses[i] + sizes[i] <= addresses[j] || addresses[j] + sizes[j] <= addresses[i]) { "Segmentos sobrepostos" }
            }
            val memory = ByteArray(end.toInt())
            try {
                for (i in 0..2) {
                    val compressed = (flags and (1 shl i)) != 0
                    val hashed = (flags and (1 shl (i + 3))) != 0
                    val source = readPlain(nsoOffset, offsets[i], stored[i].toInt(), ncaAbsolute, key, fs, readAt)
                    val segment = try {
                        if (compressed) decompressLz4(source, sizes[i].toInt()) else source.copyOf()
                    } finally { source.fill(0) }
                    try {
                        if (hashed) {
                            val digest = MessageDigest.getInstance("SHA-256").digest(segment)
                            val expected = header.copyOfRange(0xA0 + i * 32, 0xC0 + i * 32)
                            require(digest.contentEquals(expected)) { "${names[i]} SHA256 divergente" }
                        }
                        System.arraycopy(segment, 0, memory, addresses[i].toInt(), segment.size)
                        log.append("${names[i]}: ${segment.size} bytes, ${if (compressed) "LZ4 OK" else "sem compressao"}, ${if (hashed) "SHA256 OK" else "hash nao exigido"}\n")
                    } finally { segment.fill(0) }
                }
                log.append("BSS: $bss bytes\n")
                log.append("Imagem de memoria: $end bytes\n")
                log.append("MONTAGEM CONCLUIDA: 3/3 segmentos\n")
                // MOD0_INSPECT_V31
                log.append(Mod0Inspector.inspect(memory, name))
                // RELOC_V32
                log.append(RelocationInspector.apply(memory, name))
                log.append("Relocacao, HLE e execucao ARM64 ainda pendentes.\n")
            } finally { memory.fill(0) }
        } catch (e: Exception) {
            log.append("ERRO: ${e.message}\n")
        }
        return log.toString()
    }
}
