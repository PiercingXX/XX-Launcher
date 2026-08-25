package com.piercingxx.xxlauncher.theme

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.piercingxx.xxlauncher.R
import java.io.File
import java.util.Locale

object LauncherFont {
    const val SYSTEM = "system"
    const val MONOSPACE = "monospace"
    const val SPACE_MONO = "space_mono"
    const val JETBRAINS_MONO = "jetbrains_mono"
    const val JETBRAINS_MONO_NERD = "jetbrains_mono_nerd"
    const val CUSTOM = "custom"

    val ALL = listOf(SYSTEM, MONOSPACE, SPACE_MONO, JETBRAINS_MONO, JETBRAINS_MONO_NERD, CUSTOM)

    fun isValid(key: String): Boolean = key in ALL
}

private val typefaceCache = mutableMapOf<String, Typeface>()
private const val CUSTOM_FONT_FILE_NAME = "launcher_custom_font"

sealed interface FontImportResult {
    data class Success(val displayName: String) : FontImportResult
    data object InvalidExtension : FontImportResult
    data object UnreadableFile : FontImportResult
    data object InvalidFontData : FontImportResult
}

fun Context.getLauncherTypeface(fontKey: String): Typeface {
    val key = fontKey.takeIf(LauncherFont::isValid) ?: LauncherFont.JETBRAINS_MONO_NERD
    return typefaceCache.getOrPut(key) {
        when (key) {
            LauncherFont.SPACE_MONO ->
                ResourcesCompat.getFont(this, R.font.space_mono_regular) ?: Typeface.MONOSPACE

            LauncherFont.JETBRAINS_MONO ->
                ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE

            LauncherFont.JETBRAINS_MONO_NERD ->
                ResourcesCompat.getFont(this, R.font.jetbrains_mono_nerd_regular)
                    ?: Typeface.MONOSPACE

            LauncherFont.CUSTOM -> loadCustomTypeface()

            LauncherFont.SYSTEM -> Typeface.create("sans-serif-light", Typeface.NORMAL)

            else -> Typeface.MONOSPACE
        }
    }
}

/** Recursively applies the chosen font to a view tree, keeping per-view style. */
fun View.applyLauncherFont(fontKey: String) {
    applyTypeface(this, context.getLauncherTypeface(fontKey))
}

/** Fonts apply to every surface including dialogs. */
fun Dialog.applyLauncherFont(fontKey: String) {
    window?.decorView?.applyLauncherFont(fontKey)
}

fun clearFontCache(fontKey: String? = null) {
    if (fontKey == null) typefaceCache.clear() else typefaceCache.remove(fontKey)
}

fun Context.importCustomFont(uri: Uri): FontImportResult {
    val displayName = queryFontDisplayName(uri).ifBlank { "custom-font.ttf" }
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.US)
    if (extension !in setOf("ttf", "otf")) return FontImportResult.InvalidExtension

    val importFile = File(cacheDir, "$CUSTOM_FONT_FILE_NAME.$extension")
    val finalFile = File(filesDir, CUSTOM_FONT_FILE_NAME)

    return runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            importFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return FontImportResult.UnreadableFile

        if (runCatching { Typeface.createFromFile(importFile) }.isFailure) {
            importFile.delete()
            return FontImportResult.InvalidFontData
        }

        importFile.copyTo(finalFile, overwrite = true)
        importFile.delete()
        clearFontCache(LauncherFont.CUSTOM)
        FontImportResult.Success(displayName)
    }.getOrElse {
        importFile.delete()
        FontImportResult.UnreadableFile
    }
}

private fun Context.loadCustomTypeface(): Typeface {
    val file = File(filesDir, CUSTOM_FONT_FILE_NAME)
    if (!file.exists()) return Typeface.MONOSPACE
    return runCatching { Typeface.createFromFile(file) }.getOrDefault(Typeface.MONOSPACE)
}

private fun Context.queryFontDisplayName(uri: Uri): String = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex).orEmpty() else ""
        }.orEmpty()
}.getOrDefault("")

private fun applyTypeface(view: View, typeface: Typeface) {
    when (view) {
        is TextView -> {
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = Typeface.create(typeface, style)
        }

        is ViewGroup -> {
            for (index in 0 until view.childCount) {
                applyTypeface(view.getChildAt(index), typeface)
            }
        }
    }
}
