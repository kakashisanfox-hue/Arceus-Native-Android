package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linker experimental: imagens de memoria Kotlin, nunca executa codigo. */
object NsoLinkSession {
    private const val RELATIVE = 1027L
    private const val ABS64 = 257L
    private const val GLOB_DAT = 1025L
    private const val JUMP_SLOT = 1026L
    private const val MAX_TOTAL = 256L * 1024L * 1024L

    private data class Rel(val offset: Long, val info: Long, val addend: Long)
    private data class Sym(val name: String, val value: Long, val defined: Boolean, val weak: Boolean, val binding: Int)
    private class Module(
        val name: String,
        val data: ByteArray,
        val offsets: LongArray,
        val sizes: LongArray,
        val bss: Long
    ) {
        var base = 0L
        val rels = ArrayList<Rel>()
        val plt = ArrayList<Rel>()
        val symbols = ArrayList<Sym>()
        val needed = ArrayList<String>()
        var error: String? = null
    }
    private val modules = ArrayList<Module>()
    private fun fits(m: Module, at: Long, n: Long): Boolean =
        at >= 0 && n >= 0 && at <= m.data.size.toLong() && n <= m.data.size.toLong() - at
    private fun bb(m: Module): ByteBuffer = ByteBuffer.wrap(m.data).order(ByteOrder.LITTLE_ENDIAN)
    private fun u32(b: ByteBuffer, p: Int): Long = b.getInt(p).toLong() and 0xffffffffL
    private fun align(n: Long): Long = (n + 65535L) and -65536L
    private fun hex(n: Long) = "0x" + java.lang.Long.toHexString(n)

    @Synchronized fun owns(memory: ByteArray): Boolean = modules.any { it.data === memory }
    @Synchronized fun reset() {
        for (m in modules) m.data.fill(0)
        modules.clear()
    }
    @Synchronized fun capture(
        name: String,
        memory: ByteArray,
        offsets: LongArray,
        sizes: LongArray,
        bss: Long
    ) {
        if (modules.any { it.name == name }) return
        val m = Module(name, memory, offsets.copyOf(), sizes.copyOf(), bss)
        try { inspect(m) } catch (e: Exception) { m.error = e.message ?: e.javaClass.simpleName }
        modules.add(m)
    }
    private fun inspect(m: Module) {
        val b = bb(m)
        require(fits(m, 4, 4)) { "Imagem pequena" }
        val mod = u32(b, 4)
        require(fits(m, mod, 28) && String(m.data, mod.toInt(), 4, Charsets.US_ASCII) == "MOD0") { "MOD0 ausente" }
        val dyn = mod + b.getInt(mod.toInt() + 4).toLong()
        val tags = HashMap<Long, Long>()
        val needs = ArrayList<Long>()
        var terminated = false
        for (i in 0 until 256) {
            val p = dyn + i * 16L
            require(fits(m, p, 16)) { "Dynamic invalido" }
            val tag = b.getLong(p.toInt())
            if (tag == 0L) { terminated = true; break }
            val value = b.getLong(p.toInt() + 8)
            if (tag == 1L) needs.add(value) else tags[tag] = value
        }
        require(terminated) { "Dynamic sem terminador" }
        fun readRel(at: Long, size: Long, dst: ArrayList<Rel>) {
            require(size >= 0 && size % 24L == 0L && size / 24L <= 500000L && fits(m, at, size)) { "Tabela RELA invalida" }
            for (i in 0 until (size / 24L).toInt()) {
                val p = (at + i * 24L).toInt()
                dst.add(Rel(b.getLong(p), b.getLong(p + 8), b.getLong(p + 16)))
            }
        }
        require((tags[9L] ?: 24L) == 24L) { "RELAENT invalido" }
        readRel(tags[7L] ?: 0L, tags[8L] ?: 0L, m.rels)
        val pltSize = tags[2L] ?: 0L
        if (pltSize > 0L) {
            require(tags[20L] == 7L) { "PLT nao e RELA" }
            readRel(tags[23L] ?: -1L, pltSize, m.plt)
        }
        val symtab = tags[6L] ?: throw IllegalArgumentException("SYMTAB ausente")
        val strtab = tags[5L] ?: throw IllegalArgumentException("STRTAB ausente")
        val strsz = tags[10L] ?: throw IllegalArgumentException("STRSZ ausente")
        require((tags[11L] ?: 24L) == 24L && fits(m, strtab, strsz)) { "Tabela de strings invalida" }
        fun stringAt(off: Long): String {
            if (off < 0 || off >= strsz) return ""
            val p = (strtab + off).toInt()
            val end = (strtab + strsz).toInt()
            var q = p
            while (q < end && q - p < 512 && m.data[q] != 0.toByte()) q++
            return String(m.data, p, q - p, Charsets.UTF_8)
        }
        for (off in needs) m.needed.add(stringAt(off))
        // DT_HASH usa nchain como contagem de simbolos ELF.
        val hash = tags[4L]
        val hashCount = if (hash != null && fits(m, hash, 8)) u32(b, hash.toInt() + 4) else 0L
        val maxReferenced = (m.rels.asSequence() + m.plt.asSequence())
            .map { it.info ushr 32 }.maxOrNull() ?: 0L
        val count = maxOf(hashCount, maxReferenced + 1L)
        require(count in 1..100000L && fits(m, symtab, count * 24L)) { "SYMTAB fora dos limites" }
        for (i in 0 until count.toInt()) {
            val p = (symtab + i * 24L).toInt()
            val stName = u32(b, p)
            val stInfo = m.data[p + 4].toInt() and 255
            val shndx = b.getShort(p + 6).toInt() and 65535
            val value = b.getLong(p + 8)
            m.symbols.add(Sym(stringAt(stName), value, shndx != 0, (stInfo ushr 4) == 2, stInfo ushr 4))
        }
    }

