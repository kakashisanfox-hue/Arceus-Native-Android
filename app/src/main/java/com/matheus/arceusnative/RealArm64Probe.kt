package com.matheus.arceusnative

/**
 * Interpretador diagnostico AArch64 v4.5.
 * Os registradores reais do processo ainda nao sao conhecidos.
 * Nunca executa instrucoes nativamente no Android.
 */
object RealArm64Probe {
    private const val MAX_STEPS = 32

    private fun hex(v: Long) =
        "0x" + java.lang.Long.toHexString(v)

    private fun signed(v: Long, bits: Int) =
        (v shl (64 - bits)) shr (64 - bits)

    data class Flags(
        val n: Boolean,
        val z: Boolean,
        val c: Boolean,
        val v: Boolean
    ) {
        override fun toString(): String =
            "N=${if (n) 1 else 0} " +
            "Z=${if (z) 1 else 0} " +
            "C=${if (c) 1 else 0} " +
            "V=${if (v) 1 else 0}"
    }

    private fun subFlags(a: Long, b: Long): Flags {
        val result = a - b
        return Flags(
            result < 0,
            result == 0L,
            java.lang.Long.compareUnsigned(a, b) >= 0,
            ((a xor b) and (a xor result)) < 0
        )
    }

    private fun condition(cond: Int, f: Flags): Boolean? =
        when (cond) {
            0 -> f.z
            1 -> !f.z
            2 -> f.c
            3 -> !f.c
            4 -> f.n
            5 -> !f.n
            6 -> f.v
            7 -> !f.v
            8 -> f.c && !f.z
            9 -> !f.c || f.z
            10 -> f.n == f.v
            11 -> f.n != f.v
            12 -> !f.z && f.n == f.v
            13 -> f.z || f.n != f.v
            14 -> true
            else -> null
        }

    private fun selfTest(): String {
        val out = StringBuilder(
            "\n===== FLAGS ARM64 v4.5 SELF-TEST =====\n"
        )

        val equal = subFlags(0, 0)
        val less = subFlags(0, 1)
        val greater = subFlags(5, 3)
        val overflow = subFlags(Long.MIN_VALUE, 1)

        val tests = listOf(
            "CMP igual" to
                (equal.z && equal.c && !equal.n && !equal.v),
            "CMP menor" to
                (less.n && !less.z && !less.c && !less.v),
            "CMP maior" to
                (!greater.n && !greater.z && greater.c),
            "Overflow assinado" to overflow.v,
            "B.EQ" to (condition(0, equal) == true),
            "B.NE" to (condition(1, less) == true),
            "B.LT" to (condition(11, less) == true),
            "B.GT" to (condition(12, greater) == true),
            "B.CS" to (condition(2, greater) == true)
        )

        for ((name, ok) in tests) {
            out.append("$name: ${if (ok) "OK" else "FALHA"}\n")
        }

        out.append(
            if (tests.all { it.second })
                "FLAGS v4.5 SELF-TEST: SUCESSO!\n"
            else
                "FLAGS v4.5 SELF-TEST: FALHOU\n"
        )

        return out.toString()
    }

