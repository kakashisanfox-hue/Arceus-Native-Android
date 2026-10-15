package com.matheus.arceusnative

/**
 * Contexto AArch64 experimental.
 * null significa valor desconhecido, nunca zero implicito.
 */
object Arm64ContextV51 {

    data class Flags(
        var n: Boolean? = null,
        var z: Boolean? = null,
        var c: Boolean? = null,
        var v: Boolean? = null
    )

    class Context {
        val x = arrayOfNulls<Long>(31)
        var sp: Long? = null
        var pc: Long? = null
        var flags = Flags()

        fun readX(index: Int): Long? {
            require(index in 0..31)
            return if (index == 31) 0L else x[index]
        }

        fun writeX(index: Int, value: Long) {
            require(index in 0..31)
            if (index != 31) x[index] = value
        }

        fun validStack(): Boolean =
            sp != null && sp!! >= 0 &&
            sp!! % 16L == 0L

        fun knownRegisters(): Int =
            x.count { it != null }

        fun copy(): Context {
            val result = Context()
            for (i in 0..30) result.x[i] = x[i]
            result.sp = sp
            result.pc = pc
            result.flags = flags.copy()
            return result
        }
    }

    private fun hex(v: Long): String =
        "0x" + java.lang.Long.toHexString(v)

    fun selfTest(): String {
        val out = StringBuilder(
            "\n===== ARM64 CONTEXTO v5.1 SELF-TEST =====\n"
        )

        val c = Context()

        val unknown =
            c.readX(0) == null &&
            c.sp == null &&
            c.pc == null &&
            c.knownRegisters() == 0

        c.writeX(0, 42L)
        c.writeX(30, 0x12345678L)
        c.writeX(31, 99L)

        val registers =
            c.readX(0) == 42L &&
            c.readX(30) == 0x12345678L &&
            c.readX(31) == 0L &&
            c.knownRegisters() == 2

        c.sp = 0x21000L
        c.pc = 0x10000L

        val stack =
            c.validStack() &&
            c.pc == 0x10000L

        c.sp = 0x21008L
        val alignment = !c.validStack()
        c.sp = 0x21000L

        c.flags.z = true
        val copy = c.copy()
        copy.writeX(0, 77L)
        copy.flags.z = false

        val isolation =
            c.readX(0) == 42L &&
            copy.readX(0) == 77L &&
            c.flags.z == true &&
            copy.flags.z == false

        val tests = linkedMapOf(
            "Registradores desconhecidos" to unknown,
            "X0-X30 e XZR" to registers,
            "PC e pilha sintetica" to stack,
            "Alinhamento SP de 16 bytes" to alignment,
            "Copia isolada do contexto" to isolation
        )

        for ((name, ok) in tests) {
            out.append(
                "$name: ${if (ok) "OK" else "FALHA"}\n"
            )
        }

        out.append(
            if (tests.values.all { it })
                "CONTEXTO v5.1 SELF-TEST: SUCESSO!\n"
            else
                "CONTEXTO v5.1 SELF-TEST: FALHOU\n"
        )

        out.append(
            "Contexto sintetico validado; " +
            "nao e o contexto real do Switch.\n"
        )

        return out.toString()
    }

    fun inspect(
        entry: Long,
        fetch: (Long) -> Int
    ): String {
        val out = StringBuilder(
            "\n===== ARM64 CONTEXTO v5.1 CODIGO REAL =====\n"
        )

        val c = Context()
        c.pc = entry

        out.append("PC candidato: ${hex(entry)}\n")
        out.append(
            "Registradores conhecidos: " +
            "${c.knownRegisters()}/31\n"
        )
        out.append("X0: DESCONHECIDO\n")
        out.append("SP: DESCONHECIDO\n")
        out.append("NZCV: DESCONHECIDO\n")

        try {
            val first = fetch(entry)
            val value = first.toLong() and 0xffffffffL

            out.append(
                "Primeiro opcode: ${hex(value)}\n"
            )

            if ((value and 0xfc000000L) ==
                0x14000000L) {

                val imm = value and 0x3ffffffL
                val delta = (imm shl 38) shr 36
                val target = entry + delta

                out.append(
                    "Primeiro desvio B: ${hex(target)}\n"
                )

                val next = fetch(target)
                val nextValue =
                    next.toLong() and 0xffffffffL

                out.append(
                    "Opcode no destino: ${hex(nextValue)}\n"
                )

                if ((nextValue and 0xff000000L) ==
                    0xf1000000L) {
                    val rn = (next ushr 5) and 31
                    out.append(
                        "SUBS/CMP usa X$rn ou SP.\n"
                    )
                    out.append(
                        "Valor inicial nao confirmado: " +
                        "execucao suspensa.\n"
                    )
                }
            }

            out.append(
                "CONTEXTO REAL: AGUARDANDO " +
                "CONTRATO DE ENTRADA\n"
            )
        } catch (e: Exception) {
            out.append(
                "PARADA SEGURA: ${e.message}\n"
            )
        }

        return out.toString()
    }
}
