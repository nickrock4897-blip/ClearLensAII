package com.clearlens.ai

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var originalPreview: ImageView
    private lateinit var enhancedPreview: ImageView
    private lateinit var strengthLabel: TextView
    private lateinit var saveImageButton: Button
    private lateinit var enhanceVideoButton: Button
    private lateinit var enhanceImageButton: Button
    private var selectedVideoUri: Uri? = null
    private var originalBitmap: Bitmap? = null
    private var enhancedBitmap: Bitmap? = null
    private var intensity = 45

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedVideoUri = null
            enhancedBitmap = null
            saveImageButton.isEnabled = false
            enhanceVideoButton.isEnabled = false
            enhanceImageButton.isEnabled = false
            status.text = "Bild wird geladen …"
            progress.visibility = View.VISIBLE
            thread {
                try {
                    val bitmap = decodeScaledBitmap(uri)
                    runOnUiThread {
                        originalBitmap = bitmap
                        originalPreview.setImageBitmap(bitmap)
                        enhancedPreview.setImageDrawable(null)
                        status.text = "Bild bereit. Tippe auf „Bild verbessern“."
                        progress.visibility = View.GONE
                        enhanceImageButton.isEnabled = true
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        progress.visibility = View.GONE
                        status.text = "Das Bild konnte nicht geöffnet werden."
                        toast("Bild konnte nicht geladen werden.")
                    }
                }
            }
        }
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            originalBitmap = null
            enhancedBitmap = null
            originalPreview.setImageDrawable(null)
            enhancedPreview.setImageDrawable(null)
            saveImageButton.isEnabled = false
            enhanceImageButton.isEnabled = false
            enhanceVideoButton.isEnabled = true
            status.text = "Video ausgewählt. Du kannst es jetzt als verbessertes MP4 exportieren."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    private fun buildInterface() {
        val page = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(10, 17, 32))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        page.addView(column)
        column.addView(textView("ClearLens AI", 30f, Color.rgb(105, 220, 190), true))
        column.addView(textView("Klarere Bilder und Videos – direkt auf deinem Handy.", 15f, Color.LTGRAY).apply {
            setPadding(0, dp(6), 0, dp(18))
        })
        column.addView(makeButton("Foto auswählen", true) {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        column.addView(makeButton("Video auswählen", false) {
            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        })
        strengthLabel = textView("Verbesserungsstärke: 45 %", 14f, Color.WHITE).apply {
            setPadding(0, dp(14), 0, dp(3))
        }
        column.addView(strengthLabel)
        column.addView(SeekBar(this).apply {
            max = 100
            progress = intensity
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(105, 220, 190))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(105, 220, 190))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    intensity = value
                    strengthLabel.text = "Verbesserungsstärke: " + value + " %"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        originalPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Originalbild"
            setBackgroundColor(Color.rgb(23, 32, 49))
        }
        enhancedPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Verbessertes Bild"
            setBackgroundColor(Color.rgb(23, 32, 49))
        }
        val originalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        originalBox.addView(textView("ORIGINAL", 12f, Color.LTGRAY))
        originalBox.addView(originalPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)).apply {
            setMargins(dp(3), dp(8), dp(3), dp(4))
        })
        val enhancedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        enhancedBox.addView(textView("VERBESSERT", 12f, Color.rgb(105, 220, 190)))
        enhancedBox.addView(enhancedPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)).apply {
            setMargins(dp(3), dp(8), dp(3), dp(4))
        })
        previewRow.addView(originalBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        previewRow.addView(enhancedBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(previewRow)

        enhanceImageButton = makeButton("Bild verbessern", true) { enhanceImage() }
        enhanceImageButton.isEnabled = false
        column.addView(enhanceImageButton)
        saveImageButton = makeButton("Verbessertes Bild speichern", false) { saveEnhancedImage() }
        saveImageButton.isEnabled = false
        column.addView(saveImageButton)
        enhanceVideoButton = makeButton("Video verbessern & MP4 speichern", true) { enhanceVideo() }
        enhanceVideoButton.isEnabled = false
        column.addView(enhanceVideoButton)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progress)
        status = textView("Wähle ein Foto oder Video aus, um zu starten.", 14f, Color.WHITE).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        column.addView(status)
        column.addView(textView(
            "Hinweis: ClearLens verbessert Kontrast und Farben. Verdeckte Details lassen sich nicht sicher wiederherstellen; echte Feuchtigkeit in der Kameralinse kann die App nicht reparieren.",
            12f, Color.LTGRAY
        ).apply { setPadding(0, dp(12), 0, 0) })
        setContentView(page)
    }

    private fun textView(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) Color.rgb(8, 22, 32) else Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (primary) Color.rgb(105, 220, 190) else Color.rgb(38, 51, 72)
            )
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(5), 0, dp(5)) }
        }
    }

    private fun enhanceImage() {
        val source = originalBitmap ?: return toast("Wähle zuerst ein Bild aus.")
        progress.visibility = View.VISIBLE
        enhanceImageButton.isEnabled = false
        saveImageButton.isEnabled = false
        status.text = "Bild wird verbessert …"
        val amount = intensity
        thread {
            try {
                val result = improveBitmap(source, amount)
                runOnUiThread {
                    enhancedBitmap = result
                    enhancedPreview.setImageBitmap(result)
                    progress.visibility = View.GONE
                    status.text = "Fertig. Vergleiche Original und Ergebnis."
                    enhanceImageButton.isEnabled = true
                    saveImageButton.isEnabled = true
                }
            } catch (_: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    enhanceImageButton.isEnabled = true
                    status.text = "Die Bildverbesserung ist fehlgeschlagen."
                    toast("Bild konnte nicht verbessert werden.")
                }
            }
        }
    }

    private fun improveBitmap(source: Bitmap, strength: Int): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)
        val amount = strength.coerceIn(0, 100) / 100f
        val contrast = 1f + amount * 0.48f
        val saturation = 1f + amount * 0.28f
        val brightness = amount * 4f
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color).toFloat()
            val g = Color.green(color).toFloat()
            val b = Color.blue(color).toFloat()
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val sr = lum + (r - lum) * saturation
            val sg = lum + (g - lum) * saturation
            val sb = lum + (b - lum) * saturation
            val nr = ((sr - 127.5f) * contrast + 127.5f + brightness).toInt().coerceIn(0, 255)
            val ng = ((sg - 127.5f) * contrast + 127.5f + brightness).toInt().coerceIn(0, 255)
            val nb = ((sb - 127.5f) * contrast + 127.5f + brightness).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(color), nr, ng, nb)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun decodeScaledBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Could not open image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Could not decode image")
    }

    private fun saveEnhancedImage() {
        val bitmap = enhancedBitmap ?: return toast("Verbessere zuerst ein Bild.")
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ClearLens_" + System.currentTimeMillis() + ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClearLens AI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create media entry")
            contentResolver.openOutputStream(uri)?.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) throw IOException("JPEG failed")
            } ?: throw IOException("Could not open output")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            status.text = "Bild gespeichert unter Bilder/ClearLens AI."
            toast("Bild gespeichert.")
        } catch (_: Exception) {
            status.text = "Bild konnte nicht gespeichert werden."
            toast("Speichern fehlgeschlagen.")
        }
    }

    @OptIn(UnstableApi::class)
    private fun enhanceVideo() {
        val uri = selectedVideoUri ?: return toast("Wähle zuerst ein Video aus.")
        val outputFile = File(cacheDir, "clearlens_" + System.currentTimeMillis() + ".mp4")
        progress.visibility = View.VISIBLE
        enhanceVideoButton.isEnabled = false
        status.text = "Video wird verarbeitet. Das kann eine Weile dauern …"
        try {
            val effects = Effects(
                emptyList(),
                listOf(Contrast((intensity / 100f) * 0.30f), Brightness((intensity / 100f) * 0.05f))
            )
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
            val transformer = Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult
                    ) {
                        thread {
                            try {
                                copyVideoToGallery(outputFile)
                                runOnUiThread {
                                    progress.visibility = View.GONE
                                    enhanceVideoButton.isEnabled = true
                                    status.text = "Video als MP4 unter Filme/ClearLens AI gespeichert."
                                    toast("Verbessertes MP4 gespeichert.")
                                    outputFile.delete()
                                }
                            } catch (_: Exception) {
                                runOnUiThread {
                                    progress.visibility = View.GONE
                                    enhanceVideoButton.isEnabled = true
                                    status.text = "Video exportiert, aber Speichern in der Galerie ist fehlgeschlagen."
                                    toast("Speichern des Videos fehlgeschlagen.")
                                }
                            }
                        }
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        runOnUiThread {
                            progress.visibility = View.GONE
                            enhanceVideoButton.isEnabled = true
                            status.text = "Videoexport fehlgeschlagen: " + exportException.errorCodeName
                            outputFile.delete()
                            toast("Video konnte nicht exportiert werden.")
                        }
                    }
                }).build()
            transformer.start(edited, outputFile.absolutePath)
        } catch (e: Exception) {
            progress.visibility = View.GONE
            enhanceVideoButton.isEnabled = true
            status.text = "Videoexport konnte nicht starten: " + (e.message ?: "unbekannter Fehler")
            outputFile.delete()
            toast("Videoexport konnte nicht gestartet werden.")
        }
    }

    private fun copyVideoToGallery(file: File): Uri {
        if (!file.exists() || file.length() == 0L) throw IOException("Empty video output")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ClearLens_" + System.currentTimeMillis() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClearLens AI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create video entry")
        contentResolver.openOutputStream(uri)?.use { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
        } ?: throw IOException("Could not open video output")
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
        return uri
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
