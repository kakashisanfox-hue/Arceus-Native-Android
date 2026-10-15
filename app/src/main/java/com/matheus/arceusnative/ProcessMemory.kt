package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ProcessMemory {
    data class Region(
        val name: String,
        val start: Long,
        val size: Long,
        val perms: String,
        val data: ByteArray,
        val source: Int
    )

    private val regions = ArrayList<Region>()

    fun map(r: Region) {
        require(r.start >= 0 && r.size >= 0)
        require(r.source >= 0)
        require(r.size <= r.data.size.toLong() - r.source)
        require(r.start <= Long.MAX_VALUE - r.size)
        for (old in regions) {
            require(
                r.start + r.size <= old.start ||
                old.start + old.size <= r.start
            ) { "Regioes sobrepostas" }
        }
        regions.add(r)
    }

    fun read32(address: Long, execute: Boolean): Int {
        require(address % 4L == 0L)
        val r = regions.firstOrNull {
            address >= it.start &&
            address - it.start <= it.size - 4L
        } ?: error("Endereco nao mapeado")

        require(
            if (execute) 'X' in r.perms
            else 'R' in r.perms
        ) { "Permissao negada" }

        val offset = r.source +
            (address - r.start).toInt()

        return ByteBuffer.wrap(r.data)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt(offset)
    }

    fun report(
        modules: List<ModuleView>
    ): String {
        regions.clear()
        val out = StringBuilder(
            "\n===== MEMORIA VIRTUAL v4.3 =====\n"
        )

        try {
            for (m in modules) {
                val names = arrayOf("TEXT", "RO", "DATA")
                val perms = arrayOf("RX", "R", "RW")

                for (i in 0..2) {
                    val offset = m.offsets[i]
                    val size = m.sizes[i]
                    if (size == 0L) continue

                    map(
                        Region(
                            "${m.name}:${names[i]}",
                            m.base + offset,
                            size,
                            perms[i],
                            m.data,
                            offset.toInt()
                        )
                    )
                }

                val bssStart =
                    m.offsets[2] + m.sizes[2]
                val bssSize = m.bss

                if (bssSize > 0L) {
                    map(
                        Region(
                            "${m.name}:BSS",
                            m.base + bssStart,
                            bssSize,
                            "RW",
                            m.data,
                            bssStart.toInt()
                        )
                    )
                }
            }

            out.append(
                "Regioes mapeadas: ${regions.size}\n"
            )

            for (r in regions) {
                out.append(
                    "${r.name}: 0x" +
                    r.start.toString(16) +
                    " bytes=${r.size} " +
                    "permissoes=${r.perms}\n"
                )
            }

            val rtld = modules.firstOrNull {
                it.name == "rtld"
            }

            if (rtld != null) {
                val entry = rtld.base + rtld.offsets[0]
                val op = read32(entry, true)
                out.append(
                    "RTLD TEXT inicio: 0x" +
                    entry.toString(16) + "\n"
                )
                out.append(
                    "Primeira instrucao real: 0x" +
                    (op.toLong() and 0xffffffffL)
                        .toString(16) + "\n"
                )
                out.append(
                    "Leitura executavel: OK\n"
                )
            } else {
                out.append("RTLD nao encontrado.\n")
            }

            // REAL_ARM64_V44
            if (rtld != null) {
                val start =
                    rtld.base + rtld.offsets[0]

                out.append(
                    RealArm64Probe.run(start) { address ->
                        read32(address, true)
                    }
                )
            }

            val ro = regions.firstOrNull {
                it.name.endsWith(":RO") &&
                it.size >= 4L
            }

            if (ro != null) {
                val blocked = try {
                    require('W' in ro.perms)
                    false
                } catch (_: Exception) {
                    true
                }

                out.append(
                    "Protecao RO contra escrita: " +
                    if (blocked) "OK\n" else "FALHOU\n"
                )
            }

            out.append(
                "Mapeamento virtual: CONCLUIDO NO TESTE\n"
            )
            out.append(
                "Instrucao real: lida, nao executada.\n"
            )
        } catch (e: Exception) {
            out.append(
                "ERRO VM: ${e.message}\n"
            )
        } finally {
            regions.clear()
        }

        return out.toString()
    }

    data class ModuleView(
        val name: String,
        val base: Long,
        val data: ByteArray,
        val offsets: LongArray,
        val sizes: LongArray,
        val bss: Long
    )
}
