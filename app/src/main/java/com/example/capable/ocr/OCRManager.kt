package com.example.capable.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * OCR Manager for text recognition using ML Kit
 * Triggered by double-tap gesture
 */
class OCRManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    
    fun processImage(
        bitmap: Bitmap, 
        onResult: (String) -> Unit, 
        onError: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                Log.d(TAG, "OCR Result: ${text.take(100)}...")
                onResult(text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed: ${e.message}")
                onError(e)
            }
    }
    
    fun close() {
        recognizer.close()
    }
    
    companion object {
        private const val TAG = "OCRManager"
    }
}
