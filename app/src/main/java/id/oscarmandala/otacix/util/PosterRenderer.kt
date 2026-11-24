package id.oscarmandala.otacix.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import id.oscarmandala.otacix.R
import id.oscarmandala.otacix.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Membuat poster 1080x1620 bergaya liquid glass dari sebuah playlist (digambar langsung dengan Canvas). */
object PosterRenderer {
    private const val W = 1080f
    private const val H = 1620f
    private const val NAVY = 0xFF040B1E.toInt()
    private const val BLUE = 0xFF064A94.toInt()
    private const val BLUE_BRIGHT = 0xFF2B7BFF.toInt()
    private const val YELLOW = 0xFFFDC12B.toInt()

    private suspend fun load(ctx: Context, url: String): Bitmap? {
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(540).allowHardware(false).build())
        return ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }

    /** style: 0 = grid 3x3, 1 = tumpukan miring. lite = resolusi lebih kecil (hemat RAM). */
    suspend fun render(ctx: Context, title: String, tracks: List<Track>, style: Int, lite: Boolean): Bitmap =
        withContext(Dispatchers.Default) {
            val s = if (lite) 2f / 3f else 1f
            val bmp = Bitmap.createBitmap((W * s).toInt(), (H * s).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(s, s)

            val shown = tracks.take(if (style == 0) 9 else 5)
            val covers: List<Pair<Bitmap?, String>> = coroutineScope {
                shown.map { t -> async { load(ctx, t.cover) to t.cover } }.awaitAll()
            }

            drawBackground(c)
            drawHeader(ctx, c, title, tracks.size)
            if (style == 0) drawGrid(c, covers) else drawStack(c, covers)
            drawList(c, tracks, if (style == 0) 3 else 5, if (style == 0) 1400f else 1230f)
            drawFooter(c)
            bmp
        }

    private fun drawBackground(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(0xFF0B2F6B.toInt(), NAVY), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        fun blob(color: Int, cx: Float, cy: Float, r: Float) {
            p.shader = RadialGradient(cx, cy, r, color, 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, p)
        }
        blob(0x66FDC12B, W * 0.95f, 140f, 700f)
        blob(0x882B7BFF.toInt(), 80f, 520f, 760f)
        blob(0x77064A94, W * 0.8f, H * 0.95f, 800f)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, center: Boolean = false, maxW: Float = 920f) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = size; tp.color = color
        tp.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        val t = TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString()
        tp.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(t, x, y, tp)
    }

    private fun drawHeader(ctx: Context, c: Canvas, title: String, count: Int) {
        // kartu logo putih (seperti di aplikasi)
        val tile = RectF(W - 60f - 230f, 70f, W - 60f, 70f + 112f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        c.drawRoundRect(tile, 30f, 30f, bg)
        BitmapFactory.decodeResource(ctx.resources, R.drawable.logo)?.let { logo ->
            val dst = RectF(tile.left + 18f, tile.top + 12f, tile.right - 18f, tile.bottom - 12f)
            val sc = minOf(dst.width() / logo.width, dst.height() / logo.height)
            val w = logo.width * sc; val h = logo.height * sc
            val d = RectF(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2)
            c.drawBitmap(logo, null, d, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        text(c, "OTACIX", 60f, 120f, 56f, 0xFFFFFFFF.toInt(), bold = true)
        text(c, "by Oscar Mandala", 60f, 168f, 30f, YELLOW, bold = true)
        text(c, title, 60f, 290f, 92f, 0xFFFFFFFF.toInt(), bold = true)
        text(c, "$count lagu", 60f, 342f, 36f, 0xCCFFE08A.toInt())
    }

    private fun drawCover(c: Canvas, bmp: Bitmap?, url: String, r: RectF, radius: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        if (bmp == null) {
            fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, BLUE_BRIGHT, BLUE, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radius, radius, fill)
        } else {
            val crop = if (url.contains("ytimg.com")) 1.34f else 1f
            val sc = maxOf(r.width() / bmp.width, r.height() / bmp.height) * crop
            val m = Matrix()
            m.setScale(sc, sc)
            m.postTranslate(r.left + (r.width() - bmp.width * sc) / 2f, r.top + (r.height() - bmp.height * sc) / 2f)
            val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            sh.setLocalMatrix(m)
            fill.shader = sh
            c.drawRoundRect(r, radius, radius, fill)
        }
        // tepi kaca
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f
            shader = LinearGradient(r.left, r.top, r.left, r.bottom, 0xAAFFFFFF.toInt(), 0x22FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, radius, radius, edge)
    }

    private fun drawGrid(c: Canvas, covers: List<Pair<Bitmap?, String>>) {
        // panel kaca di belakang grid
        val panel = RectF(40f, 380f, W - 40f, 1360f)
        c.drawRoundRect(panel, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF })
        c.drawRoundRect(panel, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x55FFFFFF
        })
        val size = 296f; val gap = 28f
        val left = (W - (3 * size + 2 * gap)) / 2f
        for (i in 0 until 9) {
            val row = i / 3; val col = i % 3
            val x = left + col * (size + gap); val y = 410f + row * (size + gap)
            val (b, u) = covers.getOrNull(i) ?: (null to "")
            drawCover(c, b, u, RectF(x, y, x + size, y + size), 38f)
        }
    }

    private fun drawStack(c: Canvas, covers: List<Pair<Bitmap?, String>>) {
        val angles = floatArrayOf(-15f, 10f, -7f, 13f, 0f)
        val dx = floatArrayOf(-140f, 150f, -90f, 90f, 0f)
        val dy = floatArrayOf(-30f, 10f, 60f, 70f, 40f)
        val n = covers.size.coerceAtLeast(1)
        for (k in 0 until n) {
            val i = n - 1 - k                    // gambar dari belakang ke depan
            val (b, u) = covers.getOrNull(i) ?: (null to "")
            val size = if (i == 0) 600f else 480f
            c.save()
            c.translate(W / 2f + dx[i], 730f + dy[i])
            c.rotate(angles[i])
            drawCover(c, b, u, RectF(-size / 2, -size / 2, size / 2, size / 2), 52f)
            c.restore()
        }
    }

    private fun drawList(c: Canvas, tracks: List<Track>, max: Int, y0: Float) {
        tracks.take(max).forEachIndexed { i, t ->
            val y = y0 + i * 52f
            text(c, String.format("%02d", i + 1), 60f, y, 32f, YELLOW, bold = true)
            text(c, "${t.title} — ${t.artist}", 130f, y, 32f, 0xEEFFFFFF.toInt(), maxW = 880f)
        }
        if (tracks.size > max) text(c, "+ ${tracks.size - max} lagu lainnya", 130f, y0 + max * 52f, 28f, 0x99FFFFFF.toInt())
    }

    private fun drawFooter(c: Canvas) {
        text(c, "Dibuat dengan Otacix · by Oscar Mandala", W / 2f, H - 44f, 28f, 0x99FFFFFF.toInt(), center = true)
    }

    // ---- simpan & bagikan ----
    fun saveToGallery(ctx: Context, bmp: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "otacix-poster-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Otacix")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
        } catch (e: Exception) { false }
    }

    fun share(ctx: Context, bmp: Bitmap) {
        val dir = File(ctx.cacheDir, "posters").apply { mkdirs() }
        val f = File(dir, "otacix-poster.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Bagikan poster"))
    }
}
