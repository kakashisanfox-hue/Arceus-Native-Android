package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Interpretador AArch64 de diagnostico, sem executar codigo do jogo. */
object Arm64Cpu {
    private const val R = 1
    private const val W = 2
    private const val X = 4
    private const val LIMIT = 10000
    private const val BASE = 0x10000L

    private class Region(val start: Long, val bytes: ByteArray, val perms: Int) {
        fun contains(address: Long, size: Int): Boolean =
            address >= start && size >= 0 &&
            address - start <= bytes.size.toLong() - size
    }

    private class Memory {
        val regions = ArrayList<Region>()
        fun map(start: Long, size: Int, permissions: Int): Region {
            require(start >= 0 && size > 0 && size <= 1024 * 1024)
            require(start % 4096 == 0L && size % 4096 == 0)
            require(permissions in 1..7)
            require(start <= Long.MAX_VALUE - size)
            require(regions.none { start < it.start + it.bytes.size && it.start < start + size })
            return Region(start, ByteArray(size), permissions).also { regions.add(it) }
        }
        private fun locate(address: Long, size: Int, permission: Int): Pair<Region, Int> {
            val region = regions.firstOrNull { it.contains(address, size) }
                ?: error("Unmapped memory: 0x${address.toString(16)}")
            require(region.perms and permission != 0) {
                "Permission denied: 0x${address.toString(16)}"
            }
            return region to (address - region.start).toInt()
        }
        fun get32(address: Long, permission: Int = R): Int {
            val (region, off) = locate(address, 4, permission)
            return ByteBuffer.wrap(region.bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(off)
        }
        fun get64(address: Long): Long {
            val (region, off) = locate(address, 8, R)
            return ByteBuffer.wrap(region.bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(off)
        }
        fun put64(address: Long, value: Long) {
            val (region, off) = locate(address, 8, W)
            ByteBuffer.wrap(region.bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(off, value)
        }
        fun loadCode(region: Region, ops: IntArray) {
            require(region.perms and X != 0 && ops.size * 4 <= region.bytes.size)
            val b = ByteBuffer.wrap(region.bytes).order(ByteOrder.LITTLE_ENDIAN)
            ops.forEach { b.putInt(it) }
        }
    }

    private class Cpu(val memory: Memory, val start: Long) {
        val x = LongArray(31)
        var sp = 0L
        var pc = start
        var steps = 0
        var branches = 0
        var svc: Int? = null
        var stopped = false
        private fun reg(n: Int) = if (n == 31) 0L else x[n]
        private fun source(n: Int) = if (n == 31) sp else x[n]
        private fun dest(n: Int, value: Long, useSp: Boolean = false) {
            if (n == 31) { if (useSp) sp = value } else x[n] = value
        }
        private fun signed(value: Long, bits: Int) = (value shl (64 - bits)) shr (64 - bits)
        fun run() {
            while (!stopped && steps < LIMIT) {
                require(pc % 4L == 0L) { "Unaligned PC" }
                val here = pc
                val op = memory.get32(pc, X)
                val u = op.toLong() and 0xffffffffL
                val rd = op and 31
                val rn = (op ushr 5) and 31
                pc += 4
                steps++
                when {
                    u == 0xD503201FL -> Unit // NOP
                    (u and 0xFF800000L) == 0xD2800000L -> {
                        val imm = (op ushr 5) and 65535
                        val shift = ((op ushr 21) and 3) * 16
                        dest(rd, imm.toLong() shl shift)
                    }
                    (u and 0xFF000000L) == 0x91000000L ||
                    (u and 0xFF000000L) == 0xD1000000L -> {
                        val imm = ((op ushr 10) and 4095).toLong() shl
                            (if ((op ushr 22) and 1 != 0) 12 else 0)
                        val sub = (u and 0x40000000L) != 0L
                        dest(rd, if (sub) source(rn) - imm else source(rn) + imm, true)
                    }
                    (u and 0xFF200000L) == 0x8B000000L ||
                    (u and 0xFF200000L) == 0xCB000000L -> {
                        require((op ushr 22) and 3 == 0) { "Shift not implemented" }
                        val rm = (op ushr 16) and 31
                        val rhs = reg(rm) shl ((op ushr 10) and 63)
                        val sub = (u and 0x40000000L) != 0L
                        dest(rd, if (sub) reg(rn) - rhs else reg(rn) + rhs)
                    }
                    (u and 0xFFC00000L) == 0xF9400000L ||
                    (u and 0xFFC00000L) == 0xF9000000L -> {
                        val address = source(rn) + ((op ushr 10) and 4095) * 8L
                        if ((u and 0x00400000L) != 0L) dest(rd, memory.get64(address))
                        else memory.put64(address, reg(rd))
                    }
                    (u and 0xFC000000L) == 0x14000000L ||
                    (u and 0xFC000000L) == 0x94000000L -> {
                        val delta = signed((u and 0x3ffffffL) shl 2, 28)
                        if ((u and 0x80000000L) != 0L) x[30] = pc
                        pc = here + delta
                        branches++
                    }
                    (u and 0x7E000000L) == 0x34000000L -> {
                        val delta = signed(((u ushr 5) and 0x7ffffL) shl 2, 21)
                        val value = if (u and 0x80000000L != 0L) reg(rd) else reg(rd) and 0xffffffffL
                        val nonzero = u and 0x01000000L != 0L
                        if ((value != 0L) == nonzero) { pc = here + delta; branches++ }
                    }
                    (u and 0xFFFFFC1FL) == 0xD65F0000L -> {
                        val target = reg(rn)
                        if (target == 0L) stopped = true else { pc = target; branches++ }
                    }
                    (u and 0xFFFFFC1FL) == 0xD61F0000L ||
                    (u and 0xFFFFFC1FL) == 0xD63F0000L -> {
                        if (u and 0x00200000L != 0L) x[30] = pc
                        pc = reg(rn)
                        branches++
                    }
                    (u and 0xFFE0001FL) == 0xD4000001L -> {
                        svc = (op ushr 5) and 65535
                        stopped = true
                    }
                    else -> error("Unsupported opcode: 0x${u.toString(16)} at 0x${here.toString(16)}")
                }
            }
            require(steps < LIMIT) { "Instruction budget exhausted" }
        }
    }

    fun selfTest(): String {
        val out = StringBuilder("\n===== ARM64 CPU v4.2 =====\n")
        try {
            val memory = Memory()
            val code = memory.map(BASE, 4096, R or X)
            memory.map(0x20000, 4096, R or W)
            val program = intArrayOf(
                0xD28000A0.toInt(), // MOVZ X0,5
                0xD28000E1.toInt(), // MOVZ X1,7
                0x8B010002.toInt(), // ADD X2,X0,X1
                0xD10043FF.toInt(), // SUB SP,SP,16
                0xF90003E2.toInt(), // STR X2,[SP]
                0xF94003E3.toInt(), // LDR X3,[SP]
                0xCB000064.toInt(), // SUB X4,X3,X0
                0xB5000044.toInt(), // CBNZ X4,+8
                0xD2800C65.toInt(), // skipped
                0xD2800545.toInt(), // MOVZ X5,42
                0x14000002.toInt(), // B +8
                0xD2800C86.toInt(), // skipped
                0x94000003.toInt(), // BL +12
                0xD4000001.toInt(), // SVC 0
                0xD503201F.toInt(), // NOP
                0xD2800026.toInt(), // MOVZ X6,1
                0xD65F03C0.toInt()  // RET X30
            )
            memory.loadCode(code, program)
            val cpu = Cpu(memory, BASE)
            cpu.sp = 0x21000L
            cpu.run()
            val stack = memory.get64(0x20ff0)
            val arithmetic = cpu.x[2] == 12L && cpu.x[3] == 12L && cpu.x[4] == 7L
            val flow = cpu.x[5] == 42L && cpu.x[6] == 1L && cpu.svc == 0
            val stackOk = cpu.sp == 0x20ff0L && stack == 12L
            var denied = false
            try { memory.put64(BASE, 99L) } catch (_: IllegalArgumentException) { denied = true }
            var nx = false
            try { memory.get32(0x20000L, X) } catch (_: IllegalArgumentException) { nx = true }
            out.append("Aritmetica e registradores: ${if (arithmetic) "OK" else "FALHA"}\n")
            out.append("Pilha e LDR/STR: ${if (stackOk) "OK" else "FALHA"}\n")
            out.append("Desvios e SVC: ${if (flow) "OK" else "FALHA"}\n")
            out.append("Codigo somente leitura: ${if (denied) "OK" else "FALHA"}\n")
            out.append("Dados nao executaveis: ${if (nx) "OK" else "FALHA"}\n")
            out.append("Instrucoes: ${cpu.steps}; desvios: ${cpu.branches}\n")
            out.append(if (arithmetic && flow && stackOk && denied && nx)
                "ARM64 v4.2 SELF-TEST: SUCESSO!\n" else "ARM64 v4.2 SELF-TEST: FALHOU\n")
        } catch (e: Exception) {
            out.append("ARM64 v4.2 ERRO: ${e.message}\n")
        }
        out.append("Somente teste sintetico; Pokemon nao executado.\n")
        return out.toString()
    }
}
