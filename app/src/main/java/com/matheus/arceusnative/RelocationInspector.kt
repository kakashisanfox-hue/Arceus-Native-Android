package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

object RelocationInspector {
    private const val RELATIVE = 1027L
    private const val ABS64 = 257L
    private const val GLOB_DAT = 1025L
    private const val JUMP_SLOT = 1026L

    private data class Entry(
        val target: Long,
        val info: Long,
        val addend: Long
    )

    private fun fits(
        size: Int, at: Long, length: Long
    ): Boolean =
        at >= 0 && length >= 0 &&
        at <= size.toLong() &&
        length <= size.toLong() - at

    private fun hex(v: Long): String =
        "0x" + v.toULong().toString(16)

    fun apply(memory: ByteArray, module: String): String {
        val log = StringBuilder(
            "\n===== RELOC v3.4: $module =====\n"
        )
        try {
            val b = ByteBuffer.wrap(memory)
                .order(ByteOrder.LITTLE_ENDIAN)

            require(fits(memory.size, 4, 4))
            val mod = b.getInt(4).toLong()
            require(fits(memory.size, mod, 28))
            require(
                String(
                    memory, mod.toInt(), 4,
                    Charsets.US_ASCII
                ) == "MOD0"
            ) { "MOD0 ausente" }

            val dynamic =
                mod + b.getInt(mod.toInt() + 4).toLong()
            require(fits(memory.size, dynamic, 16))

            val tags = mutableMapOf<Long, Long>()
            var ended = false

            for (i in 0 until 256) {
                val at = dynamic + i * 16L
                if (!fits(memory.size, at, 16)) break
                val tag = b.getLong(at.toInt())
                if (tag == 0L) {
                    ended = true
                    break
                }
                tags[tag] = b.getLong(at.toInt() + 8)
            }
            require(ended) { "Dynamic sem terminador" }

            val rela = tags[7L] ?: 0L
            val relaSize = tags[8L] ?: 0L
            val relaEnt = tags[9L] ?: 24L

            require(relaEnt == 24L)
            require(relaSize >= 0 && relaSize % 24 == 0L)
            require(relaSize / 24 <= 500000L)
            require(fits(memory.size, rela, relaSize))

            val entries = ArrayList<Entry>(
                (relaSize / 24).toInt()
            )

            for (i in 0 until (relaSize / 24).toInt()) {
                val at = (rela + i * 24L).toInt()
                entries.add(
                    Entry(
                        b.getLong(at),
                        b.getLong(at + 8),
                        b.getLong(at + 16)
                    )
                )
            }

            log.append("RELA principal: ${entries.size}\n")

            val jmp = tags[23L]
            val pltSize = tags[2L] ?: 0L
            val pltType = tags[20L]

            val pltEntries = ArrayList<Entry>()
            if (jmp != null && pltSize > 0) {
                require(pltType == 7L) {
                    "Formato PLT diferente de RELA"
                }
                require(pltSize % 24L == 0L)
                require(pltSize / 24L <= 500000L)
                require(fits(memory.size, jmp, pltSize))

                for (i in 0 until (pltSize / 24L).toInt()) {
                    val at = (jmp + i * 24L).toInt()
                    pltEntries.add(
                        Entry(
                            b.getLong(at),
                            b.getLong(at + 8),
                            b.getLong(at + 16)
                        )
                    )
                }
            }

            log.append(
                "JMPREL/PLT: ${pltEntries.size}\n"
            )

            val symtab = tags[6L]
            val strtab = tags[5L]
            val strsz = tags[10L] ?: 0L
            val syment = tags[11L] ?: 24L

            val symbolReady =
                symtab != null && strtab != null &&
                syment == 24L &&
                fits(memory.size, strtab, strsz) &&
                strsz > 0L

            fun symbolName(index: Long): String {
                if (!symbolReady || index > 1000000L)
                    return "<indisponivel>"

                val at = symtab!! + index * 24L
                if (!fits(memory.size, at, 24L))
                    return "<fora da imagem>"

                val off =
                    b.getInt(at.toInt()).toLong() and
                    0xffffffffL

                if (off >= strsz)
                    return "<nome invalido>"

                val begin = (strtab!! + off).toInt()
                val limit = (strtab + strsz).toInt()
                var end = begin

                while (
                    end < limit &&
                    end - begin < 160 &&
                    memory[end] != 0.toByte()
                ) end++

                return String(
                    memory, begin, end - begin,
                    Charsets.UTF_8
                )
            }

            var relative = 0
            var invalid = 0
            var externalAddends = 0
            var symbolic = 0
            var unsupported = 0
            var shown = 0

            val all = entries + pltEntries

            for ((index, e) in all.withIndex()) {
                val type = e.info and 0xffffffffL
                val sym = e.info ushr 32

                when {
                    type == RELATIVE && sym == 0L -> {
                        if (!fits(memory.size, e.target, 8)) {
                            invalid++
                            if (invalid <= 5) {
                                log.append(
                                    "DESTINO INVALIDO #$index " +
                                    "${hex(e.target)} " +
                                    "addend=${hex(e.addend)}\n"
                                )
                            }
                        } else {
                            relative++
                            if (
                                e.addend < 0 ||
                                e.addend >= memory.size.toLong()
                            ) {
                                externalAddends++
                                if (externalAddends <= 3) {
                                    log.append(
                                        "ADDEND EXTERNO #$index " +
                                        "${hex(e.addend)}\n"
                                    )
                                }
                            }
                        }
                    }
                    type == ABS64 ||
                    type == GLOB_DAT ||
                    type == JUMP_SLOT -> {
                        symbolic++
                        if (shown < 12) {
                            log.append(
                                "SIMBOLO #$index " +
                                "tipo=$type " +
                                "indice=$sym " +
                                "nome=${symbolName(sym)}\n"
                            )
                            shown++
                        }
                    }
                    else -> unsupported++
                }
            }

            log.append("RELATIVE validas: $relative\n")
            log.append(
                "Addends fora da imagem: $externalAddends\n"
            )
            log.append(
                "Dependentes de simbolos: $symbolic\n"
            )
            log.append(
                "Tipos nao suportados: $unsupported\n"
            )
            log.append("Destinos invalidos: $invalid\n")

            if (invalid != 0 || unsupported != 0) {
                log.append(
                    "Aplicacao cancelada: validacao incompleta.\n"
                )
                return log.toString()
            }

            // Snapshot completo das entradas antes de escrever.
            // Enderecamento virtual de base zero.
            var applied = 0
            for (e in all) {
                val type = e.info and 0xffffffffL
                val sym = e.info ushr 32

                if (type == RELATIVE && sym == 0L) {
                    b.putLong(e.target.toInt(), e.addend)
                    applied++
                }
            }

            log.append(
                "RELATIVE APLICADAS: $applied\n"
            )
            log.append(
                "SIMBOLOS EXTERNOS: aguardando " +
                "mapa conjunto dos modulos.\n"
            )
            log.append(
                "Memoria temporaria; base virtual zero.\n"
            )
            log.append(
                "Execucao ARM64: nao iniciada.\n"
            )
        } catch (e: Exception) {
            log.append(
                "ERRO: ${e.javaClass.simpleName}: " +
                "${e.message}\n"
            )
        }
        return log.toString()
    }
}
