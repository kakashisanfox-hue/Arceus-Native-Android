package com.matheus.arceusnative

/**
 * Diagnostico de fluxo AArch64 sobre codigo real.
 * Nao chama codigo nativo, nao modifica memoria e nao
 * implementa syscalls ou acesso a dispositivos.
 */
object RealArm64Probe {
    private const val MAX_STEPS = 16

    private fun hex(v: Long): String =
        "0x" + java.lang.Long.toHexString(v)

    private fun signed(v: Long, bits: Int): Long =
        (v shl (64 - bits)) shr (64 - bits)

    fun run(
        start: Long,
        fetch: (Long) -> Int
    ): String {
        val out = StringBuilder(
            "\n===== ARM64 CODIGO REAL v4.4 =====\n"
        )

        var pc = start
        var steps = 0
        var branches = 0
        var reason = "Limite de instrucoes"

        val visited = HashSet<Long>()

        try {
            while (steps < MAX_STEPS) {
                if (!visited.add(pc)) {
                    reason = "Loop detectado"
                    break
                }

                val op = fetch(pc)
                val u = op.toLong() and 0xffffffffL
                val here = pc

                steps++

                val description: String

                when {
                    (u and 0xFC000000L) ==
                        0x14000000L -> {
                        val delta = signed(
                            (u and 0x3ffffffL) shl 2,
                            28
                        )
                        pc = here + delta
                        branches++
                        description = "B -> ${hex(pc)}"
                    }

                    (u and 0xFC000000L) ==
                        0x94000000L -> {
                        val delta = signed(
                            (u and 0x3ffffffL) shl 2,
                            28
                        )
                        pc = here + delta
                        branches++
                        description =
                            "BL -> ${hex(pc)} (LR simulado)"
                    }

                    u == 0xD503201FL -> {
                        pc = here + 4L
                        description = "NOP"
                    }

                    (u and 0xFFFFFC1FL) ==
                        0xD65F0000L -> {
                        description = "RET"
                        reason =
                            "RET exige estado de registradores"
                        out.append(
                            "${hex(here)}: ${hex(u)} " +
                            "$description\n"
                        )
                        break
                    }

                    (u and 0xFFE0001FL) ==
                        0xD4000001L -> {
                        description = "SVC"
                        reason = "Syscall nao implementada"
                        out.append(
                            "${hex(here)}: ${hex(u)} " +
                            "$description\n"
                        )
                        break
                    }

                    else -> {
                        description = "OPCODE NAO IMPLEMENTADO"
                        reason =
                            "Decodificador precisa ser ampliado"
                        out.append(
                            "${hex(here)}: ${hex(u)} " +
                            "$description\n"
                        )
                        break
                    }
                }

                out.append(
                    "${hex(here)}: ${hex(u)} " +
                    "$description\n"
                )
            }

            out.append("Instrucoes analisadas: $steps\n")
            out.append("Desvios seguidos: $branches\n")
            out.append("Parada segura: $reason\n")
            out.append(
                "ARM64 v4.4: DIAGNOSTICO CONCLUIDO\n"
            )
            out.append(
                "Fluxo de PC interpretado; " +
                "sem execucao nativa do jogo.\n"
            )
        } catch (e: Exception) {
            out.append(
                "PARADA SEGURA: " +
                "${e.message ?: e.javaClass.simpleName}\n"
            )
            out.append(
                "Instrucoes analisadas: $steps\n"
            )
        }

        return out.toString()
    }
}
