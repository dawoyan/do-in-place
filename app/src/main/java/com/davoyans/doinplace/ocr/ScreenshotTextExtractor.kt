package com.davoyans.doinplace.ocr

import android.content.Context
import android.net.Uri
import com.davoyans.doinplace.util.DiagLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

// Returns true only if ALL letters in the text are Latin (U+0000–U+024F: Basic Latin through Latin Extended-B).
// Rejects text containing any Cyrillic, Armenian, Arabic, CJK, etc. characters.
// ML Kit DEFAULT_OPTIONS is a Latin-script model; non-Latin input produces garbage output.
fun isLatinDominant(text: String): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return true
    return letters.all { it.code <= 0x024F }
}

class ScreenshotTextExtractor {
    suspend fun extractText(context: Context, imageUri: Uri): Result<String> = runCatching {
        DiagLog.d("OCR", "start uri=${imageUri.scheme}:${imageUri.host ?: ""}")
        val image = InputImage.fromFilePath(context, imageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val result = recognizer.process(image).await()
        val text = result.text
            .lines()
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (text.isBlank()) DiagLog.d("OCR", "no_text")
        else DiagLog.d("OCR", "success chars=${text.length}")
        text
    }
}
