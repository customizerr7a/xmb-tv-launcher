package com.retro.xmblauncher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.ui.graphics.Color
import java.io.File

enum class ThemePreset(
    val id: String,
    val title: String,
    val description: String,
    val primaryAccent: Color,
    val glowColor: Color,
    val backgroundColors: List<Color>
) {
    TV_CLASSIC(
        id = "tv_classic",
        title = "Классический Синий",
        description = "Фирменный глубокий синий стиль Smart TV",
        primaryAccent = Color(0xFF0070D1),
        glowColor = Color(0xFF29B6F6),
        backgroundColors = listOf(
            Color(0xFF001026),
            Color(0xFF00224D),
            Color(0xFF003791),
            Color(0xFF001738)
        )
    ),
    TV_MIDNIGHT(
        id = "tv_midnight",
        title = "Тёмный Графит",
        description = "Тёмный фон с бирюзовым акцентом (минимум нагрузки на ТВ)",
        primaryAccent = Color(0xFF00E5FF),
        glowColor = Color(0xFF00E5FF),
        backgroundColors = listOf(
            Color(0xFF06080C),
            Color(0xFF0D1117),
            Color(0xFF161B22),
            Color(0xFF080B0F)
        )
    ),
    TV_SLATE(
        id = "tv_slate",
        title = "Серый Минимал",
        description = "Сдержанный нейтральный серый стиль",
        primaryAccent = Color(0xFFE0E0E0),
        glowColor = Color(0xFF90CAF9),
        backgroundColors = listOf(
            Color(0xFF16181C),
            Color(0xFF22262C),
            Color(0xFF2C323B),
            Color(0xFF181B20)
        )
    ),
    TV_NEON(
        id = "tv_neon",
        title = "Неоновый Закат",
        description = "Глубокий индиго с яркими неоновыми оттенками",
        primaryAccent = Color(0xFFFF4081),
        glowColor = Color(0xFF00E5FF),
        backgroundColors = listOf(
            Color(0xFF0A0612),
            Color(0xFF160E29),
            Color(0xFF241442),
            Color(0xFF0E081A)
        )
    ),
    TV_EMERALD(
        id = "tv_emerald",
        title = "Изумрудный Лес",
        description = "Спокойный мягкий зелёно-хвойный градиент",
        primaryAccent = Color(0xFF00E676),
        glowColor = Color(0xFF69F0AE),
        backgroundColors = listOf(
            Color(0xFF041210),
            Color(0xFF0A2420),
            Color(0xFF133933),
            Color(0xFF061614)
        )
    )
}

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val displayName: String
)

object WallpaperManager {
    private const val PREFS_NAME = "tv_launcher_prefs"
    private const val KEY_THEME = "selected_theme_id"
    private const val KEY_CUSTOM_PATH = "custom_wallpaper_path"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedTheme(context: Context): ThemePreset {
        val themeId = getPrefs(context).getString(KEY_THEME, ThemePreset.TV_CLASSIC.id)
        return ThemePreset.values().firstOrNull { it.id == themeId } ?: ThemePreset.TV_CLASSIC
    }

    fun setSelectedTheme(context: Context, preset: ThemePreset) {
        getPrefs(context).edit().putString(KEY_THEME, preset.id).apply()
    }

    fun getCustomWallpaperPath(context: Context): String? {
        val path = getPrefs(context).getString(KEY_CUSTOM_PATH, null)
        if (path != null && File(path).exists()) {
            return path
        }
        return null
    }

    fun setCustomWallpaperPath(context: Context, path: String?) {
        getPrefs(context).edit().putString(KEY_CUSTOM_PATH, path).apply()
    }

    // Quick root shortcuts for easy TV remote navigation
    fun getQuickRoots(): List<Pair<String, File>> {
        val roots = mutableListOf<Pair<String, File>>()
        roots.add("Память ТВ" to File("/sdcard"))
        roots.add("Загрузки" to File("/sdcard/Download"))
        roots.add("Картинки" to File("/sdcard/Pictures"))
        
        // USB storage drives on Android TV
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            storageDir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "emulated" && f.name != "self") {
                    roots.add("USB (${f.name})" to f)
                }
            }
        }
        return roots
    }

    // Browse any directory for subfolders and images (PNG, JPG, JPEG, WEBP)
    fun browseDirectory(dir: File): List<FileEntry> {
        val supportedExtensions = setOf("jpg", "jpeg", "png", "webp")
        val result = mutableListOf<FileEntry>()

        if (!dir.exists() || !dir.isDirectory) return result

        val parent = dir.parentFile
        if (parent != null && parent.canRead() && dir.absolutePath != "/" && dir.absolutePath != "/sdcard") {
            result.add(FileEntry(parent, isDirectory = true, displayName = ".. (Назад на уровень выше)"))
        }

        val allFiles = dir.listFiles() ?: return result
        
        // Add directories first
        val directories = allFiles
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        for (d in directories) {
            result.add(FileEntry(d, isDirectory = true, displayName = d.name))
        }

        // Add supported image files
        val imageFiles = allFiles
            .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
            .sortedBy { it.name.lowercase() }
        for (f in imageFiles) {
            result.add(FileEntry(f, isDirectory = false, displayName = f.name))
        }

        return result
    }

    // Memory-safe wallpaper downsampler for 720p TV (1280x720) with 1GB RAM
    fun loadDownsampledBitmap(filePath: String, targetWidth: Int = 1280, targetHeight: Int = 720): Bitmap? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            if (options.outHeight > targetHeight || options.outWidth > targetWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
                    inSampleSize *= 2
                }
            }
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // 50% RAM savings, no alpha channel needed for wallpaper
            }
            BitmapFactory.decodeFile(filePath, decodeOptions)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }
}
