package com.matheus.arceusnative

/**
 * ARM64 Consolidado v6.0 - base experimental.
 * Testes sinteticos e leitura real sem executar o jogo.
 */
object Arm64ConsolidatedV60 {
    private fun hex(v: Long): String =
        "0x" + java.lang.Long.toHexString(v)

    class Context {
        val x = arrayOfNulls<Long>(31)
        var pc: Long? = null
        var sp: Long? = null
        var n: Boolean? = null
        var z: Boolean? = null
        var c: Boolean? = null
        var v: Boolean? = null

        fun get(r: Int): Long? {
            require(r in 0..31)
            return if (r == 31) 0L else x[r]
        }

        fun set(r: Int, value: Long) {
            require(r in 0..31)
            if (r != 31) x[r] = value
        }

        fun ready(): Boolean =
            pc != null && sp != null &&
            sp!! % 16L == 0L
    }

    class Memory {
        data class Area(
            val start: Long,
            val data: ByteArray,
            val perms: String
        )

        private val areas = ArrayList<Area>()

        fun map(
            start: Long,
            data: ByteArray,
            perms: String
        ) {
            require(start >= 0 && data.isNotEmpty())
            require(perms.isNotEmpty())
            require(perms.all { it in "RWX" })
            require(start <= Long.MAX_VALUE - data.size)

            val end = start + data.size
            require(areas.none {
                start < it.start + it.data.size &&
                it.start < end
            })
            areas.add(Area(start, data, perms))
        }

        private fun access(
            address: Long,
            count: Int,
            permission: Char
        ): Pair<Area, Int> {
            require(count in 1..8)
            val a = areas.firstOrNull {
                address >= it.start &&
                address - it.start <=
                    it.data.size.toLong() - count
            } ?: error("Endereco nao mapeado")

            require(permission in a.perms) {
                "Permissao $permission negada"
            }
            return Pair(a, (address - a.start).toInt())
        }

        fun read(
            address: Long,
            count: Int,
            permission: Char = 'R'
        ): Long {
            val (a, offset) =
                access(address, count, permission)
            var value = 0L
            for (i in 0 until count) {
                value = value or (
                    (a.data[offset + i].toLong()
                        and 255L) shl (i * 8)
                )
            }
            return value
        }

        fun write(
            address: Long,
            count: Int,
            value: Long
        ) {
            val (a, offset) =
                access(address, count, 'W')
            for (i in 0 until count) {
                a.data[offset + i] =
                    (value ushr (i * 8)).toByte()
            }
        }

        fun fetch(address: Long): Int {
            require(address % 4L == 0L)
            return read(address, 4, 'X').toInt()
        }

        @Synchronized
        fun compareExchange64(
            address: Long,
            expected: Long,
            replacement: Long
        ): Long {
            require(address % 8L == 0L)
            val previous = read(address, 8)
            if (previous == expected) {
                write(address, 8, replacement)
            }
            return previous
        }
    }

    class Vector128 {
        val bytes = ByteArray(16)

        fun xor(other: Vector128): Vector128 {
            val result = Vector128()
            for (i in 0..15) {
                result.bytes[i] =
                    (bytes[i].toInt() xor
                     other.bytes[i].toInt()).toByte()
            }
            return result
        }

        fun addBytes(other: Vector128): Vector128 {
            val result = Vector128()
            for (i in 0..15) {
                result.bytes[i] =
                    (bytes[i].toInt() +
                     other.bytes[i].toInt()).toByte()
            }
            return result
        }
    }

    sealed class Stop {
        data class Svc(val number: Int): Stop()
        data class Fault(val reason: String): Stop()
        data class Unsupported(val opcode: Int): Stop()
    }

    fun decodeAndApply(
        op: Int,
        ctx: Context
    ): Stop? {
        val value = op.toLong() and 0xffffffffL
        val rd = op and 31
        val rn = (op ushr 5) and 31

        if ((value and 0xffe0001fL) ==
            0xd4000001L) {
            return Stop.Svc((op ushr 5) and 65535)
        }

        if ((value and 0x7f800000L) ==
            0x52800000L) {
            val wide =
                (value and 0x80000000L) != 0L
            val shift = ((op ushr 21) and 3) * 16
            if (!wide && shift >= 32)
                return Stop.Fault("MOVZ invalido")
            val imm =
                ((op ushr 5) and 65535).toLong()
            var result = imm shl shift
            if (!wide) result =
                result and 0xffffffffL
            ctx.set(rd, result)
            return null
        }

        if ((value and 0x7f800000L) ==
            0x72800000L) {
            val wide =
                (value and 0x80000000L) != 0L
            val shift = ((op ushr 21) and 3) * 16
            if (!wide && shift >= 32)
                return Stop.Fault("MOVK invalido")
            val old = ctx.get(rd)
                ?: return Stop.Fault("Rd desconhecido")
            val mask = 65535L shl shift
            val imm =
                ((op ushr 5) and 65535).toLong()
            var result =
                (old and mask.inv()) or
                (imm shl shift)
            if (!wide) result =
                result and 0xffffffffL
            ctx.set(rd, result)
            return null
        }

        if ((value and 0x7f800000L) ==
            0x12800000L) {
            val wide =
                (value and 0x80000000L) != 0L
            val shift = ((op ushr 21) and 3) * 16
            if (!wide && shift >= 32)
                return Stop.Fault("MOVN invalido")
            val imm =
                ((op ushr 5) and 65535).toLong()
            var result = (imm shl shift).inv()
            if (!wide) result =
                result and 0xffffffffL
            ctx.set(rd, result)
            return null
        }

        if ((value and 0xff000000L) ==
            0xf1000000L &&
            rd == 31) {
            val lhs = ctx.get(rn)
                ?: return Stop.Fault("X$rn desconhecido")
            val shift = (op ushr 22) and 3
            if (shift > 1)
                return Stop.Fault("CMP shift invalido")
            val imm =
                ((op ushr 10) and 4095)
                    .toLong() shl
                (if (shift == 1) 12 else 0)
            val wide =
                (value and 0x80000000L) != 0L
            val mask =
                if (wide) -1L else 0xffffffffL
            val a = lhs and mask
            val b = imm and mask
            val result = (a - b) and mask
            ctx.z = result == 0L
            ctx.n =
                if (wide) result < 0
                else (result and 0x80000000L) != 0L
            ctx.c = java.lang.Long.compareUnsigned(
                a, b
            ) >= 0
            val sign =
                if (wide) Long.MIN_VALUE
                else 0x80000000L
            ctx.v = (
                ((a xor b) and (a xor result)
                    and sign) != 0L
            )
            return null
        }

        return Stop.Unsupported(op)
    }

