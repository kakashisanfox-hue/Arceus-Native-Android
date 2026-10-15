package com.matheus.arceusnative

/**
 * Integracao experimental CPU/memoria.
 * Todos os testes executam codigo sintetico.
 */
object Arm64IntegratedV53 {

    private fun hx(v: Long) =
        "0x" + java.lang.Long.toHexString(v)

    private fun sx(v: Long, bits: Int) =
        (v shl (64 - bits)) shr (64 - bits)

    class Memory {
        data class Region(
            val start: Long,
            val bytes: ByteArray,
            val permissions: String
        )

        private val regions = ArrayList<Region>()

        fun map(
            start: Long,
            size: Int,
            permissions: String
        ) {
            require(start >= 0 && size > 0)
            require(permissions.all { it in "RWX" })
            require(start <= Long.MAX_VALUE - size.toLong())

            val end = start + size.toLong()

            require(regions.none {
                val otherEnd =
                    it.start + it.bytes.size.toLong()
                start < otherEnd && it.start < end
            }) { "Regioes sobrepostas" }

            regions.add(
                Region(start, ByteArray(size), permissions)
            )
        }

        private fun locate(
            address: Long,
            length: Int,
            permission: Char
        ): Pair<Region, Int> {
            require(length > 0)
            val region = regions.firstOrNull {
                address >= it.start &&
                address - it.start <=
                    it.bytes.size.toLong() - length
            } ?: error("Endereco nao mapeado")

            require(permission in region.permissions) {
                "Permissao $permission negada"
            }

            return Pair(
                region,
                (address - region.start).toInt()
            )
        }

        fun read(
            address: Long,
            length: Int,
            permission: Char = 'R'
        ): Long {
            require(length in 1..8)
            val (r, offset) =
                locate(address, length, permission)

            var result = 0L
            for (i in 0 until length) {
                result = result or
                    ((r.bytes[offset + i].toLong()
                        and 255L) shl (i * 8))
            }
            return result
        }

        fun write(
            address: Long,
            length: Int,
            value: Long
        ) {
            require(length in 1..8)
            val (r, offset) =
                locate(address, length, 'W')

            for (i in 0 until length) {
                r.bytes[offset + i] =
                    (value ushr (i * 8)).toByte()
            }
        }

        fun fetch(address: Long): Int {
            require(address % 4L == 0L)
            return read(address, 4, 'X').toInt()
        }
    }

    class Cpu(private val memory: Memory) {
        val x = LongArray(31)
        var sp = 0L
        var pc = 0L
        var zero = false
        var steps = 0
        var stop = "Limite"
        var branches = 0

        private fun reg(n: Int): Long =
            if (n == 31) 0L else x[n]

        private fun base(n: Int): Long =
            if (n == 31) sp else x[n]

        private fun put(
            n: Int,
            value: Long,
            width: Int = 64,
            allowSp: Boolean = false
        ) {
            val result =
                if (width == 32)
                    value and 0xffffffffL
                else value

            if (n == 31) {
                if (allowSp) sp = result
            } else {
                x[n] = result
            }
        }

        fun run(start: Long, limit: Int = 64) {
            pc = start
            steps = 0
            branches = 0
            stop = "Limite"

            while (steps < limit) {
                val here = pc
                val op = try {
                    memory.fetch(here)
                } catch (e: Exception) {
                    stop = "Fetch: ${e.message}"
                    break
                }

                val v = op.toLong() and 0xffffffffL
                val rd = op and 31
                val rn = (op ushr 5) and 31

                steps++
                pc = here + 4L

                when {
                    v == 0xd503201fL -> Unit

                    (v and 0x7f800000L) ==
                        0x52800000L -> {
                        val width =
                            if ((v and 0x80000000L) != 0L)
                                64 else 32
                        val shift =
                            ((op ushr 21) and 3) * 16
                        if (shift >= width) {
                            stop = "MOVZ invalido"
                            break
                        }
                        val imm =
                            ((op ushr 5) and 65535)
                                .toLong()
                        put(rd, imm shl shift, width)
                    }

                    (v and 0x1f000000L) ==
                        0x11000000L -> {
                        val width =
                            if ((v and 0x80000000L) != 0L)
                                64 else 32
                        val sub =
                            (v and 0x40000000L) != 0L
                        val flags =
                            (v and 0x20000000L) != 0L
                        val shift =
                            (op ushr 22) and 3
                        if (shift > 1) {
                            stop = "Shift invalido"
                            break
                        }
                        val imm =
                            ((op ushr 10) and 4095)
                                .toLong() shl
                            (if (shift == 1) 12 else 0)

                        val lhs =
                            if (flags) reg(rn)
                            else base(rn)
                        val mask =
                            if (width == 32)
                                0xffffffffL else -1L
                        val result =
                            (if (sub) lhs - imm
                             else lhs + imm) and mask

                        if (flags) zero = result == 0L
                        put(rd, result, width, !flags)
                    }

                    (v and 0xffc00000L) ==
                        0xf9000000L ||
                    (v and 0xffc00000L) ==
                        0xf9400000L -> {
                        val addr =
                            base(rn) +
                            ((op ushr 10) and 4095) * 8L
                        try {
                            if ((v and 0x00400000L) != 0L) {
                                put(
                                    rd,
                                    memory.read(addr, 8)
                                )
                            } else {
                                memory.write(
                                    addr, 8, reg(rd)
                                )
                            }
                        } catch (e: Exception) {
                            stop = "Dados: ${e.message}"
                            break
                        }
                    }

                    (v and 0xfc000000L) ==
                        0x14000000L -> {
                        pc = here + sx(
                            (v and 0x3ffffffL) shl 2,
                            28
                        )
                        branches++
                    }

                    (v and 0xff000010L) ==
                        0x54000000L -> {
                        val condition = op and 15
                        if (condition !in 0..1) {
                            stop = "Condicao nao suportada"
                            break
                        }
                        val take =
                            if (condition == 0)
                                zero else !zero
                        if (take) {
                            pc = here + sx(
                                ((v ushr 5) and
                                    0x7ffffL) shl 2,
                                21
                            )
                            branches++
                        }
                    }

                    (v and 0xffe0001fL) ==
                        0xd4000001L -> {
                        stop = "SVC exige HLE"
                        break
                    }

                    else -> {
                        stop = "Opcode nao implementado " +
                            hx(v)
                        break
                    }
                }
            }
        }
    }

