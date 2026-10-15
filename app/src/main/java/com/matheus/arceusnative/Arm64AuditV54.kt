package com.matheus.arceusnative

/**
 * Auditoria ARM64 v5.4.
 * Nao executa codigo do jogo.
 */
object Arm64AuditV54 {
    private fun hex(v: Long) =
        "0x" + java.lang.Long.toHexString(v)

    private fun signed(v: Long, bits: Int) =
        (v shl (64 - bits)) shr (64 - bits)

    private fun unsigned(v: Int) =
        v.toLong() and 0xffffffffL

    private fun category(op: Int): String {
        val v = unsigned(op)
        return when {
            (v and 0xfc000000L) == 0x14000000L ->
                "DESVIO_B"
            (v and 0xfc000000L) == 0x94000000L ->
                "CHAMADA_BL"
            (v and 0xff000010L) == 0x54000000L ->
                "DESVIO_CONDICIONAL"
            (v and 0x1f000000L) == 0x11000000L ->
                "ARITMETICA_IMEDIATA"
            (v and 0x1f000000L) == 0x0a000000L ->
                "LOGICA_REGISTRADORES"
            (v and 0x7f800000L) == 0x52800000L ->
                "MOVZ"
            (v and 0x7f800000L) == 0x72800000L ->
                "MOVK"
            (v and 0x7f800000L) == 0x12800000L ->
                "MOVN"
            (v and 0xffc00000L) == 0xf9000000L ->
                "STR"
            (v and 0xffc00000L) == 0xf9400000L ->
                "LDR"
            (v and 0xfffffc1fL) == 0xd65f0000L ->
                "RET"
            (v and 0xffe0001fL) == 0xd4000001L ->
                "SVC"
            v == 0xd503201fL -> "NOP"
            else -> "NAO_CLASSIFICADA"
        }
    }

    fun selfTest(): String {
        val cases = linkedMapOf(
            "B" to Pair(0x14000002, "DESVIO_B"),
            "BL" to Pair(
                0x94000002.toInt(), "CHAMADA_BL"
            ),
            "CMP" to Pair(
                0xf100001f.toInt(),
                "ARITMETICA_IMEDIATA"
            ),
            "B.NE" to Pair(
                0x54000061, "DESVIO_CONDICIONAL"
            ),
            "ORR" to Pair(
                0x2a0103f3, "LOGICA_REGISTRADORES"
            ),
            "LDR" to Pair(
                0xf94003e0.toInt(), "LDR"
            ),
            "STR" to Pair(
                0xf90003e0.toInt(), "STR"
            ),
            "SVC" to Pair(
                0xd4000001.toInt(), "SVC"
            ),
            "Desconhecida" to Pair(
                -1, "NAO_CLASSIFICADA"
            )
        )

        val out = StringBuilder(
            "\n===== ARM64 AUDITORIA v5.4 SELF-TEST =====\n"
        )

        var passed = 0
        for ((name, test) in cases) {
            val ok = category(test.first) == test.second
            if (ok) passed++
            out.append(
                "$name: ${if (ok) "OK" else "FALHA"}\n"
            )
        }

        out.append("Testes: $passed/${cases.size}\n")
        out.append(
            if (passed == cases.size)
                "AUDITORIA v5.4 SELF-TEST: SUCESSO!\n"
            else
                "AUDITORIA v5.4 SELF-TEST: FALHOU\n"
        )
        return out.toString()
    }

    fun inspect(
        entry: Long,
        fetch: (Long) -> Int
    ): String {
        val out = StringBuilder(
            "\n===== ARM64 AUDITORIA v5.4 RTLD =====\n"
        )

        val counts = linkedMapOf<String, Int>()
        var pc = entry
        var inspected = 0
        var reason = "Limite de inspecao"

        val visited = HashSet<Long>()

        while (inspected < 32) {
            if (!visited.add(pc)) {
                reason = "Endereco repetido"
                break
            }

            val op = try {
                fetch(pc)
            } catch (e: Exception) {
                reason = "Leitura negada: ${e.message}"
                break
            }

            val value = unsigned(op)
            val kind = category(op)
            counts[kind] = (counts[kind] ?: 0) + 1
            inspected++

            out.append(
                "${hex(pc)} ${hex(value)} $kind\n"
            )

            if (kind == "DESVIO_B") {
                val delta = signed(
                    (value and 0x3ffffffL) shl 2,
                    28
                )
                pc += delta
                continue
            }

            if (kind == "NAO_CLASSIFICADA") {
                reason = "Instrucao nao classificada"
                break
            }

            // Sem contexto real, nao decidir condicoes.
            if (kind == "DESVIO_CONDICIONAL") {
                reason = "Condicao depende de NZCV real"
                break
            }

            if (kind == "SVC") {
                reason = "Servico HLE necessario"
                break
            }

            // Apos uma instrucao que depende de estado,
            // a auditoria nao presume resultados.
            if (kind != "NOP") {
                reason = "Estado real nao inicializado"
                break
            }

            pc += 4L
        }

        out.append("Instrucoes inspecionadas: $inspected\n")
        out.append("Parada: $reason\n")

        for ((name, count) in counts) {
            out.append("$name: $count\n")
        }

        out.append(
            "CPU ARM64: NAO CONCLUIDA\n"
        )
        out.append(
            "Pendencias: contexto de entrada, " +
            "memoria real integrada, instrucoes adicionais, " +
            "NEON, atomicas e excecoes.\n"
        )
        out.append(
            "Proximo modulo planejado: Kernel/HLE; " +
            "CPU permanece em desenvolvimento.\n"
        )
        out.append(
            "Nenhuma instrucao do jogo foi executada " +
            "por esta auditoria.\n"
        )

        return out.toString()
    }
}