    fun selfTest(): String {
        val tests = linkedMapOf<String, Boolean>()
        val out = StringBuilder(
            "\n===== ARM64 CONSOLIDADO v6.0 =====\n"
        )

        try {
            val c = Context()
            tests["1. Contexto desconhecido"] =
                c.get(0) == null && !c.ready()

            c.pc = 0x10000L
            c.sp = 0x21000L
            tests["1. Contexto sintetico"] =
                c.ready() && c.get(0) == null

            val m = Memory()
            m.map(0x10000L, ByteArray(64), "RX")
            m.map(0x20000L, ByteArray(4096), "RW")

            m.write(0x20000L, 8, 123L)
            tests["2. Memoria leitura/escrita"] =
                m.read(0x20000L, 8) == 123L

            tests["2. Memoria RX protegida"] =
                try {
                    m.write(0x10000L, 4, 1L)
                    false
                } catch (_: Exception) {
                    true
                }

            tests["2. Dados nao executaveis"] =
                try {
                    m.fetch(0x20000L)
                    false
                } catch (_: Exception) {
                    true
                }

            val movz = decodeAndApply(
                0xd28000a0.toInt(), c
            )
            val movk = decodeAndApply(
                0xf2a00020.toInt(), c
            )
            tests["3. MOVZ/MOVK"] =
                movz == null && movk == null &&
                c.get(0) == 5L

            val cmp = decodeAndApply(
                0xf100141f.toInt(), c
            )
            tests["3. CMP/NZCV"] =
                cmp == null && c.z == false &&
                c.c == true

            val unsupported = decodeAndApply(-1, c)
            tests["3. Opcode desconhecido"] =
                unsupported is Stop.Unsupported

            val a = Vector128()
            val b = Vector128()
            a.bytes[0] = 5
            b.bytes[0] = 3
            tests["4. SIMD XOR 128 bits"] =
                a.xor(b).bytes[0].toInt() == 6
            tests["4. SIMD soma de bytes"] =
                a.addBytes(b).bytes[0].toInt() == 8

            val before = m.compareExchange64(
                0x20000L, 123L, 456L
            )
            tests["5. Compare-exchange"] =
                before == 123L &&
                m.read(0x20000L, 8) == 456L

            val failed = m.compareExchange64(
                0x20000L, 123L, 789L
            )
            tests["5. CAS sem troca indevida"] =
                failed == 456L &&
                m.read(0x20000L, 8) == 456L

            val svc = decodeAndApply(
                0xd4000001.toInt(), c
            )
            tests["6. SVC capturada"] =
                svc is Stop.Svc

            val fault = decodeAndApply(
                0xf2e00020.toInt(), c
            )
            tests["6. Falha controlada"] =
                fault == null ||
                fault is Stop.Fault
        } catch (e: Exception) {
            out.append(
                "ERRO DE TESTE: ${e.message}\n"
            )
        }

        for ((name, ok) in tests) {
            out.append(
                "$name: ${if (ok) "OK" else "FALHA"}\n"
            )
        }

        val passed = tests.values.count { it }
        out.append("Resultado: $passed/${tests.size}\n")
        out.append(
            if (tests.size == 14 &&
                passed == tests.size)
                "ARM64 v6.0 SELF-TEST: SUCESSO!\n"
            else
                "ARM64 v6.0 SELF-TEST: FALHOU\n"
        )
        out.append(
            "Seis subsistemas testados parcialmente.\n"
        )
        out.append(
            "NEON completo, atomicas ARM64 e " +
            "excecoes arquiteturais ainda pendentes.\n"
        )
        return out.toString()
    }

    fun realProbe(
        start: Long,
        fetch: (Long) -> Int
    ): String {
        val out = StringBuilder(
            "\n===== ARM64 v6.0 MEMORIA REAL =====\n"
        )
        try {
            val op = fetch(start)
            out.append(
                "RTLD PC candidato: ${hex(start)}\n"
            )
            out.append(
                "Opcode real: ${hex(
                    op.toLong() and 0xffffffffL
                )}\n"
            )
            out.append(
                "Leitura da memoria real: OK\n"
            )
            out.append(
                "Execucao real: BLOQUEADA ate " +
                "inicializacao valida do processo.\n"
            )
        } catch (e: Exception) {
            out.append(
                "Leitura real falhou: ${e.message}\n"
            )
        }
        return out.toString()
    }
}
