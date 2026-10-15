package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object ExefsProbe {
    // EXEFS_PROBE_V21

    fun parseKeys(text: String): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val regex = Regex(
            "^key_area_key_(application|ocean|system)_" +
            "([0-9a-fA-F]{2})$"
        )

        for (line in text.lineSequence()) {
            val clean = line.substringBefore("#").trim()
            if (!clean.contains("=")) continue
            val name = clean.substringBefore("=")
                .trim().lowercase()
            val value = clean.substringAfter("=")
                .trim().removePrefix("0x")

            if (!regex.matches(name)) continue
            if (!value.matches(Regex("[0-9a-fA-F]{32}")))
                continue

            result[name] = ByteArray(16) { i ->
                value.substring(i * 2, i * 2 + 2)
                    .toInt(16).toByte()
            }
        }
        return result
    }

    private fun le32(b: ByteBuffer, pos: Int): Long =
        b.getInt(pos).toLong() and 0xffffffffL

    private fun decryptKey(
        encrypted: ByteArray,
        areaKey: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(areaKey, "AES")
        )
        return cipher.doFinal(encrypted)
    }

    private fun decryptCtr(
        encrypted: ByteArray,
        key: ByteArray,
        fsHeader: ByteArray,
        ncaOffset: Long
    ): ByteArray {
        require(ncaOffset >= 0 && ncaOffset % 16L == 0L)

        val counter = ByteArray(16)
        for (i in 0 until 8) {
            counter[i] = fsHeader[0x147 - i]
        }

        var block = ncaOffset / 16L
        for (i in 15 downTo 8) {
            counter[i] = block.toByte()
            block = block ushr 8
        }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(counter)
        )
        return cipher.doFinal(encrypted)
    }

    fun probe(
        header: ByteArray,
        ncaSize: Long,
        ncaAbsolute: Long,
        keys: Map<String, ByteArray>,
        readAt: (Long, Int) -> ByteArray
    ): String {
        val out = StringBuilder()
        out.append("\n===== EXEFS v3.5 =====\n")

        try {
            val b = ByteBuffer.wrap(header)
                .order(ByteOrder.LITTLE_ENDIAN)

            val generation = maxOf(
                header[0x206].toInt() and 255,
                header[0x220].toInt() and 255
            )
            val masterIndex = if (generation <= 1) {
                0
            } else {
                generation - 1
            }

            val areaIndex = header[0x207].toInt() and 255
            val areaName = when (areaIndex) {
                0 -> "application"
                1 -> "ocean"
                2 -> "system"
                else -> {
                    out.append("Indice de chave desconhecido.\n")
                    return out.toString()
                }
            }

            val keyName = "key_area_key_${areaName}_" +
                masterIndex.toString(16).padStart(2, '0')

            out.append("Chave necessaria: $keyName\n")

            val areaKey = keys[keyName]
            if (areaKey == null) {
                out.append(
                    "Chave ausente no prod.keys.\n"
                )
                return out.toString()
            }

            val rights = header.copyOfRange(0x230, 0x240)
            if (rights.any { it != 0.toByte() }) {
                out.append(
                    "Rights ID presente: titlekey necessaria.\n"
                )
                return out.toString()
            }

            val encryptedContentKey =
                header.copyOfRange(0x320, 0x330)
            val contentKey = decryptKey(
                encryptedContentKey, areaKey
            )

            try {
                var found = false

                NsoLinkSession.reset()
                try {
                for (i in 0 until 4) {
                    val entry = 0x240 + i * 16
                    val start = le32(b, entry) * 512L
                    val end = le32(b, entry + 4) * 512L

                    if (start == 0L || end <= start ||
                        end > ncaSize) continue

                    val fsBase = 0x400 + i * 0x200
                    val fsType =
                        header[fsBase + 2].toInt() and 255
                    val encType =
                        header[fsBase + 4].toInt() and 255
                    val hashType =
                        header[fsBase + 3].toInt() and 255

                    if (fsType != 1 || encType != 3 ||
                        hashType != 2) continue

                    found = true
                    out.append("Secao $i: PartitionFS AES-CTR\n")

                    val pfsRelative =
                        b.getLong(fsBase + 0x40)
                    val pfsSize =
                        b.getLong(fsBase + 0x48)

                    if (pfsRelative < 0 || pfsSize < 16 ||
                        pfsRelative > end - start ||
                        pfsSize > end - start - pfsRelative) {
                        out.append(
                            "Limites PFS0 invalidos.\n"
                        )
                        continue
                    }

                    val pfsNcaOffset = start + pfsRelative
                    val aligned = pfsNcaOffset and -16L
                    val skip = (pfsNcaOffset - aligned).toInt()

                    val firstEncrypted = readAt(
                        ncaAbsolute + aligned,
                        4096 + 16
                    )
                    val firstPlain = decryptCtr(
                        firstEncrypted,
                        contentKey,
                        header.copyOfRange(
                            fsBase, fsBase + 0x200
                        ),
                        aligned
                    )
                    val first = firstPlain.copyOfRange(
                        skip, firstPlain.size
                    )

                    val magic = String(
                        first, 0, 4, Charsets.US_ASCII
                    )
                    out.append("Assinatura: $magic\n")

                    if (magic != "PFS0") {
                        out.append(
                            "PFS0 nao reconhecido; " +
                            "verificar chaves/contador.\n"
                        )
                        continue
                    }

                    val table = ByteBuffer.wrap(first)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    val count = table.getInt(4)
                    val namesSize = table.getInt(8)

                    if (count !in 0..100 ||
                        namesSize !in 0..32768) {
                        out.append(
                            "Tabela PFS0 fora dos limites.\n"
                        )
                        continue
                    }

                    val tableBytes = 16 + count * 24 +
                        namesSize
                    if (tableBytes > first.size) {
                        out.append(
                            "Tabela maior que a janela inicial.\n"
                        )
                        continue
                    }

                    val dataStart = tableBytes.toLong()
                    out.append(
                        "PFS0 CONFIRMADO! $count arquivos\n"
                    )

                    for (j in 0 until count) {
                        val off = 16 + j * 24
                        val fileOff = table.getLong(off)
                        val fileSize = table.getLong(off + 8)
                        val nameOff = table.getInt(off + 16)
                        val namesStart = 16 + count * 24

                        if (nameOff < 0 ||
                            nameOff >= namesSize ||
                            fileOff < 0 || fileSize < 0 ||
                            dataStart + fileOff > pfsSize ||
                            fileSize > pfsSize -
                                dataStart - fileOff) {
                            out.append(
                                "Entrada $j invalida.\n"
                            )
                            continue
                        }

                        val nameStart = namesStart + nameOff
                        var nameEnd = nameStart
                        while (nameEnd < namesStart + namesSize &&
                            first[nameEnd] != 0.toByte()) {
                            nameEnd++
                        }
                        val name = String(
                            first, nameStart,
                            nameEnd - nameStart,
                            Charsets.UTF_8
                        )
                        out.append(
                            "Arquivo: $name ($fileSize bytes)\n"
                        )

                        // NSO_REAL_V22
                        if (name in listOf(
                                "main", "rtld", "sdk",
                                "subsdk0", "subsdk1"
                            ) && fileSize >= 0x100L) {
                            try {
                                val nsoOffset =
                                    pfsNcaOffset +
                                    dataStart + fileOff

                                val alignedNso =
                                    nsoOffset and -16L
                                val skipNso =
                                    (nsoOffset - alignedNso).toInt()

                                val encryptedNso = readAt(
                                    ncaAbsolute + alignedNso,
                                    0x100 + 16
                                )

                                val decryptedNso = decryptCtr(
                                    encryptedNso,
                                    contentKey,
                                    header.copyOfRange(
                                        fsBase, fsBase + 0x200
                                    ),
                                    alignedNso
                                )

                                val nsoHeader =
                                    decryptedNso.copyOfRange(
                                        skipNso,
                                        skipNso + 0x100
                                    )

                                out.append(
                                    "\n===== NSO REAL v2.2: $name =====\n"
                                )
                                out.append(
                                    NsoInspector.inspectHeader(
                                        nsoHeader
                                    )
                                )
                                // NSO_LOADER_V30
                                out.append(NsoLoader.load(
                                    name, nsoHeader, fileSize,
                                    nsoOffset, contentKey,
                                    header.copyOfRange(fsBase, fsBase + 0x200),
                                    ncaAbsolute,
                                    { position, length -> readAt(position, length) }
                                ))
                                out.append("\n")

                                nsoHeader.fill(0)
                                decryptedNso.fill(0)
                                encryptedNso.fill(0)
                            } catch (e: Exception) {
                                out.append(
                                    "NSO $name: erro ${e.message}\n"
                                )
                            }
                        }

                    }
                }

                out.append(NsoLinkSession.finish())
                } finally { NsoLinkSession.reset() }
                if (!found) {
                    out.append(
                        "Nenhuma secao ExeFS compativel.\n"
                    )
                }
            } finally {
                contentKey.fill(0)
            }
        } catch (e: Exception) {
            out.append("Erro ExeFS: ${e.message}\n")
        }

        out.append(
            "Nenhum executavel foi iniciado.\n"
        )
        return out.toString()
    }
}
