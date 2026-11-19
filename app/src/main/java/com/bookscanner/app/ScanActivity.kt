package com.bookscanner.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var autoBtn: Button
    private var imageCapture: ImageCapture? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var autoOn = true
    @Volatile private var capturing = false
    private var lastAnalysis = 0L
    private var goodCount = 0
    private var lastRect: RectF? = null
    private var lastMsg = ""
    private var lastMsgTime = 0L

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            status.text = "Camera permission denied."
            Speaker.say("Camera permission is needed to scan pages.")
        }
    }

    private fun say(m: String) { if (Prefs.voice(this)) Speaker.say(m) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        autoOn = Prefs.auto(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.BLACK)
        previewView = PreviewView(this)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.contentDescription = "Camera preview"
        col.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        status = uiLabel("Pages: ${Store.size()}. Point the camera at a page.", 18f)
        col.addView(status)
        col.addView(uiSpinner("Reading mode", listOf("Reader: Offline (ML Kit)", "Reader: Online (Gemini)"), if (Prefs.online(this)) 1 else 0) {
            Prefs.setOnline(this, it == 1)
            say(if (it == 1) "Online reader selected" else "Offline reader selected")
        })
        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(uiButton("Capture Now", true) { capture() })
        autoBtn = uiButton(autoLabel(), true) {
            autoOn = !autoOn
            Prefs.setAuto(this, autoOn)
            autoBtn.text = autoLabel()
            say(if (autoOn) "Auto capture on" else "Auto capture off")
        }
        r1.addView(autoBtn)
        col.addView(r1)
        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.addView(uiButton("Delete Last Page", true) {
            if (Store.removeLast()) { status.text = "Pages: ${Store.size()}"; say("Last page deleted. ${Store.size()} pages.") }
            else say("No pages to delete")
        })
        r2.addView(uiButton("Finish and Save", true) {
            startActivity(Intent(this, ReviewActivity::class.java))
            finish()
        })
        col.addView(r2)
        setContentView(col)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else perm.launch(Manifest.permission.CAMERA)
    }

    private fun autoLabel() = if (autoOn) "Auto Capture: ON" else "Auto Capture: OFF"

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            imageCapture = ic
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(720, 960))
                .build()
            analysis.setAnalyzer(exec) { proxy ->
                val now = SystemClock.elapsedRealtime()
                val media = proxy.image
                if (capturing || now - lastAnalysis < 700 || media == null) {
                    proxy.close()
                } else {
                    lastAnalysis = now
                    val rot = proxy.imageInfo.rotationDegrees
                    val w = if (rot % 180 == 0) proxy.width else proxy.height
                    val h = if (rot % 180 == 0) proxy.height else proxy.width
                    Ocr.recognizer.process(InputImage.fromMediaImage(media, rot))
                        .addOnSuccessListener { onFrame(it, w, h) }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic, analysis)
                say("Camera ready. Point the camera at a page.")
            } catch (e: Exception) {
                status.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun guide(m: String) {
        val now = SystemClock.elapsedRealtime()
        if (m != lastMsg || now - lastMsgTime > 4000) {
            lastMsg = m
            lastMsgTime = now
            status.text = m + "  (Pages: ${Store.size()})"
            say(m)
        }
    }

    private fun onFrame(res: Text, w: Int, h: Int) {
        if (capturing) return
        val boxes = res.textBlocks.mapNotNull { it.boundingBox }
        if (boxes.isEmpty() || res.text.length < 15) {
            goodCount = 0
            lastRect = null
            guide("No page found. Point the camera at the page.")
            return
        }
        val l = boxes.minOf { it.left }.toFloat()
        val t = boxes.minOf { it.top }.toFloat()
        val r = boxes.maxOf { it.right }.toFloat()
        val b = boxes.maxOf { it.bottom }.toFloat()
        val mx = w * 0.03f
        val my = h * 0.03f
        val edges = ArrayList<String>()
        if (l < mx) edges.add("left")
        if (r > w - mx) edges.add("right")
        if (t < my) edges.add("top")
        if (b > h - my) edges.add("bottom")
        val area = (r - l) * (b - t) / (w * h)
        if (edges.size >= 2) {
            goodCount = 0
            guide("Move the phone away a little")
        } else if (edges.size == 1) {
            goodCount = 0
            guide(when (edges[0]) {
                "left" -> "Move the phone a little to the left"
                "right" -> "Move the phone a little to the right"
                "top" -> "Move the phone a little up"
                else -> "Move the phone a little down"
            })
        } else if (area < 0.30f) {
            goodCount = 0
            guide("Move the phone closer")
        } else {
            val cur = RectF(l / w, t / h, r / w, b / h)
            val prev = lastRect
            lastRect = cur
            val moved = prev != null && (Math.abs(prev.left - cur.left) > 0.06f || Math.abs(prev.top - cur.top) > 0.06f ||
                Math.abs(prev.right - cur.right) > 0.06f || Math.abs(prev.bottom - cur.bottom) > 0.06f)
            goodCount = if (moved || prev == null) 1 else goodCount + 1
            if (goodCount == 1) guide("Good. Hold steady")
            if (goodCount >= 3 && autoOn) capture()
        }
    }

    private fun capture() {
        val ic = imageCapture ?: return
        if (capturing) return
        capturing = true
        goodCount = 0
        val file = Store.newFile()
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch {
                        val n = withContext(Dispatchers.IO) { Importer.addFromFile(applicationContext, file) }
                        status.text = "Page $n captured"
                        say("Page $n captured. Turn the page.")
                        delay(3500)
                        lastMsg = ""
                        capturing = false
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    say("Capture failed. Try again.")
                }
            })
    }
}
