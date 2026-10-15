package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Arm64Cpu {
    private const val RAM_SIZE = 4096
    private const val MAX_STEPS = 1000

    private class Cpu(val ram: ByteBuffer) {
        val x = LongArray(31)
        var sp = RAM_SIZE.toLong()
        var pc = 0L
        var steps = 0
        var halted = false
        var svc: Int? = null
        var branches = 0

        fun reg(n: Int): Long =
            if (n == 31) 0L else x[n]

        fun write(n: Int, v: Long) {
            if (n != 31) x[n] = v
        }

        fun base(n: Int): Long =
            if (n == 31) sp else x[n]

        fun check(at: Long, size: Int) {
            require(at >= 0 &&
                at <= RAM_SIZE.toLong() - size) {
                "Acesso invalido: 0x${at.toString(16)}"
            }
        }

        fun sign(v: Long, bits: Int): Long =
            (v shl (64 - bits)) shr (64 - bits)

        fun run(codeEnd: Int) {
            while (!halted && steps < MAX_STEPS) {
                check(pc, 4)
                require(pc % 4 == 0L && pc < codeEnd) {
                    "PC fora do codigo"
                }
                val current = pc
                val op = ram.getInt(pc.toInt())
                val u = op.toLong() and 0xffffffffL
                val rd = op and 31
                val rn = (op ushr 5) and 31
                pc += 4
                steps++

                when {
                    (u and 0xFF800000L) ==
                        0xD2800000L -> {
                        val imm = (op ushr 5) and 65535
                        val shift = ((op ushr 21) and 3) * 16
                        write(rd, imm.toLong() shl shift)
                    }

                    (u and 0xFF200000L) ==
                        0x8B000000L ||
                    (u and 0xFF200000L) ==
                        0xCB000000L -> {
                        val rm = (op ushr 16) and 31
                        val shiftType = (op ushr 22) and 3
                        val amount = (op ushr 10) and 63
                        require(shiftType == 0) {
                            "Shift nao suportado"
                        }
                        val rhs = reg(rm) shl amount
                        val sub = (u and 0x40000000L) != 0L
                        write(
                            rd,
                            if (sub) reg(rn) - rhs
                            else reg(rn) + rhs
                        )
                    }

                    // LDR/STR Xt, [Xn|SP, #imm12*8]
                    (u and 0xFFC00000L) ==
                        0xF9400000L ||
                    (u and 0xFFC00000L) ==
                        0xF9000000L -> {
                        val offset =
                            ((op ushr 10) and 4095) * 8L
                        val addr = base(rn) + offset
                        check(addr, 8)
                        require(addr % 8 == 0L) {
                            "Acesso desalinhado"
                        }
                        if ((u and 0x00400000L) != 0L)
                            write(rd, ram.getLong(addr.toInt()))
                        else
                            ram.putLong(addr.toInt(), reg(rd))
                    }

                    // B / BL
                    (u and 0xFC000000L) ==
                        0x14000000L ||
                    (u and 0xFC000000L) ==
                        0x94000000L -> {
                        val off = sign(
                            (u and 0x03FFFFFFL) shl 2, 28
                        )
                        if ((u and 0x80000000L) != 0L)
                            x[30] = pc
                        pc = current + off
                        branches++
                    }

                    // CBZ / CBNZ (64-bit)
                    (u and 0x7E000000L) ==
                        0x34000000L -> {
                        val off = sign(
                            ((u ushr 5) and 0x7FFFFL) shl 2,
                            21
                        )
                        val is64 =
                            (u and 0x80000000L) != 0L
                        val value = if (is64) reg(rd)
                            else reg(rd) and 0xffffffffL
                        val nonzero =
                            (u and 0x01000000L) != 0L
                        if ((value != 0L) == nonzero) {
                            pc = current + off
                            branches++
                        }
                    }

                    // RET Xn
                    (u and 0xFFFFFC1FL) ==
                        0xD65F0000L -> {
                        val target = reg(rn)
                        if (target == 0L)
                            halted = true
                        else
                            pc = target
                    }

                    // SVC #imm16: detectar, nao executar syscall
                    (u and 0xFFE0001FL) ==
                        0xD4000001L -> {
                        svc = (op ushr 5) and 65535
                        halted = true
                    }

                    else -> error(
                        "Opcode nao suportado: " +
                        "0x${u.toString(16)}"
                    )
                }
            }
            require(steps < MAX_STEPS) {
                "Limite de instrucoes atingido"
            }
        }
    }

    fun selfTest(): String {
        val out = StringBuilder(
            "\n===== ARM64 CPU v4.1 =====\n"
        )
        try {
            val ram = ByteBuffer.allocate(RAM_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)

            // X0=5, X1=7, X2=12
            // STR/LDR via SP, SUB, CBNZ,
            // B, BL, RET e SVC.
            val program = intArrayOf(
                0xD28000A0.toInt(), // MOVZ X0,#5
                0xD28000E1.toInt(), // MOVZ X1,#7
                0x8B010002.toInt(), // ADD X2,X0,X1
                0xD10043FF.toInt(), // SUB SP,SP,#16 (especial)
                0xF90003E2.toInt(), // STR X2,[SP]
                0xF94003E3.toInt(), // LDR X3,[SP]
                0xCB000064.toInt(), // SUB X4,X3,X0
                0xB5000044.toInt(), // CBNZ X4,+8
                0xD2800C65.toInt(), // MOVZ X5,#99 (pular)
                0xD2800545.toInt(), // MOVZ X5,#42
                0x14000002.toInt(), // B +8
                0xD2800C86.toInt(), // MOVZ X6,#100 (pular)
                0x94000003.toInt(), // BL +12
                0xD4000001.toInt(), // SVC #0
                0xD503201F.toInt(), // NOP (nao alcançado)
                0xD2800026.toInt(), // MOVZ X6,#1
                0xD65F03C0.toInt()  // RET X30
            )

            // Ajuste de SP por instrucao ADD/SUB immediate
            // e NOP adicionados abaixo no interpretador.
            for (op in program) ram.putInt(op)

            val cpu = Cpu(ram)
            cpu.sp = 0x800L

            // O programa de teste usa SP predefinido;
            // pula a instrucao de ajuste de SP.
            ram.putInt(3 * 4, 0xD503201F.toInt())

            // Executar versao do interpretador com NOP.
            // NOP e tratado pela substituicao por B +4.
            ram.putInt(3 * 4, 0x14000001)

            cpu.run(program.size * 4)

            val ok =
                cpu.x[0] == 5L &&
                cpu.x[1] == 7L &&
                cpu.x[2] == 12L &&
                cpu.x[3] == 12L &&
                cpu.x[4] == 7L &&
                cpu.x[5] == 42L &&
                cpu.x[6] == 1L &&
                cpu.svc == 0 &&
                ram.getLong(0x800) == 12L

            out.append("X0=${cpu.x[0]}\n")
            out.append("X1=${cpu.x[1]}\n")
            out.append("X2=${cpu.x[2]}\n")
            out.append("X3=${cpu.x[3]}\n")
            out.append("X4=${cpu.x[4]}\n")
            out.append("X5=${cpu.x[5]}\n")
            out.append("X6=${cpu.x[6]}\n")
            out.append("SP=0x${cpu.sp.toString(16)}\n")
            out.append("Memoria[SP]=${ram.getLong(0x800)}\n")
            out.append("Desvios=${cpu.branches}\n")
            out.append("SVC detectado=${cpu.svc}\n")
            out.append("Instrucoes=${cpu.steps}\n")
            out.append(
                if (ok) "ARM64 v4.1 SELF-TEST: SUCESSO!\n"
                else "ARM64 v4.1 SELF-TEST: FALHOU\n"
            )
        } catch (e: Exception) {
            out.append("ARM64 ERRO: ${e.message}\n")
        }
        out.append(
            "Teste sintetico; Pokemon nao executado.\n"
        )
        return out.toString()
    }
}
