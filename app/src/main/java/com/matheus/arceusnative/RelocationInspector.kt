package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

object RelocationInspector {
    private fun valid(n: Int, p: Long, len: Long): Boolean =
        p >= 0L && len >= 0L &&
        p <= n.toLong() && len <= n.toLong() - p

    fun apply(memory: ByteArray, module: String): String {
        val out = StringBuilder(
            "\n===== RELOC v3.2: $module =====\n"
        )
        try {
            val b = ByteBuffer.wrap(memory)
                .order(ByteOrder.LITTLE_ENDIAN)

            val mod = 4L + b.getInt(4).toLong()
            if (!valid(memory.size, mod, 28L))
                return out.append("MOD0 invalido.\n").toString()

            val magic = String(
                memory, mod.toInt(), 4, Charsets.US_ASCII
            )
            if (magic != "MOD0")
                return out.append("MOD0 ausente.\n").toString()

            val dynamic =
                mod + b.getInt(mod.toInt() + 4).toLong()

            if (!valid(memory.size, dynamic, 16L))
                return out.append("Dynamic invalido.\n").toString()

            val tags = mutableMapOf<Long, Long>()
            var terminated = false

            for (i in 0 until 256) {
                val p = dynamic + i * 16L
                if (!valid(memory.size, p, 16L)) break
                val tag = b.getLong(p.toInt())
                if (tag == 0L) {
                    terminated = true
                    break
                }
                tags[tag] = b.getLong(p.toInt() + 8)
            }

            if (!terminated)
                return out.append(
                    "Dynamic sem terminador.\n"
                ).toString()

            val rela = tags[7L]
                ?: return out.append("DT_RELA ausente.\n").toString()
            val size = tags[8L]
                ?: return out.append("DT_RELASZ ausente.\n").toString()
            val entry = tags[9L] ?: 24L

            if (entry != 24L || size < 0L ||
                size % 24L != 0L ||
                !valid(memory.size, rela, size)) {
                return out.append(
                    "Tabela RELA invalida.\n"
                ).toString()
            }

            val count = size / 24L
            if (count > 500000L)
                return out.append(
                    "Limite de entradas excedido.\n"
                ).toString()

            var relative = 0
            var symbolic = 0
            var other = 0
            var invalid = 0

            out.append("Entradas RELA: $count\n")

            // Primeira passagem: validar sem escrever.
            for (i in 0 until count.toInt()) {
                val p = (rela + i * 24L).toInt()
                val target = b.getLong(p)
                val info = b.getLong(p + 8)
                val addend = b.getLong(p + 16)
                val type = info and 0xffffffffL
                val symbol = info ushr 32

                if (type == 1027L && symbol == 0L) {
                    if (!valid(memory.size, target, 8L) ||
                        addend < 0L ||
                        addend >= memory.size.toLong()) {
                        invalid++
                    } else {
                        relative++
                    }
                } else if (
                    type == 1025L ||
                    type == 1026L ||
                    type == 257L
                ) {
                    symbolic++
                } else {
                    other++
                }
            }

            out.append("RELATIVE validas: $relative\n")
            out.append("Dependentes de simbolos: $symbolic\n")
            out.append("Outros tipos: $other\n")
            out.append("Invalidas: $invalid\n")

            if (invalid != 0) {
                out.append(
                    "Aplicacao cancelada por seguranca.\n"
                )
                return out.toString()
            }

            // Segunda passagem: base virtual zero.
            // Apenas R_AARCH64_RELATIVE.
            var applied = 0
            for (i in 0 until count.toInt()) {
                val p = (rela + i * 24L).toInt()
                val target = b.getLong(p)
                val info = b.getLong(p + 8)

                if ((info and 0xffffffffL) == 1027L &&
                    (info ushr 32) == 0L) {
                    val addend = b.getLong(p + 16)
                    b.putLong(target.toInt(), addend)
                    applied++
                }
            }

            out.append(
                "RELOCACOES RELATIVE APLICADAS: $applied\n"
            )
            out.append(
                "Base virtual: 0; memoria temporaria.\n"
            )
            out.append(
                "Simbolos externos e execucao ARM64 pendentes.\n"
            )
        } catch (ex: Exception) {
            out.append("ERRO: ${ex.message}\n")
        }
        return out.toString()
    }
}