    fun run(
        start: Long,
        fetch: (Long) -> Int
    ): String {
        val out = StringBuilder(
            "\n===== ARM64 CODIGO REAL v4.5 =====\n"
        )
        out.append(selfTest())

        var pc = start
        var steps = 0
        var branches = 0
        var reason = "Limite de instrucoes"

        // Registradores do processo ainda nao inicializados.
        val registers = arrayOfNulls<Long>(31)
        var flags: Flags? = null
        val visited = HashSet<Long>()

        try {
            while (steps < MAX_STEPS) {
                if (!visited.add(pc)) {
                    reason = "Loop detectado"
                    break
                }

                val here = pc
                val op = fetch(here)
                val u = op.toLong() and 0xffffffffL
                steps++

                val description: String

                when {
                    (u and 0xFC000000L) ==
                        0x14000000L -> {
                        val delta = signed(
                            (u and 0x3ffffffL) shl 2, 28
                        )
                        pc = here + delta
                        branches++
                        description = "B -> ${hex(pc)}"
                    }

                    (u and 0xFC000000L) ==
                        0x94000000L -> {
                        val delta = signed(
                            (u and 0x3ffffffL) shl 2, 28
                        )
                        registers[30] = here + 4L
                        pc = here + delta
                        branches++
                        description = "BL -> ${hex(pc)}"
                    }

                    // SUBS (imediato), 32 ou 64 bits.
                    (u and 0x7F000000L) ==
                        0x71000000L -> {
                        val sf = (u and 0x80000000L) != 0L
                        val rn = (op ushr 5) and 31
                        val rd = op and 31
                        val imm12 = (op ushr 10) and 4095
                        val shift = (op ushr 22) and 3

                        if (shift > 1) {
                            reason = "Shift SUBS nao suportado"
                            description = "SUBS shift invalido"
                            out.append(
                                "${hex(here)}: ${hex(u)} " +
                                "$description\n"
                            )
                            break
                        }

                        val imm = imm12.toLong() shl
                            (if (shift == 1) 12 else 0)

                        val value =
                            if (rn == 31) 0L else registers[rn]

                        if (value == null) {
                            description =
                                "CMP/SUBS: X$rn desconhecido"
                            reason =
                                "Registrador real nao inicializado"
                            out.append(
                                "${hex(here)}: ${hex(u)} " +
                                "$description\n"
                            )
                            break
                        }

                        val lhs =
                            if (sf) value else value and 0xffffffffL

                        val result = lhs - imm
                        flags = if (sf) {
                            subFlags(lhs, imm)
                        } else {
                            val a = lhs and 0xffffffffL
                            val b = imm and 0xffffffffL
                            val r = (a - b) and 0xffffffffL
                            Flags(
                                (r and 0x80000000L) != 0L,
                                r == 0L,
                                a >= b,
                                (((a xor b) and (a xor r)) and
                                    0x80000000L) != 0L
                            )
                        }

                        if (rd != 31) {
                            registers[rd] =
                                if (sf) result
                                else result and 0xffffffffL
                        }

                        pc = here + 4L
                        description = "SUBS ${flags}"
                    }

                    // B.cond
                    (u and 0xFF000010L) ==
                        0x54000000L -> {
                        val cond = op and 15
                        val f = flags
                        if (f == null) {
                            reason = "Flags ainda desconhecidas"
                            out.append(
                                "${hex(here)}: ${hex(u)} " +
                                "B.cond sem flags\n"
                            )
                            break
                        }

                        val take = condition(cond, f)
                        if (take == null) {
                            reason = "Condicao reservada"
                            out.append(
                                "${hex(here)}: ${hex(u)} " +
                                "Condicao nao suportada\n"
                            )
                            break
                        }

                        val delta = signed(
                            ((u ushr 5) and 0x7ffffL) shl 2,
                            21
                        )
                        pc = if (take) here + delta
                             else here + 4L
                        if (take) branches++
                        description =
                            "B.cond $cond " +
                            if (take) "tomado -> ${hex(pc)}"
                            else "nao tomado"
                    }

                    u == 0xD503201FL -> {
                        pc = here + 4L
                        description = "NOP"
                    }

                    (u and 0xFFFFFC1FL) ==
                        0xD65F0000L -> {
                        reason = "RET: estado de CPU incompleto"
                        out.append(
                            "${hex(here)}: ${hex(u)} RET\n"
                        )
                        break
                    }

                    (u and 0xFFE0001FL) ==
                        0xD4000001L -> {
                        reason = "SVC sem HLE"
                        out.append(
                            "${hex(here)}: ${hex(u)} SVC\n"
                        )
                        break
                    }

                    else -> {
                        reason = "Opcode nao implementado"
                        out.append(
                            "${hex(here)}: ${hex(u)} " +
                            "OPCODE NAO IMPLEMENTADO\n"
                        )
                        break
                    }
                }

                out.append(
                    "${hex(here)}: ${hex(u)} " +
                    "$description\n"
                )
            }
        } catch (e: Exception) {
            reason =
                "Erro controlado: " +
                (e.message ?: e.javaClass.simpleName)
        }

        out.append("Instrucoes analisadas: $steps\n")
        out.append("Desvios seguidos: $branches\n")
        out.append("Parada segura: $reason\n")
        out.append(
            "ARM64 v4.5: DIAGNOSTICO CONCLUIDO\n"
        )
        out.append(
            "Registradores desconhecidos nao sao inventados.\n"
        )
        out.append(
            "Pokemon ainda nao executado integralmente.\n"
        )

        return out.toString()
    }
}
