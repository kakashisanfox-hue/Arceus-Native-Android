package com.matheus.arceusnative

/**
 * ARM64 Runtime v6.1
 * Excecoes basicas e analise do fluxo real do RTLD.
 * Nao inventa registradores de entrada.
 */
object Arm64RuntimeV61 {

    private fun hex(v: Long): String =
        "0x" + java.lang.Long.toHexString(v)

    private fun u(v: Int): Long =
        v.toLong() and 0xffffffffL

    private fun sx(v: Long, bits: Int): Long =
        (v shl (64 - bits)) shr (64 - bits)

    enum class Cause {
        SVC,
        BREAKPOINT,
        HALT,
        UNDEFINED,
        FETCH_FAULT,
        PC_ALIGNMENT,
        UNKNOWN_STATE,
        BUDGET
    }

    data class Trap(
        val cause: Cause,
        val pc: Long,
        val opcode: Long?,
        val detail: String
    )

    class State {
        val x = arrayOfNulls<Long>(31)
        var sp: Long? = null
        var pc: Long = 0L
        var n: Boolean? = null
        var z: Boolean? = null
        var c: Boolean? = null
        var v: Boolean? = null

        fun read(reg: Int): Long? =
            if (reg == 31) 0L else x[reg]

        fun write(reg: Int, value: Long) {
            if (reg != 31) x[reg] = value
        }
    }

    fun classify(
        opcode: Int,
        pc: Long
    ): Trap? {
        val v = u(opcode)
        val imm = (opcode ushr 5) and 65535

        return when {
            (v and 0xffe0001fL) == 0xd4000001L ->
                Trap(Cause.SVC, pc, v, "SVC #$imm")

            (v and 0xffe0001fL) == 0xd4200000L ->
                Trap(
                    Cause.BREAKPOINT,
                    pc, v, "BRK #$imm"
                )

            (v and 0xffe0001fL) == 0xd4400000L ->
                Trap(Cause.HALT, pc, v, "HLT #$imm")

            v == 0xffffffffL ->
                Trap(
                    Cause.UNDEFINED,
                    pc, v, "Instrucao indefinida"
                )

            else -> null
        }
    }

