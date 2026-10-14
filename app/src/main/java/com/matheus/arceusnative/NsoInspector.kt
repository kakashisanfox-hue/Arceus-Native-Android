package com.matheus.arceusnative

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reconhecedor estrutural NSO0.
 * Nao executa codigo e nao descomprime segmentos.
 */
object NsoInspector {
    fun inspectHeader(data: ByteArray): String {
        if (data.size < 0x70) {
            return "NSO: cabecalho incompleto."
        }

        val magic = String(
            data, 0, 4, Charsets.US_ASCII
        )
        if (magic != "NSO0") {
            return "NSO: assinatura NSO0 ausente."
        }

        val b = ByteBuffer.wrap(data)
            .order(ByteOrder.LITTLE_ENDIAN)

        val textOffset = b.getInt(0x10)
        val textMemory = b.getInt(0x14)
        val textSize = b.getInt(0x18)

        val roOffset = b.getInt(0x20)
        val roMemory = b.getInt(0x24)
        val roSize = b.getInt(0x28)

        val dataOffset = b.getInt(0x30)
        val dataMemory = b.getInt(0x34)
        val dataSize = b.getInt(0x38)

        return buildString {
            append("NSO0 reconhecido.\n")
            append(
                "TEXT: arquivo=$textOffset " +
                "memoria=$textMemory bytes=$textSize\n"
            )
            append(
                "RO: arquivo=$roOffset " +
                "memoria=$roMemory bytes=$roSize\n"
            )
            append(
                "DATA: arquivo=$dataOffset " +
                "memoria=$dataMemory bytes=$dataSize\n"
            )
            append(
                "Descompressao e execucao: pendentes.\n"
            )
        }
    }
}
