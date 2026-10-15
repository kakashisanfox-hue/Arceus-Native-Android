package com.matheus.arceusnative

/** CPU AArch64 interpretada em Kotlin: subconjunto experimental, sem codigo nativo. */
object Arm64CoreV50 {
    private const val LIMIT = 256
    private fun hx(x: Long) = "0x" + java.lang.Long.toHexString(x)
    private fun sx(x: Long, bits: Int) = (x shl (64 - bits)) shr (64 - bits)
    private fun u(x: Int) = x.toLong() and 0xffffffffL

    data class Flags(val n: Boolean, val z: Boolean, val c: Boolean, val v: Boolean)
    private fun subFlags(a: Long, b: Long, width: Int): Flags {
        val mask = if (width == 64) -1L else 0xffffffffL
        val sign = if (width == 64) Long.MIN_VALUE else 0x80000000L
        val x = a and mask; val y = b and mask; val r = (x - y) and mask
        return Flags(r and sign != 0L, r == 0L,
            java.lang.Long.compareUnsigned(x, y) >= 0,
            ((x xor y) and (x xor r) and sign) != 0L)
    }
    private fun cond(c: Int, f: Flags) = when(c) {
        0 -> f.z; 1 -> !f.z; 2 -> f.c; 3 -> !f.c
        4 -> f.n; 5 -> !f.n; 6 -> f.v; 7 -> !f.v
        8 -> f.c && !f.z; 9 -> !f.c || f.z
        10 -> f.n == f.v; 11 -> f.n != f.v
        12 -> !f.z && f.n == f.v; 13 -> f.z || f.n != f.v
        14 -> true; else -> false
    }

