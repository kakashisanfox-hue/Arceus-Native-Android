package com.matheus.arceusnative

/**
 * Decodificador AArch64 v5.2.
 * Identifica instrucoes; nao altera registradores.
 */
object Arm64DecoderV52 {
    private fun hex(n: Long): String =
        "0x" + java.lang.Long.toHexString(n)

    private fun sx(v: Long, bits: Int): Long =
        (v shl (64 - bits)) shr (64 - bits)

    fun decode(word: Int, pc: Long): String {
        val op = word.toLong() and 0xffffffffL
        val rd = word and 31
        val rn = (word ushr 5) and 31

        return when {
            op == 0xd503201fL -> "NOP"

            (op and 0x7f800000L) ==
                0x52800000L -> {
                val sf = (op and 0x80000000L) != 0L
                val imm = (word ushr 5) and 65535
                val shift = ((word ushr 21) and 3) * 16
                if (!sf && shift >= 32)
                    "MOVZ codificacao invalida"
                else
                    "MOVZ ${if (sf) "X" else "W"}$rd, " +
                    "#$imm, LSL #$shift"
            }

            (op and 0x7f800000L) ==
                0x72800000L -> "MOVK"

            (op and 0x7f800000L) ==
                0x12800000L -> "MOVN"

            (op and 0x1f000000L) ==
                0x11000000L -> {
                val sub = (op and 0x40000000L) != 0L
                val flags = (op and 0x20000000L) != 0L
                val imm = (word ushr 10) and 4095
                val shift = (word ushr 22) and 3
                if (shift > 1)
                    "ADD/SUB codificacao invalida"
                else {
                    val name = when {
                        sub && flags && rd == 31 -> "CMP"
                        !sub && flags && rd == 31 -> "CMN"
                        sub && flags -> "SUBS"
                        sub -> "SUB"
                        flags -> "ADDS"
                        else -> "ADD"
                    }
                    "$name Rd=$rd Rn=$rn " +
                    "imm=${imm.toLong() shl (shift * 12)}"
                }
            }

            (op and 0x1f000000L) ==
                0x0a000000L -> {
                val opc = (word ushr 29) and 3
                val name = when (opc) {
                    0 -> "AND"
                    1 -> "ORR"
                    2 -> "EOR"
                    else -> "ANDS"
                }
                "$name (registradores)"
            }

            (op and 0xfc000000L) ==
                0x14000000L -> {
                val delta = sx(
                    (op and 0x3ffffffL) shl 2, 28
                )
                "B -> ${hex(pc + delta)}"
            }

            (op and 0xfc000000L) ==
                0x94000000L -> {
                val delta = sx(
                    (op and 0x3ffffffL) shl 2, 28
                )
                "BL -> ${hex(pc + delta)}"
            }

            (op and 0xff000010L) ==
                0x54000000L -> {
                val delta = sx(
                    ((op ushr 5) and 0x7ffffL) shl 2,
                    21
                )
                "B.cond cond=${word and 15} " +
                "alvo=${hex(pc + delta)}"
            }

            (op and 0x7e000000L) ==
                0x34000000L -> {
                val delta = sx(
                    ((op ushr 5) and 0x7ffffL) shl 2,
                    21
                )
                val name =
                    if ((op and 0x01000000L) != 0L)
                        "CBNZ" else "CBZ"
                "$name alvo=${hex(pc + delta)}"
            }

            (op and 0xffc00000L) ==
                0xf9000000L -> "STR X$rd, [base]"

            (op and 0xffc00000L) ==
                0xf9400000L -> "LDR X$rd, [base]"

            (op and 0xfffffc1fL) ==
                0xd65f0000L -> "RET"

            (op and 0xfffffc1fL) ==
                0xd61f0000L -> "BR"

            (op and 0xfffffc1fL) ==
                0xd63f0000L -> "BLR"

            (op and 0xffe0001fL) ==
                0xd4000001L -> "SVC"

            else -> "DESCONHECIDA"
        }
    }

    fun selfTest(): String {
        val out = StringBuilder(
            "\n===== ARM64 DECODER v5.2 SELF-TEST =====\n"
        )

        val pc = 0x1000L

        val tests = linkedMapOf(
            "MOVZ" to
                decode(0xd28000a0.toInt(), pc)
                    .startsWith("MOVZ"),
            "MOVK" to
                (decode(0xf2800020.toInt(), pc)
                    == "MOVK"),
            "MOVN" to
                (decode(0x92800000.toInt(), pc)
                    == "MOVN"),
            "ADD" to
                decode(0x91000400.toInt(), pc)
                    .startsWith("ADD"),
            "CMP" to
                decode(0xf100001f.toInt(), pc)
                    .startsWith("CMP"),
            "AND" to
                decode(0x8a010000.toInt(), pc)
                    .startsWith("AND"),
            "ORR" to
                decode(0xaa010000.toInt(), pc)
                    .startsWith("ORR"),
            "EOR" to
                decode(0xca010000.toInt(), pc)
                    .startsWith("EOR"),
            "B" to
                (decode(0x14000002, pc)
                    == "B -> 0x1008"),
            "B.cond" to
                decode(0x54000040, pc)
                    .startsWith("B.cond"),
            "LDR" to
                decode(0xf94003e0.toInt(), pc)
                    .startsWith("LDR"),
            "STR" to
                decode(0xf90003e0.toInt(), pc)
                    .startsWith("STR"),
            "RET" to
                (decode(0xd65f03c0.toInt(), pc)
                    == "RET"),
            "SVC" to
                (decode(0xd4000001.toInt(), pc)
                    == "SVC"),
            "Opcode desconhecido" to
                (decode(0xffffffff.toInt(), pc)
                    == "DESCONHECIDA")
        )

        for ((name, ok) in tests) {
            out.append(
                "$name: ${if (ok) "OK" else "FALHA"}\n"
            )
        }

        out.append(
            if (tests.values.all { it })
                "DECODER v5.2 SELF-TEST: SUCESSO!\n"
            else
                "DECODER v5.2 SELF-TEST: FALHOU\n"
        )

        out.append(
            "Decodificacao apenas; " +
            "execucao nao ampliada nesta etapa.\n"
        )
        return out.toString()
    }

    fun inspect(
        start: Long,
        fetch: (Long) -> Int
    ): String {
        val out = StringBuilder(
            "\n===== ARM64 DECODER v5.2 CODIGO REAL =====\n"
        )

        var pc = start
        try {
            repeat(8) {
                val op = fetch(pc)
                val u = op.toLong() and 0xffffffffL
                out.append(
                    "${hex(pc)} ${hex(u)} " +
                    "${decode(op, pc)}\n"
                )
                pc += 4L
            }
        } catch (e: Exception) {
            out.append(
                "Leitura interrompida: ${e.message}\n"
            )
        }

        out.append(
            "Inspecao sequencial; " +
            "nao representa fluxo executado.\n"
        )
        return out.toString()
    }
}
