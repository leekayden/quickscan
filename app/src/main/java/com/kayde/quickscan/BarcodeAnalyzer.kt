package com.kayde.quickscan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage

class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile
    var paused: Boolean = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (paused) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes ->
                for (code in codes) {
                    val text = code.rawValue
                    if (!text.isNullOrEmpty()) {
                        onDecoded(text)
                        break
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