    /** Test memory: isolated from actual game images. */
    private class TestMem {
        val words = HashMap<Long, Int>()
        val stack = HashMap<Long, Long>()
        fun fetch(pc: Long): Int = words[pc] ?: error("PC nao mapeado ${hx(pc)}")
        fun store(addr: Long, value: Long) {
            require(addr in 0x20000L..0x20ff8L && addr % 8L == 0L) { "Escrita fora da pilha" }
            stack[addr] = value
        }
        fun load(addr: Long): Long {
            require(addr in 0x20000L..0x20ff8L && addr % 8L == 0L) { "Leitura fora da pilha" }
            return stack[addr] ?: 0L
        }
    }
    private class Cpu(val fetch: (Long)->Int, val mem: TestMem? = null) {
        val x = arrayOfNulls<Long>(31)
        var sp: Long? = null
        var flags: Flags? = null
        var pc = 0L
        var steps = 0
        var branches = 0
        var stop = "Limite de instrucoes"
        private fun get(n: Int): Long? = if (n == 31) 0L else x[n]
        private fun base(n: Int): Long? = if (n == 31) sp else x[n]
        private fun set(n: Int, v: Long?, width: Int = 64, allowSp: Boolean = false) {
            val value = if (width == 32) v?.and(0xffffffffL) else v
            if (n == 31) { if (allowSp) sp = value } else x[n] = value
        }
        fun run(start: Long, budget: Int = LIMIT): String {
            pc = start
            val log = StringBuilder()
            val visited = HashSet<Long>()
            while (steps < budget) {
                if (!visited.add(pc)) { stop = "Loop detectado"; break }
                if (pc % 4L != 0L) { stop = "PC desalinhado"; break }
                val here = pc
                val op = try { fetch(pc) } catch(e: Exception) {
                    stop = "Fetch negado: ${e.message}"; break
                }
                val v = u(op); val rd = op and 31; val rn = (op ushr 5) and 31
                steps++
                pc = here + 4
                var name = ""
                when {
                    v == 0xD503201FL -> name = "NOP"
                    (v and 0x7f800000L) == 0x52800000L -> {
                        val width = if (v and 0x80000000L != 0L) 64 else 32
                        val shift = ((op ushr 21) and 3) * 16
                        if (shift >= width) { stop = "MOVZ shift invalido"; break }
                        set(rd, ((op ushr 5) and 65535).toLong() shl shift, width)
                        name = "MOVZ"
                    }
                    (v and 0x1f000000L) == 0x11000000L -> {
                        val width = if (v and 0x80000000L != 0L) 64 else 32
                        val sub = v and 0x40000000L != 0L
                        val update = v and 0x20000000L != 0L
                        val shift = (op ushr 22) and 3
                        if (shift > 1) { stop = "Shift invalido"; break }
                        val imm = ((op ushr 10) and 4095).toLong() shl (if (shift == 1) 12 else 0)
                        val lhs = if (update) get(rn) else base(rn)
                        if (lhs == null) { stop = "X$rn/SP desconhecido"; break }
                        val mask = if (width == 64) -1L else 0xffffffffL
                        val result = (if (sub) lhs - imm else lhs + imm) and mask
                        if (update) {
                            // NZCV for SUBS and ADDS
                            flags = if (sub) subFlags(lhs, imm, width) else {
                                val sign = if (width == 64) Long.MIN_VALUE else 0x80000000L
                                val a = lhs and mask; val b = imm and mask
                                Flags(result and sign != 0L, result == 0L,
                                    java.lang.Long.compareUnsigned(result, a) < 0,
                                    ((a xor result) and (b xor result) and sign) != 0L)
                            }
                        }
                        set(rd, result, width, !update)
                        name = if (update && rd == 31) "CMP/CMN" else if (update) "ADDS/SUBS" else "ADD/SUB"
                    }
                    (v and 0xff000010L) == 0x54000000L -> {
                        val f = flags
                        if (f == null) { stop = "NZCV desconhecido"; break }
                        val c = op and 15
                        if (c == 15) { stop = "Condicao reservada"; break }
                        if (cond(c,f)) { pc = here + sx((v ushr 5 and 0x7ffffL) shl 2,21); branches++ }
                        name = "B.cond"
                    }
                    (v and 0xfc000000L) == 0x14000000L ||
                    (v and 0xfc000000L) == 0x94000000L -> {
                        val link = v and 0x80000000L != 0L
                        if (link) x[30] = pc
                        pc = here + sx((v and 0x3ffffffL) shl 2,28)
                        branches++; name = if(link) "BL" else "B"
                    }
                    (v and 0x7e000000L) == 0x34000000L -> {
                        val reg = get(rd)
                        if (reg == null) { stop = "X$rd desconhecido"; break }
                        val value = if (v and 0x80000000L != 0L) reg else reg and 0xffffffffL
                        val nonzero = v and 0x01000000L != 0L
                        if ((value != 0L) == nonzero) {
                            pc = here + sx((v ushr 5 and 0x7ffffL) shl 2,21); branches++
                        }
                        name = if(nonzero) "CBNZ" else "CBZ"
                    }
                    (v and 0xffc00000L) == 0xf9000000L ||
                    (v and 0xffc00000L) == 0xf9400000L -> {
                        val m = mem
                        if (m == null) { stop = "Acesso de dados real ainda nao integrado"; break }
                        val b = base(rn)
                        if (b == null) { stop = "Base desconhecida"; break }
                        val addr = b + ((op ushr 10) and 4095) * 8L
                        try {
                            if (v and 0x00400000L != 0L) set(rd, m.load(addr))
                            else m.store(addr, get(rd) ?: error("Fonte desconhecida"))
                        } catch(e: Exception) { stop = "Memoria: ${e.message}"; break }
                        name = "LDR/STR"
                    }
                    (v and 0xfffffc1fL) == 0xd65f0000L -> {
                        val target = get(rn)
                        if (target == null) { stop = "RET destino desconhecido"; break }
                        pc = target; branches++; name = "RET"
                    }
                    (v and 0xffe0001fL) == 0xd4000001L -> {
                        stop = "SVC exige kernel HLE"; name = "SVC"
                        log.append("${hx(here)} ${hx(v)} $name\n"); break
                    }
                    else -> { stop = "Opcode nao implementado ${hx(v)}"; break }
                }
                if (log.length < 4000) log.append("${hx(here)} ${hx(v)} $name -> ${hx(pc)}\n")
            }
            log.append("Passos: $steps; desvios: $branches; parada: $stop\n")
            return log.toString()
        }
    }
    fun selfTest(): String {
        val out = StringBuilder("\n===== ARM64 CORE v5.0 SELF-TEST =====\n")
        val m = TestMem()
        val start = 0x10000L
        val ops = intArrayOf(
            0xd28000a0.toInt(), // MOVZ X0,5
            0xd10043ff.toInt(), // SUB SP,SP,#16
            0xf90003e0.toInt(), // STR X0,[SP]
            0xf94003e1.toInt(), // LDR X1,[SP]
            0xf100143f.toInt(), // CMP X1,#5
            0x54000040, // B.EQ +8
            0xd2800002.toInt(), // MOVZ X2,0 (skip)
            0xd2800022.toInt(), // MOVZ X2,1
            0xd4000001.toInt()  // SVC
        )
        ops.forEachIndexed { i, op -> m.words[start + i*4L] = op }
        val cpu = Cpu(m::fetch, m)
        cpu.sp = 0x21000L
        val trace = cpu.run(start, 32)
        val tests = linkedMapOf(
            "MOVZ e registradores" to (cpu.x[0] == 5L && cpu.x[1] == 5L),
            "Pilha e LDR/STR" to (cpu.sp == 0x20ff0L && m.load(0x20ff0L) == 5L),
            "CMP e flag Z" to (cpu.flags?.z == true),
            "B.EQ condicional" to (cpu.x[2] == 1L && cpu.branches == 1),
            "SVC interrompida" to (cpu.stop == "SVC exige kernel HLE")
        )
        tests.forEach { (name, ok) -> out.append("$name: ${if(ok) "OK" else "FALHA"}\n") }
        out.append(if(tests.values.all{it}) "ARM64 CORE v5.0 SELF-TEST: SUCESSO!\n" else "ARM64 CORE v5.0 SELF-TEST: FALHOU\n")
        out.append("CPU parcial: NEON, atomicas, excecoes e HLE pendentes.\n")
        return out.toString()
    }
    fun probe(start: Long, fetch: (Long)->Int): String {
        val out = StringBuilder("\n===== ARM64 CORE v5.0 CODIGO REAL =====\n")
        val cpu = Cpu(fetch)
        out.append(cpu.run(start, 32))
        out.append("Contexto real de registradores desconhecido; parada conservadora.\n")
        return out.toString()
    }
}
