package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CPU AArch64 experimental.
 * Executa apenas codigo sintetico de teste.
 * Nao executa codigo Nintendo Switch.
 */
object Arm64Cpu {
    private class Cpu {
        val x = LongArray(31)
        var pc = 0L
        var halted = false
        var instructions = 0

        fun readReg(n: Int): Long =
            if (n == 31) 0L else x[n]

        fun writeReg(n: Int, value: Long) {
            if (n != 31) x[n] = value
        }
    }

    private fun hex(v: Long): String =
        "0x" + java.lang.Long.toHexString(v)

    fun selfTest(): String {
        val out = StringBuilder(
            "\n===== ARM64 CPU v4.0 =====\n"
        )

        // MOVZ X0,#5
        // MOVZ X1,#7
        // ADD X2,X0,X1
        // RET
        val program = intArrayOf(
            0xD28000A0.toInt(),
            0xD28000E1.toInt(),
            0x8B010002.toInt(),
            0xD65F03C0.toInt()
        )

        val memory = ByteBuffer.allocate(
            program.size * 4
        ).order(ByteOrder.LITTLE_ENDIAN)

        for (ins in program) memory.putInt(ins)

        val cpu = Cpu()
        val maxSteps = 32

        try {
            while (!cpu.halted &&
                cpu.instructions < maxSteps) {

                require(cpu.pc >= 0)
                require(cpu.pc % 4L == 0L)
                require(cpu.pc <= memory.capacity() - 4L)

                val op = memory.getInt(cpu.pc.toInt())
                val bits = op.toLong() and 0xffffffffL
                val rd = op and 31
                val rn = (op ushr 5) and 31

                cpu.pc += 4
                cpu.instructions++

                when {
                    // MOVZ Xd, #imm16, LSL #shift
                    (bits and 0xFF800000L) ==
                        0xD2800000L -> {

                        val imm = (op ushr 5) and 65535
                        val shift =
                            ((op ushr 21) and 3) * 16

                        cpu.writeReg(
                            rd,
                            imm.toLong() shl shift
                        )
                    }

                    // ADD Xd, Xn, Xm (shifted register)
                    (bits and 0xFF200000L) ==
                        0x8B000000L -> {

                        val rm = (op ushr 16) and 31
                        val shiftType =
                            (op ushr 22) and 3
                        val amount =
                            (op ushr 10) and 63

                        require(shiftType == 0) {
                            "Shift ainda nao suportado"
                        }

                        val value =
                            cpu.readReg(rn) +
                            (cpu.readReg(rm) shl amount)

                        cpu.writeReg(rd, value)
                    }

                    // RET Xn
                    (bits and 0xFFFFFC1FL) ==
                        0xD65F0000L -> {
                        cpu.halted = true
                    }

                    else -> {
                        throw IllegalStateException(
                            "Instrucao desconhecida: " +
                            hex(bits)
                        )
                    }
                }
            }

            out.append(
                "Instrucoes executadas: " +
                "${cpu.instructions}\n"
            )
            out.append("X0: ${cpu.x[0]}\n")
            out.append("X1: ${cpu.x[1]}\n")
            out.append("X2: ${cpu.x[2]}\n")
            out.append("PC final: ${hex(cpu.pc)}\n")
            out.append(
                "RET reconhecido: ${cpu.halted}\n"
            )

            if (cpu.halted &&
                cpu.x[0] == 5L &&
                cpu.x[1] == 7L &&
                cpu.x[2] == 12L &&
                cpu.instructions == 4) {
                out.append(
                    "ARM64 SELF-TEST: SUCESSO!\n"
                )
            } else {
                out.append(
                    "ARM64 SELF-TEST: FALHOU\n"
                )
            }

        } catch (e: Exception) {
            out.append(
                "ARM64 ERRO: ${e.message}\n"
            )
        }

        out.append(
            "Codigo executado: somente teste sintetico.\n"
        )
        out.append(
            "Codigo do Pokemon: nao executado.\n"
        )
        return out.toString()
    }
}
