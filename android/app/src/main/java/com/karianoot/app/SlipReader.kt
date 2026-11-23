package com.karianoot.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import com.karianoot.engine.SlipText
import java.io.File

/**
 * "צילום טופס": the text of a slip screenshot, read on the phone by Tesseract (Hebrew + English, tessdata_fast).
 * Tested on rendered slips with the same two-pass recipe on the CLI (ocr-probe.yml, tools/release/slip_merge.py).
 * The picture is made grey, enlarged when small, and inverted when the app is dark (Tesseract reads dark on light).
 */
object SlipReader {
    fun read(ctx: Context, uri: Uri, modelDir: File): String {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("לא הצלחתי לפתוח את התמונה")
        val prepared = prepare(src)
        if (prepared !== src) src.recycle()
        try {
            // A Hebrew pass for the words, then a digits-only pass for the numbers (Hebrew models read digits as letters);
            // SlipText.merge puts each number on the line it sits on.
            val heb = pass(modelDir, prepared, "heb", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, null)
            val dig = pass(modelDir, prepared, "eng", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, "0123456789.,X")
            return SlipText.merge(SlipText.hocrWords(heb), SlipText.hocrWords(dig))
        } finally {
            prepared.recycle()
        }
    }

    /** One Tesseract pass -> its hOCR (words with boxes and confidence). Tesseract wants the folder that holds "tessdata/". */
    private fun pass(modelDir: File, b: Bitmap, lang: String, psm: Int, whitelist: String?): String {
        val api = TessBaseAPI()
        try {
            check(api.init(modelDir.path, lang)) { "מנוע זיהוי הטקסט לא נטען" }
            api.setPageSegMode(psm)
            if (whitelist != null) api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, whitelist)
            api.setImage(b)
            return api.getHOCRText(0) ?: ""
        } finally {
            api.recycle()
        }
    }

    private fun prepare(b: Bitmap): Bitmap {
        val scale = when { b.width < 900 -> 2f; b.width > 2400 -> 2400f / b.width; else -> 1f }
        val w = (b.width * scale).toInt(); val h = (b.height * scale).toInt()
        // Mean brightness on a coarse grid: a dark app screenshot gets inverted.
        var sum = 0.0; var n = 0
        for (y in 0 until b.height step maxOf(1, b.height / 40)) for (x in 0 until b.width step maxOf(1, b.width / 40)) {
            val c = b.getPixel(x, y); sum += (0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)) / 255; n++
        }
        val dark = n > 0 && sum / n < 0.45
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = ColorMatrix().apply { setSaturation(0f) }
        if (dark) m.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(b, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }
}
