package com.kayde.quickscan

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var resultCard: View
    private lateinit var resultText: TextView
    private lateinit var btnOpen: TextView
    private lateinit var btnCopy: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var btnTorch: ImageButton

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var analyzer: BarcodeAnalyzer? = null
    private var scanner: com.google.mlkit.vision.barcode.BarcodeScanner? = null

    private var torchOn = false
    private var manualZoom = false
    private var permissionDenied = false
    private var openUrl: String? = null
    private var lastValue: String? = null
    private var lastAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        previewView = findViewById(R.id.preview)
        resultCard = findViewById(R.id.result_card)
        resultText = findViewById(R.id.result_text)
        btnOpen = findViewById(R.id.btn_open)
        btnCopy = findViewById(R.id.btn_copy)
        btnClose = findViewById(R.id.btn_close)
        btnTorch = findViewById(R.id.btn_torch)

        applySystemBarInsets()
        setupTouch()

        btnTorch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            btnTorch.alpha = if (torchOn) 1f else 0.7f
        }
        btnOpen.setOnClickListener {
            val url = openUrl ?: return@setOnClickListener
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
        }
        btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QuickScan", resultText.text.toString()))
            haptic()
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener { resumeScanning() }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), RC_CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val newScanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .setZoomSuggestionOptions(
                            ZoomSuggestionOptions.Builder { ratio -> applyZoomSuggestion(ratio) }
                                .setMaxSupportedZoomRatio(4f)
                                .build()
                        )
                        .enableAllPotentialBarcodes()
                        .build()
                )
                scanner = newScanner

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val newAnalyzer = BarcodeAnalyzer(newScanner) { value -> onDecoded(value) }
                analyzer = newAnalyzer
                analysis.setAnalyzer(analyzerExecutor, newAnalyzer)

                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyZoomSuggestion(ratio: Float): Boolean {
        val cam = camera ?: return false
        if (manualZoom) return false
        val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: ratio
        cam.cameraControl.setZoomRatio(ratio.coerceAtMost(max))
        return true
    }

    private fun onDecoded(value: String) {
        val now = SystemClock.elapsedRealtime()
        if (value == lastValue && now - lastAtMs < DUPLICATE_IGNORE_MS) return
        lastValue = value
        lastAtMs = now

        permissionDenied = false
        openUrl = normalizeUrl(value)
        analyzer?.paused = true

        resultText.text = value
        btnOpen.visibility = if (openUrl != null) View.VISIBLE else View.GONE
        btnCopy.visibility = View.VISIBLE
        btnClose.visibility = View.VISIBLE
        resultCard.alpha = 0f
        resultCard.translationY = 80f
        resultCard.visibility = View.VISIBLE
        resultCard.animate().alpha(1f).translationY(0f).setDuration(150).start()
        haptic()
    }

    private fun resumeScanning() {
        permissionDenied = false
        resultCard.visibility = View.GONE
        analyzer?.paused = false
        manualZoom = false
        lastValue = null
        camera?.cameraControl?.setZoomRatio(1f)
    }

    private fun normalizeUrl(raw: String): String? {
        val candidate = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val uri = Uri.parse(candidate)
        val scheme = uri.scheme?.lowercase()
        return if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) candidate else null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    permissionDenied -> requestPermissions(arrayOf(Manifest.permission.CAMERA), RC_CAMERA)
                    resultCard.visibility == View.VISIBLE -> resumeScanning()
                    else -> focusAt(e.x, e.y)
                }
                return true
            }
        })
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                manualZoom = true
                val state = cam.cameraInfo.zoomState.value
                val current = state?.zoomRatio ?: 1f
                val max = state?.maxZoomRatio ?: 1f
                cam.cameraControl.setZoomRatio((current * detector.scaleFactor).coerceIn(1f, max))
                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            if (!scale.isInProgress) gesture.onTouchEvent(event)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).build()
        )
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                top = bars.top
                bottom = bars.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            val dp = resources.displayMetrics.density
            (btnTorch.layoutParams as FrameLayout.LayoutParams).topMargin = (24 * dp).toInt() + top
            (resultCard.layoutParams as FrameLayout.LayoutParams).bottomMargin = (24 * dp).toInt() + bottom
            root.requestLayout()
            insets
        }
    }

    private fun haptic() {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS
        root.performHapticFeedback(constant)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RC_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            resultCard.visibility = View.GONE
            startCamera()
        } else {
            permissionDenied = true
            btnOpen.visibility = View.GONE
            btnCopy.visibility = View.GONE
            btnClose.visibility = View.GONE
            resultCard.alpha = 0f
            resultCard.translationY = 80f
            resultCard.visibility = View.VISIBLE
            resultCard.animate().alpha(1f).translationY(0f).setDuration(150).start()
            resultText.text = getString(R.string.camera_needed)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (resultCard.visibility == View.VISIBLE) resumeScanning() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
        scanner?.close()
    }

    companion object {
        private const val RC_CAMERA = 1
        private const val DUPLICATE_IGNORE_MS = 2000L
    }
}