    fun trace(
        start: Long,
        fetch: (Long) -> Int,
        budget: Int = 32
    ): String {
        val out = StringBuilder(
            "\n===== ARM64 RUNTIME v6.1 RTLD REAL =====\n"
        )

        val state = State()
        state.pc = start
        var count = 0
        var trap: Trap? = null
        val visited = HashSet<Long>()

        while (count < budget) {
            val pc = state.pc

            if (pc % 4L != 0L) {
                trap = Trap(
                    Cause.PC_ALIGNMENT, pc, null,
                    "PC desalinhado"
                )
                break
            }

            if (!visited.add(pc)) {
                trap = Trap(
                    Cause.BUDGET, pc, null,
                    "Endereco repetido"
                )
                break
            }

            val op = try {
                fetch(pc)
            } catch (e: Exception) {
                trap = Trap(
                    Cause.FETCH_FAULT, pc, null,
                    e.message ?: "Falha de leitura"
                )
                break
            }

            val v = u(op)
            count++
            out.append("${hex(pc)} ${hex(v)} ")

            val special = classify(op, pc)
            if (special != null) {
                trap = special
                out.append("${special.detail}\n")
                break
            }

            when {
                (v and 0xfc000000L) ==
                    0x14000000L -> {
                    val delta = sx(
                        (v and 0x3ffffffL) shl 2,
                        28
                    )
                    state.pc = pc + delta
                    out.append(
                        "B -> ${hex(state.pc)}\n"
                    )
                }

                (v and 0xfc000000L) ==
                    0x94000000L -> {
                    val delta = sx(
                        (v and 0x3ffffffL) shl 2,
                        28
                    )
                    state.write(30, pc + 4)
                    state.pc = pc + delta
                    out.append(
                        "BL -> ${hex(state.pc)}\n"
                    )
                }

                (v and 0x1f000000L) ==
                    0x11000000L -> {
                    val rn = (op ushr 5) and 31
                    val rd = op and 31
                    val lhs =
                        if (rn == 31) state.sp
                        else state.read(rn)

                    if (lhs == null) {
                        out.append(
                            "ADD/SUB: origem desconhecida\n"
                        )
                        trap = Trap(
                            Cause.UNKNOWN_STATE,
                            pc, v,
                            "X$rn/SP nao inicializado"
                        )
                        break
                    }

                    val sub =
                        (v and 0x40000000L) != 0L
                    val flags =
                        (v and 0x20000000L) != 0L
                    val shift =
                        (op ushr 22) and 3

                    if (shift > 1) {
                        trap = Trap(
                            Cause.UNDEFINED, pc, v,
                            "Shift reservado"
                        )
                        break
                    }

                    val imm =
                        ((op ushr 10) and 4095)
                            .toLong() shl
                        (if (shift == 1) 12 else 0)

                    val wide =
                        (v and 0x80000000L) != 0L
                    val mask =
                        if (wide) -1L
                        else 0xffffffffL
                    val a = lhs and mask
                    val b = imm and mask
                    val result =
                        ((if (sub) a - b else a + b) and mask)

                    if (flags) {
                        val sign =
                            if (wide) Long.MIN_VALUE
                            else 0x80000000L
                        state.z = result == 0L
                        state.n =
                            (result and sign) != 0L
                        state.c =
                            if (sub)
                                java.lang.Long
                                    .compareUnsigned(a,b) >= 0
                            else
                                java.lang.Long
                                    .compareUnsigned(result,a) < 0
                        state.v =
                            if (sub)
                                ((a xor b) and
                                 (a xor result) and
                                 sign) != 0L
                            else
                                ((a xor result) and
                                 (b xor result) and
                                 sign) != 0L
                    }

                    if (rd == 31) {
                        if (!flags) state.sp = result
                    } else {
                        state.write(rd, result)
                    }

                    state.pc = pc + 4
                    out.append("ADD/SUB executada\n")
                }

                (v and 0xff000010L) ==
                    0x54000000L -> {
                    val cond = op and 15
                    val z = state.z
                    val take = when (cond) {
                        0 -> z
                        1 -> z?.not()
                        else -> null
                    }

                    if (take == null) {
                        out.append(
                            "B.cond: NZCV desconhecido\n"
                        )
                        trap = Trap(
                            Cause.UNKNOWN_STATE,
                            pc, v,
                            "Condicao sem flags validas"
                        )
                        break
                    }

                    val delta = sx(
                        ((v ushr 5) and
                         0x7ffffL) shl 2, 21
                    )
                    state.pc =
                        if (take) pc + delta
                        else pc + 4
                    out.append(
                        "B.cond -> ${hex(state.pc)}\n"
                    )
                }

                v == 0xd503201fL -> {
                    state.pc = pc + 4
                    out.append("NOP\n")
                }

                else -> {
                    out.append(
                        "Opcode nao implementado\n"
                    )
                    trap = Trap(
                        Cause.UNDEFINED, pc, v,
                        "Instrucao nao implementada"
                    )
                    break
                }
            }
        }

        if (trap == null) {
            trap = Trap(
                Cause.BUDGET, state.pc, null,
                "Limite de instrucoes"
            )
        }

        out.append("Instrucoes analisadas: $count\n")
        out.append("Parada: ${trap.cause}\n")
        out.append("Motivo: ${trap.detail}\n")
        out.append(
            "Contexto de entrada real: INCOMPLETO\n"
        )
        out.append(
            "Execucao do processo: NAO INICIADA\n"
        )
        return out.toString()
    }

    fun selfTest(): String {
        val out = StringBuilder(
            "\n===== ARM64 EXCECOES v6.1 SELF-TEST =====\n"
        )

        val pc = 0x1000L
        val tests = linkedMapOf(
            "SVC" to
                (classify(
                    0xd4000001.toInt(), pc
                )?.cause == Cause.SVC),

            "BRK" to
                (classify(
                    0xd4200000.toInt(), pc
                )?.cause == Cause.BREAKPOINT),

            "HLT" to
                (classify(
                    0xd4400000.toInt(), pc
                )?.cause == Cause.HALT),

            "Indefinida" to
                (classify(
                    -1, pc
                )?.cause == Cause.UNDEFINED),

            "NOP sem excecao" to
                (classify(
                    0xd503201f.toInt(), pc
                ) == null)
        )

        for ((name, ok) in tests) {
            out.append(
                "$name: ${if (ok) "OK" else "FALHA"}\n"
            )
        }

        out.append(
            "Resultado: ${tests.values.count { it }}" +
            "/${tests.size}\n"
        )

        out.append(
            if (tests.values.all { it })
                "EXCECOES v6.1 SELF-TEST: SUCESSO!\n"
            else
                "EXCECOES v6.1 SELF-TEST: FALHOU\n"
        )

        out.append(
            "Tratamento arquitetural completo: PENDENTE\n"
        )
        return out.toString()
    }
}
