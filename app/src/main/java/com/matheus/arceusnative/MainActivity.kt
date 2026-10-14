package com.matheus.arceusnative

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.graphics.Canvas
import android.os.SystemClock
import android.widget.ImageView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.SurfaceView
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {
    companion object { init { System.loadLibrary("arceus_engine") } }
    external fun nativeDiagnostics(): String
    external fun nativeGpuTest(): String
    external fun nativeRenderSurface(surface: android.view.Surface): String
    external fun nativeAnimationTest(surface: android.view.Surface): String
    external fun nativeTriangleTest(surface: android.view.Surface): String
    private lateinit var status: TextView
    private lateinit var logoView: ImageView
    private lateinit var gifView: XciGifView
    @Volatile private var extractedMovie: Movie? = null

    private class XciGifView(context: android.content.Context) :
        View(context) {
        private var movie: Movie? = null
        private var started = 0L

        fun show(movieToPlay: Movie) {
            movie = movieToPlay
            started = SystemClock.uptimeMillis()
            visibility = VISIBLE
            requestLayout()
            invalidate()
        }

        fun clear() {
            movie = null
            visibility = GONE
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val m = movie
            val height = if (m != null && m.width() > 0) {
                (width.toFloat() * m.height() / m.width())
                    .toInt().coerceIn(1, 700)
            } else 1
            setMeasuredDimension(width, height)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val m = movie ?: return
            if (m.width() <= 0 || m.height() <= 0) return

            canvas.drawColor(Color.BLACK)
            val duration = if (m.duration() > 0) m.duration() else 1000
            val elapsed = SystemClock.uptimeMillis() - started
            m.setTime((elapsed % duration).toInt())

            val scale = minOf(
                width.toFloat() / m.width(),
                height.toFloat() / m.height()
            )
            canvas.save()
            canvas.translate(
                (width - m.width() * scale) / 2f,
                (height - m.height() * scale) / 2f
            )
            canvas.scale(scale, scale)
            m.draw(canvas, 0f, 0f)
            canvas.restore()
            postInvalidateDelayed(33)
        }
    }
    @Volatile private var extractedLogo: Bitmap? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            setBackgroundColor(Color.rgb(15, 20, 31))
        }
        val title = TextView(this).apply {
            text = "Arceus Native Engine v1.2"
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = "Nucleo C++ ARM64 e Vulkan experimental. Ainda nao executa jogos.\n\nTeste o motor ou selecione sua XCI."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 28, 0, 28)
        }
        val test = Button(this).apply {
            text = "Testar motor ARM64 / Vulkan"
            setOnClickListener {
                status.text = try { nativeDiagnostics() } catch (e: Exception) { "Erro nativo: ${e.message}" }
            }
        }
        val gpuTest = Button(this).apply {
            text = "Testar GPU Vulkan (comando real)"
            setOnClickListener {
                status.text = "Executando comandos na GPU..."
                Thread {
                    val report = try { nativeGpuTest() } catch (e: Throwable) { "Falha no teste GPU: ${e.message}" }
                    runOnUiThread { status.text = report }
                }.start()
            }
        }
        val render = Button(this).apply {
            text = "Testar renderizacao Vulkan na tela"
            setOnClickListener {
                val screen = SurfaceView(this@MainActivity)
                val resultText = TextView(this@MainActivity).apply {
                    text = "Preparando superficie Vulkan..."
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(24, 24, 24, 24)
                }
                val close = Button(this@MainActivity).apply {
                    text = "Voltar ao motor"
                    setOnClickListener { recreate() }
                }
                val scene = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(15, 20, 31))
                    addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(resultText)
                    addView(close)
                }
                setContentView(scene)
                screen.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Thread {
                            val result = try { nativeRenderSurface(holder.surface) }
                                catch (e: Throwable) { "Erro Vulkan: ${e.message}" }
                            runOnUiThread { resultText.text = result }
                        }.start()
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        }
        val animate = Button(this).apply {
            text = "Testar animacao Vulkan (30 quadros)"
            setOnClickListener {
                val screen = SurfaceView(this@MainActivity)
                val resultText = TextView(this@MainActivity).apply {
                    text = "Testando animacao..."
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(24, 24, 24, 24)
                }
                val close = Button(this@MainActivity).apply {
                    text = "Voltar ao motor"
                    setOnClickListener { recreate() }
                }
                val scene = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(15, 20, 31))
                    addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(resultText)
                    addView(close)
                }
                setContentView(scene)
                screen.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Thread {
                            val report = try { nativeAnimationTest(holder.surface) }
                                catch (e: Throwable) { "Erro: ${e.message}" }
                            runOnUiThread { resultText.text = report }
                        }.start()
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        }
        val choose = Button(this).apply {
            text = "Selecionar XCI"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, 100)
            }
        }
        val driver = Button(this).apply {
            text = "Turnip (em desenvolvimento)"
            setOnClickListener {
                status.text = "Driver atual: sistema.\n\nO carregamento de Turnip personalizado ainda nao foi implementado. Nenhum driver sera instalado automaticamente."
            }
        }
        val triangle = Button(this).apply {
            text = "Testar triangulo Vulkan (shaders)"
            setOnClickListener {
                val screen = SurfaceView(this@MainActivity)
                val resultText = TextView(this@MainActivity).apply {
                    text = "Criando pipeline Vulkan..."
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(24, 24, 24, 24)
                }
                val close = Button(this@MainActivity).apply {
                    text = "Voltar ao motor"
                    setOnClickListener { recreate() }
                }
                val scene = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(15, 20, 31))
                    addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(resultText)
                    addView(close)
                }
                setContentView(scene)
                screen.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Thread {
                            val report = try { nativeTriangleTest(holder.surface) }
                                catch (e: Throwable) { "Erro: ${e.message}" }
                            runOnUiThread { resultText.text = report }
                        }.start()
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        }
        layout.addView(title)
        layout.addView(test)
        layout.addView(gpuTest)
        layout.addView(render)
        layout.addView(animate)
        layout.addView(triangle)
        layout.addView(choose)
        layout.addView(driver)
        logoView = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        layout.addView(logoView, LinearLayout.LayoutParams(-1, -2))
        gifView = XciGifView(this)
        gifView.visibility = View.GONE
        layout.addView(gifView, LinearLayout.LayoutParams(-1, -2))
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    @Deprecated("Legacy result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            extractedLogo = null
            extractedMovie = null
            gifView.clear()
            logoView.setImageDrawable(null)
            logoView.visibility = View.GONE
            status.text = "Lendo XCI e procurando NintendoLogo.png..." 
            Thread {
                val result = try { inspect(uri) } catch (e: Exception) { "Erro: ${e.message}" }
                runOnUiThread {
                    status.text = result
                    val bitmap = extractedLogo
                    if (bitmap != null) {
                        logoView.setImageBitmap(bitmap)
                        logoView.visibility = View.VISIBLE
                    }
                    extractedMovie?.let { gifView.show(it) }
                }
            }.start()
        }
    }

    private fun inspect(uri: Uri): String {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: return "Arquivo indisponivel"
        pfd.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
                val channel = stream.channel
                val total = channel.size()

                fun readAt(pos: Long, n: Int): ByteArray {
                    if (pos < 0 || n < 0 || pos > total ||
                        n.toLong() > total - pos)
                        throw Exception("Leitura fora do arquivo")
                    val b = ByteBuffer.allocate(n)
                    var at = pos
                    while (b.hasRemaining()) {
                        val count = channel.read(b, at)
                        if (count <= 0)
                            throw Exception("Leitura incompleta em $at")
                        at += count
                    }
                    return b.array()
                }

                val header = readAt(0x100L, 0x38)
                if (String(header, 0, 4, Charsets.US_ASCII) != "HEAD")
                    return "Assinatura XCI HEAD nao encontrada"

                val root = ByteBuffer.wrap(header, 0x30, 8)
                    .order(ByteOrder.LITTLE_ENDIAN).long

                val out = StringBuilder()
                out.append("VISUALIZADOR XCI v1.2\n")
                out.append("ARCEUS NATIVE ENGINE v1.2\n")
                out.append("XCI reconhecida\n")
                out.append("Tamanho: %.2f GiB\n\n".format(
                    total.toDouble() / (1024.0 * 1024 * 1024)
                ))

                fun parseHfs(offset: Long, label: String, level: Int) {
                    if (level > 2) return
                    val h = readAt(offset, 16)
                    if (String(h, 0, 4, Charsets.US_ASCII) != "HFS0") {
                        out.append("$label: HFS0 nao encontrado\n")
                        return
                    }
                    val meta = ByteBuffer.wrap(h)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    val count = meta.getInt(4)
                    val namesSize = meta.getInt(8)

                    if (count !in 0..10000 ||
                        namesSize !in 0..1048576)
                        throw Exception("Tabela HFS0 invalida em $label: count=$count, namesSize=$namesSize, offset=$offset")

                    val entries = readAt(offset + 16, count * 64)
                    val names = readAt(
                        offset + 16 + count * 64L, namesSize
                    )
                    val dataStart = offset + 16 +
                        count * 64L + namesSize

                    out.append("$label: $count entradas\n")
                    var shown = 0
                    for (idx in 0 until count) {
                        val e = ByteBuffer.wrap(
                            entries, idx * 64, 64
                        ).order(ByteOrder.LITTLE_ENDIAN)
                        val relative = e.long
                        val size = e.long
                        val nameOffset = e.int
                        if (nameOffset !in names.indices ||
                            relative < 0 || size < 0) continue

                        var end = nameOffset
                        while (end < names.size &&
                            names[end] != 0.toByte()) end++

                        val name = String(
                            names, nameOffset, end - nameOffset,
                            Charsets.UTF_8
                        )
                        val absolute = dataStart + relative
                        if (absolute < dataStart ||
                            absolute > total ||
                            size > total - absolute) continue

                        if (label == "logo" &&
                            name == "NintendoLogo.png") {
                            if (size in 1L..2097152L) {
                                val png = readAt(absolute, size.toInt())
                                val bitmap = BitmapFactory.decodeByteArray(
                                    png, 0, png.size
                                )
                                if (bitmap != null) {
                                    extractedLogo = bitmap
                                    out.append(
                                        "NintendoLogo.png: IMAGEM CARREGADA!\n"
                                    )
                                } else {
                                    out.append(
                                        "NintendoLogo.png: formato nao reconhecido\n"
                                    )
                                }
                            } else {
                                out.append(
                                    "NintendoLogo.png: tamanho invalido\n"
                                )
                            }
                        }

                        if (label == "logo" &&
                            name == "StartupMovie.gif") {
                            if (size in 1L..2097152L) {
                                val bytes = readAt(absolute, size.toInt())
                                val movie = Movie.decodeByteArray(
                                    bytes, 0, bytes.size
                                )
                                if (movie != null &&
                                    movie.width() > 0 &&
                                    movie.height() > 0) {
                                    extractedMovie = movie
                                    out.append(
                                        "StartupMovie.gif: ANIMACAO CARREGADA!\n"
                                    )
                                } else {
                                    out.append(
                                        "StartupMovie.gif: falha na leitura\n"
                                    )
                                }
                            } else {
                                out.append(
                                    "StartupMovie.gif: tamanho invalido\n"
                                )
                            }
                        }

                        if (label == "secure" &&
                            name.endsWith(".nca") && size >= 0x400L) {
                            val magic = readAt(absolute + 0x200L, 4)
                            val hex = magic.joinToString("") {
                                "%02X".format(it.toInt() and 255)
                            }
                            val plain = String(
                                magic, Charsets.US_ASCII
                            )
                            val recognized = plain in listOf(
                                "NCA0", "NCA1", "NCA2", "NCA3"
                            )
                            out.append(
                                "  NCA header: " +
                                if (recognized) "$plain (legivel)\n"
                                else "$hex (criptografado ou desconhecido)\n"
                            )

                            out.append("  DIAGNOSTICO NCA v1.2\n")

                            val headerBytes = readAt(absolute, 0x400)
                            val digest = java.security.MessageDigest
                                .getInstance("SHA-256")
                                .digest(headerBytes)
                                .joinToString("") {
                                    "%02x".format(it.toInt() and 255)
                                }

                            out.append(
                                "  SHA256 cabecalho: $digest\n"
                            )
                            out.append(
                                "  Tamanho real: $size bytes\n"
                            )

                            if (recognized) {
                                val nca = ByteBuffer.wrap(headerBytes)
                                    .order(ByteOrder.LITTLE_ENDIAN)

                                val contentType =
                                    headerBytes[0x205].toInt() and 255
                                val cryptoType =
                                    headerBytes[0x206].toInt() and 255
                                val titleId = nca.getLong(0x210)
                                val declaredSize = nca.getLong(0x208)

                                val typeName = when (contentType) {
                                    0 -> "Program"
                                    1 -> "Meta"
                                    2 -> "Control"
                                    3 -> "Manual"
                                    4 -> "Data"
                                    5 -> "PublicData"
                                    else -> "Desconhecido"
                                }

                                val idHex = java.lang.Long
                                    .toUnsignedString(titleId, 16)
                                    .padStart(16, '0')
                                    .uppercase(java.util.Locale.US)

                                out.append(
                                    "  Tipo NCA: $typeName\n"
                                )
                                out.append(
                                    "  Title ID: $idHex\n"
                                )
                                out.append(
                                    "  Crypto type: $cryptoType\n"
                                )
                                out.append(
                                    "  Tamanho declarado: $declaredSize\n"
                                )

                                if (declaredSize != size) {
                                    out.append(
                                        "  Aviso: tamanhos diferentes\n"
                                    )
                                }
                            } else {
                                out.append(
                                    "  Cabecalho nao legivel. " +
                                    "Metadados internos indisponiveis " +
                                    "sem descriptografia.\n"
                                )
                            }
                        }

                        if (shown < 80) {
                            out.append("  $name: %.2f MiB\n".format(
                                size.toDouble() / (1024.0 * 1024)
                            ))
                            shown++
                        }

                        if (level == 0 &&
                            (name == "secure" ||
                             name == "normal" ||
                             name == "update" ||
                             name == "logo")) {
                            parseHfs(absolute, name, level + 1)
                        }
                    }
                    if (count > shown)
                        out.append("  (lista limitada)\n")
                }

                parseHfs(root, "Raiz", 0)
                out.append(
                    "\nLeitura de metadados apenas. " +
                    "NCA nao descriptografado. " +
                    "Codigo do jogo ainda nao executado."
                )
                return out.toString()
            }
        }
    }

}
