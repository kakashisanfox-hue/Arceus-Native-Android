package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

object NcaInspector {
    private fun u8(b: ByteArray, i: Int): Int =
        b[i].toInt() and 255

    private fun u32(b: ByteBuffer, i: Int): Long =
        b.getInt(i).toLong() and 0xffffffffL

    fun inspect(
        header: ByteArray,
        fileSize: Long,
        fileName: String
    ): String {
        val out = StringBuilder()
        out.append("  ===== NCA v2.0 =====\n")

        if (header.size != 0xC00) {
            return "  Cabecalho incompleto.\n"
        }

        val magic = String(
            header, 0x200, 4, Charsets.US_ASCII
        )

        if (magic != "NCA3") {
            return "  AES-XTS: NCA3 nao encontrado.\n"
        }

        val b = ByteBuffer.wrap(header)
            .order(ByteOrder.LITTLE_ENDIAN)

        val type = when (u8(header, 0x205)) {
            0 -> "Program"
            1 -> "Meta"
            2 -> "Control"
            3 -> "Manual"
            4 -> "Data"
            5 -> "PublicData"
            else -> "Desconhecido"
        }

        val titleId = java.lang.Long.toUnsignedString(
            b.getLong(0x210), 16
        ).padStart(16, '0').uppercase(Locale.US)

        val declared = b.getLong(0x208)
        val oldGeneration = u8(header, 0x206)
        val newGeneration = u8(header, 0x220)
        val keyAreaIndex = u8(header, 0x207)

        val rights = header.copyOfRange(0x230, 0x240)
        val rightsPresent = rights.any { it != 0.toByte() }

        out.append("  AES-XTS: SUCESSO! NCA3\n")
        out.append("  Arquivo: $fileName\n")
        out.append("  Tipo: $type\n")
        out.append("  Title ID: $titleId\n")
        out.append("  Tamanho declarado: $declared\n")
        out.append("  Tamanho no XCI: $fileSize\n")
        out.append(
            "  Key generation: $oldGeneration / $newGeneration\n"
        )
        out.append("  Key area index: $keyAreaIndex\n")
        out.append("  Rights ID presente: $rightsPresent\n")

        if (type == "Program") {
            out.append(
                "  PROGRAMA IDENTIFICADO: " +
                "possivel conteiner de executaveis.\n"
            )
        }

        var sections = 0
        for (i in 0 until 4) {
            val entry = 0x240 + i * 0x10
            val first = u32(b, entry)
            val last = u32(b, entry + 4)

            if (first == 0L && last == 0L) continue

            val begin = first * 512L
            val finish = last * 512L

            if (last <= first || finish > fileSize) {
                out.append(
                    "  Secao $i: limites invalidos.\n"
                )
                continue
            }

            sections++
            val fsBase = 0x400 + i * 0x200
            val fsType = u8(header, fsBase + 2)
            val hashType = u8(header, fsBase + 3)
            val encType = u8(header, fsBase + 4)

            val fsName = when (fsType) {
                0 -> "RomFS"
                1 -> "PartitionFS"
                else -> "Desconhecido ($fsType)"
            }

            val encName = when (encType) {
                0 -> "Auto"
                1 -> "Nenhuma"
                2 -> "AES-XTS"
                3 -> "AES-CTR"
                4 -> "AES-CTR-Ex"
                5 -> "AES-CTR-SkipHash"
                6 -> "AES-CTR-Ex-SkipHash"
                else -> "Desconhecida ($encType)"
            }

            val expected = header.copyOfRange(
                0x280 + i * 32,
                0x280 + (i + 1) * 32
            )
            val actual = MessageDigest
                .getInstance("SHA-256")
                .digest(
                    header.copyOfRange(
                        fsBase, fsBase + 0x200
                    )
                )

            val hashOk = expected.contentEquals(actual)

            out.append("  --- SECAO $i ---\n")
            out.append("  Inicio: $begin\n")
            out.append("  Fim: $finish\n")
            out.append("  Bytes: ${finish - begin}\n")
            out.append("  Sistema: $fsName\n")
            out.append("  Hash type: $hashType\n")
            out.append("  Criptografia: $encName\n")
            out.append(
                "  SHA256 cabecalho FS: " +
                if (hashOk) "OK\n" else "DIVERGENTE\n"
            )
        }

        out.append("  Secoes validas: $sections\n")
        out.append(
            "  Dados internos: ainda nao descriptografados.\n"
        )
        out.append(
            "  ExeFS/RomFS: leitura de arquivos pendente.\n"
        )
        out.append(
            "  Execucao ARM64: nao iniciada.\n"
        )
        return out.toString()
    }
}
