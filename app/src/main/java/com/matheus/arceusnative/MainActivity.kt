package com.matheus.arceusnative

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 70, 36, 20); setBackgroundColor(Color.rgb(15, 20, 31)) }
        val title = TextView(this).apply { text = "Arceus Native Lab"; textSize = 27f; setTextColor(Color.WHITE) }
        status = TextView(this).apply { text = "Analisador XCI experimental. Não executa jogos.\n\nSelecione sua XCI para ler o cabeçalho e as partições sem extrair o arquivo."; textSize = 16f; setTextColor(Color.LTGRAY); setPadding(0, 30, 0, 30) }
        val choose = Button(this).apply { text = "Selecionar XCI"; setOnClickListener { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }; startActivityForResult(i, 100) } }
        layout.addView(title); layout.addView(status); layout.addView(choose)
        setContentView(layout)
    }
    @Deprecated("Legacy result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            status.text = "Analisando cabeçalho..."
            Thread {
                val result = try { inspect(uri) } catch (e: Exception) { "Erro: ${e.message}" }
                runOnUiThread { status.text = result }
            }.start()
        }
    }
    private fun inspect(uri: Uri): String {
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return "Arquivo indisponível"
        pfd.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
                val channel = stream.channel
                fun readAt(pos: Long, n: Int): ByteArray {
                    val b = ByteBuffer.allocate(n)
                    var at = pos
                    while (b.hasRemaining()) { val count = channel.read(b, at); if (count <= 0) throw Exception("Leitura incompleta em $at"); at += count }
                    return b.array()
                }
                val header = readAt(0x100, 0x38)
                if (String(header, 0, 4, Charsets.US_ASCII) != "HEAD") return "Assinatura HEAD não encontrada."
                val root = ByteBuffer.wrap(header, 0x30, 8).order(ByteOrder.LITTLE_ENDIAN).long
                if (root < 0 || root > channel.size() - 16) return "Offset raiz inválido"
                val hfs = readAt(root, 16)
                if (String(hfs, 0, 4, Charsets.US_ASCII) != "HFS0") return "HFS0 não encontrado no offset $root"
                val meta = ByteBuffer.wrap(hfs).order(ByteOrder.LITTLE_ENDIAN)
                val count = meta.getInt(4); val namesSize = meta.getInt(8)
                if (count !in 1..100 || namesSize !in 1..65536) return "Tabela de partições inválida"
                val entries = readAt(root + 16, count * 64)
                val names = readAt(root + 16 + count * 64L, namesSize)
                val out = StringBuilder("XCI reconhecida!\nTamanho: %.2f GiB\n\nPartições:\n".format(channel.size().toDouble() / (1024.0*1024*1024)))
                for (idx in 0 until count) {
                    val e = ByteBuffer.wrap(entries, idx * 64, 64).order(ByteOrder.LITTLE_ENDIAN)
                    e.long; val size = e.long; val nameOffset = e.int
                    if (nameOffset !in names.indices) continue
                    var end = nameOffset
                    while (end < names.size && names[end] != 0.toByte()) end++
                    val name = String(names, nameOffset, end - nameOffset, Charsets.UTF_8)
                    out.append("• $name: %.3f GiB\n".format(size.toDouble() / (1024.0*1024*1024)))
                }
                out.append("\nAnálise somente leitura. Execução nativa ainda não implementada.")
                return out.toString()
            }
        }
    }
}