    @Synchronized fun finish(): String {
        val out = StringBuilder("\n===== LINKER v3.6 =====\n")
        try {
            if (modules.isEmpty()) return out.append("Nenhum modulo NSO carregado.\n").toString()
            var next = 0x100000000L
            var total = 0L
            for (m in modules) {
                total += m.data.size.toLong()
                require(total <= MAX_TOTAL) { "Memoria total excede 256 MiB" }
                m.base = next
                next += align(m.data.size.toLong()) + 65536L
                out.append("${m.name}: base=${hex(m.base)}, bytes=${m.data.size}, RELA=${m.rels.size}, PLT=${m.plt.size}, simbolos=${m.symbols.size}\n")
                if (m.error != null) out.append("  ERRO: ${m.error}\n")
                if (m.needed.isNotEmpty()) out.append("  DT_NEEDED: ${m.needed.joinToString(", ")}\n")
            }
            // Preferencia por definicao no proprio modulo; depois pelos demais.
            val exports = HashMap<String, Pair<Module, Sym>>()
            for (m in modules) if (m.error == null) {
                for (s in m.symbols) if (s.defined && s.binding in 1..2 && s.name.isNotEmpty() && s.value >= 0 && s.value < m.data.size) {
                    val prior = exports[s.name]
                    if (prior == null || (prior.second.weak && !s.weak)) exports[s.name] = m to s
                }
            }
            out.append("Simbolos exportados distintos: ${exports.size}\n")
            var relative = 0; var resolved = 0; var missing = 0; var invalid = 0; var unsupported = 0; var weakZero = 0; var special = 0
            val examples = LinkedHashSet<String>()
            val specialNames = setOf(
                "__rel_dyn_start", "__rel_dyn_end",
                "__rel_plt_start", "__rel_plt_end",
                "__EX_start", "__EX_end",
                "__got_start", "__got_end"
            )
            // Planejar todas as escritas antes de modificar as imagens.
            val writes = ArrayList<Triple<Module, Int, Long>>()
            for (m in modules) {
                if (m.error != null) continue
                for (r in m.rels + m.plt) {
                    val type = r.info and 0xffffffffL
                    val index = r.info ushr 32
                    if (!fits(m, r.offset, 8)) { invalid++; continue }
                    val value: Long = when {
                        type == RELATIVE && index == 0L -> {
                            relative++
                            m.base + r.addend
                        }
                        type == ABS64 || type == GLOB_DAT || type == JUMP_SLOT -> {
                            if (index >= m.symbols.size) { invalid++; continue }
                            val sym = m.symbols[index.toInt()]
                            val target = if (sym.defined) m to sym else exports[sym.name]
                            if (target == null) {
                                if (sym.weak) {
                                    weakZero++
                                    r.addend
                                } else {
                                    missing++
                                    if (sym.name in specialNames) special++
                                    if (examples.size < 24) {
                                        examples.add(
                                            "${m.name}: ${sym.name.ifEmpty { "<sem nome>" }}"
                                        )
                                    }
                                    continue
                                }
                            } else {
                                resolved++
                                target.first.base + target.second.value + r.addend
                            }
                        }
                        else -> { unsupported++; continue }
                    }
                    writes.add(Triple(m, r.offset.toInt(), value))
                }
            }
            out.append("RELATIVE identificadas: $relative\n")
            out.append("Ligacoes simbolicas resolvidas: $resolved\n")
            out.append("Simbolos fracos indefinidos tratados como zero: $weakZero\n")
            out.append("Simbolos especiais pendentes: $special\n")
            out.append("Simbolos nao resolvidos: $missing\n")
            out.append("Destinos invalidos: $invalid\n")
            out.append("Tipos nao suportados: $unsupported\n")
            for (x in examples) out.append("PENDENTE: $x\n")
            if (invalid == 0 && unsupported == 0 && missing == 0) {
                for ((m, off, value) in writes) bb(m).putLong(off, value)
                out.append("RELOCACOES APLICADAS NA SESSAO: ${writes.size}\n")
            } else out.append(
                "Escritas canceladas: linker ainda incompleto.\n"
            )
            out.append(
                if (missing == 0 && invalid == 0 && unsupported == 0)
                    "LINKER: RESOLVIDO NESTA SESSAO\n"
                else
                    "LINKER: PENDENCIAS REAIS; NAO CONCLUIDO\n"
            )
            out.append(
                ProcessMemory.report(
                    modules.filter { it.error == null }.map {
                        ProcessMemory.ModuleView(
                            it.name, it.base, it.data,
                            it.offsets, it.sizes, it.bss
                        )
                    }
                )
            )
            out.append(
                "Execucao ARM64 do jogo nao iniciada.\n"
            )
            // ARM64_TEST_V40
            out.append(Arm64Cpu.selfTest())
        } catch (e: Exception) {
            out.append("ERRO LINKER: ${e.message}\n")
        } finally { reset() }
        return out.toString()
    }
}