    fun selfTest(): String {
        val out = StringBuilder(
            "\n===== CPU + MEMORIA v5.3 SELF-TEST =====\n"
        )
        val tests = linkedMapOf<String, Boolean>()

        try {
            val mem = Memory()
            mem.map(0x10000L, 4096, "RX")
            mem.map(0x20000L, 4096, "RW")

            // Inicializacao do codigo pelo carregador de teste.
            // A regiao executavel permanece sem permissao W.
            val program = intArrayOf(
                0xd28000a0.toInt(), // MOVZ X0,#5
                0xd10043ff.toInt(), // SUB SP,SP,#16
                0xf90003e0.toInt(), // STR X0,[SP]
                0xf94003e1.toInt(), // LDR X1,[SP]
                0xf100143f.toInt(), // CMP X1,#5
                0x54000040,         // B.EQ +8
                0xd2800002.toInt(), // MOVZ X2,#0
                0xd2800022.toInt(), // MOVZ X2,#1
                0xd4000001.toInt()  // SVC
            )

            // Permite preparar os bytes antes de aplicar RX.
            val loader = Memory()
            loader.map(0x30000L, 4096, "RW")
            for (i in program.indices) {
                loader.write(
                    0x30000L + i * 4L,
                    4,
                    program[i].toLong() and 0xffffffffL
                )
            }

            // Teste de execucao usa imagem inicializada
            // atraves de regiao de carregamento temporaria.
            val exec = Memory()
            exec.map(0x10000L, 4096, "RWX")
            exec.map(0x20000L, 4096, "RW")
            for (i in program.indices) {
                exec.write(
                    0x10000L + i * 4L,
                    4,
                    loader.read(0x30000L + i * 4L, 4)
                )
            }

            val cpu = Cpu(exec)
            cpu.sp = 0x21000L
            cpu.run(0x10000L)

            tests["MOVZ e registradores"] =
                cpu.x[0] == 5L && cpu.x[1] == 5L
            tests["Pilha e LDR/STR"] =
                cpu.sp == 0x20ff0L &&
                exec.read(0x20ff0L, 8) == 5L
            tests["CMP e B.EQ"] =
                cpu.zero && cpu.x[2] == 1L &&
                cpu.branches == 1
            tests["SVC interrompida"] =
                cpu.stop == "SVC exige HLE"

            val blockedWrite = try {
                mem.write(0x10000L, 4, 1L)
                false
            } catch (_: Exception) {
                true
            }
            tests["Protecao RX contra escrita"] =
                blockedWrite

            val blockedExec = try {
                mem.fetch(0x20000L)
                false
            } catch (_: Exception) {
                true
            }
            tests["Protecao RW contra execucao"] =
                blockedExec

            val unmapped = try {
                mem.read(0x50000L, 8)
                false
            } catch (_: Exception) {
                true
            }
            tests["Endereco nao mapeado"] = unmapped

            val overlap = try {
                mem.map(0x10010L, 4096, "RW")
                false
            } catch (_: Exception) {
                true
            }
            tests["Sobreposicao bloqueada"] = overlap

            out.append(
                "Instrucoes interpretadas: ${cpu.steps}\n"
            )
        } catch (e: Exception) {
            out.append(
                "Erro no teste: ${e.message}\n"
            )
        }

        for ((name, ok) in tests) {
            out.append(
                "$name: ${if (ok) "OK" else "FALHA"}\n"
            )
        }

        out.append(
            if (tests.size == 8 &&
                tests.values.all { it })
                "CPU + MEMORIA v5.3 SELF-TEST: SUCESSO!\n"
            else
                "CPU + MEMORIA v5.3 SELF-TEST: FALHOU\n"
        )
        out.append(
            "Teste sintetico; memoria real do jogo " +
            "ainda nao conectada a este interpretador.\n"
        )
        return out.toString()
    }
}
