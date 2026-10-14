package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Mod0Inspector {
    private fun magic(a: ByteArray, p: Int): Boolean =
        p >= 0 && p <= a.size - 4 &&
        a[p] == 77.toByte() &&
        a[p + 1] == 79.toByte() &&
        a[p + 2] == 68.toByte() &&
        a[p + 3] == 48.toByte()

    fun inspect(memory: ByteArray, module: String): String {
        val out = StringBuilder(
            "\n===== MOD0 v3.1: $module =====\n"
        )
        if (memory.size < 32)
            return out.append("Imagem pequena.\n").toString()

        val b = ByteBuffer.wrap(memory)
            .order(ByteOrder.LITTLE_ENDIAN)

        val pointer = 4L + b.getInt(4).toLong()
        var pos = if (
            pointer >= 0 &&
            pointer <= memory.size.toLong() - 28 &&
            magic(memory, pointer.toInt())
        ) pointer.toInt() else -1

        if (pos < 0) {
            val limit = minOf(memory.size - 28, 1048576)
            var i = 0
            while (i <= limit) {
                if (magic(memory, i)) {
                    pos = i
                    break
                }
                i += 4
            }
        }

        if (pos < 0)
            return out.append(
                "MOD0 nao localizado. Ponteiro=$pointer\n"
            ).toString()

        out.append(
            "MOD0 localizado: 0x${pos.toString(16)}\n"
        )

        val names = arrayOf(
            "dynamic",
            "bss_start",
            "bss_end",
            "eh_frame_hdr_start",
            "eh_frame_hdr_end",
            "module_object"
        )
        val values = LongArray(6)

        for (i in names.indices) {
            val rel = b.getInt(pos + 4 + i * 4).toLong()
            val address = pos.toLong() + rel
            values[i] = address
            val valid = address >= 0 &&
                address <= memory.size.toLong()
            out.append(
                "${names[i]}: 0x${address.toString(16)} " +
                if (valid) "OK\n" else "FORA DA IMAGEM\n"
            )
        }

        if (
            values[1] >= 0 &&
            values[2] >= values[1] &&
            values[2] <= memory.size.toLong()
        ) {
            out.append(
                "BSS MOD0: ${values[2] - values[1]} bytes\n"
            )
        }

        val dyn = values[0]
        if (dyn < 0 || dyn + 16 > memory.size.toLong())
            return out.append(
                "Tabela dinamica fora da imagem.\n"
            ).toString()

        val tags = mapOf(
            1L to "NEEDED",
            2L to "PLTRELSZ",
            4L to "HASH",
            5L to "STRTAB",
            6L to "SYMTAB",
            7L to "RELA",
            8L to "RELASZ",
            9L to "RELAENT",
            10L to "STRSZ",
            11L to "SYMENT",
            17L to "REL",
            18L to "RELSZ",
            19L to "RELENT",
            20L to "PLTREL",
            23L to "JMPREL",
            0x6ffffff9L to "RELACOUNT"
        )

        var count = 0
        var terminated = false

        while (
            count < 256 &&
            dyn + count * 16L + 16 <= memory.size.toLong()
        ) {
            val at = dyn.toInt() + count * 16
            val tag = b.getLong(at)
            val value = b.getLong(at + 8)

            if (tag == 0L) {
                terminated = true
                break
            }

            val name = tags[tag]
            if (name != null) {
                out.append(
                    "DT_$name = 0x${value.toString(16)}\n"
                )
            }
            count++
        }

        out.append("Entradas dinamicas: $count\n")
        out.append(
            "Terminador: " +
            if (terminated) "OK\n" else "nao encontrado\n"
        )
        out.append(
            "Relocacoes identificadas, nao aplicadas.\n"
        )
        out.append(
            "Execucao ARM64 nao iniciada.\n"
        )
        return out.toString()
    }
}
