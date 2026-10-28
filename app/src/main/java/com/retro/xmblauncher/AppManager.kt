package com.retro.xmblauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class XmbItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val iconVector: ImageVector? = null,
    val cachedBitmap: Bitmap? = null,
    val onSelect: (Context) -> Unit
)

data class XmbCategory(
    val id: String,
    val title: String,
    val iconVector: ImageVector,
    var items: List<XmbItem>
)

object AppManager {
    private const val PREFS_NAME = "tv_launcher_apps_prefs"
    private const val KEY_HIDDEN_PACKAGES = "hidden_packages_set"
    private const val KEY_CUSTOM_ORDER_PREFIX = "custom_order_"
    private const val KEY_ANIM_DURATION = "anim_duration_ms"

    // 16 MB max cache for low-memory TV
    private val iconCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAnimationDuration(context: Context): Int {
        return getPrefs(context).getInt(KEY_ANIM_DURATION, 160)
    }

    fun setAnimationDuration(context: Context, ms: Int) {
        getPrefs(context).edit().putInt(KEY_ANIM_DURATION, ms).apply()
    }

    fun getHiddenPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_HIDDEN_PACKAGES, emptySet()) ?: emptySet()
    }

    fun hidePackage(context: Context, packageName: String) {
        val current = getHiddenPackages(context).toMutableSet()
        current.add(packageName)
        getPrefs(context).edit().putStringSet(KEY_HIDDEN_PACKAGES, current).apply()
    }

    fun unhidePackage(context: Context, packageName: String) {
        val current = getHiddenPackages(context).toMutableSet()
        current.remove(packageName)
        getPrefs(context).edit().putStringSet(KEY_HIDDEN_PACKAGES, current).apply()
    }

    fun saveCustomOrder(context: Context, categoryId: String, order: List<String>) {
        val serialized = order.joinToString(";")
        getPrefs(context).edit().putString(KEY_CUSTOM_ORDER_PREFIX + categoryId, serialized).apply()
    }

    fun getCustomOrder(context: Context, categoryId: String): List<String> {
        val serialized = getPrefs(context).getString(KEY_CUSTOM_ORDER_PREFIX + categoryId, null) ?: return emptyList()
        if (serialized.isEmpty()) return emptyList()
        return serialized.split(";").filter { it.isNotEmpty() }
    }

    // Helper to send any ADB shell command over localhost 127.0.0.1:5555 without root
    fun sendAdbShellCommand(command: String, onComplete: ((Boolean) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 1000)
                socket.soTimeout = 1500
                val out = socket.getOutputStream()
                val `in` = socket.getInputStream()

                fun makeAdbMsg(cmd: String, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
                    val cmdBytes = cmd.toByteArray(Charsets.US_ASCII)
                    val cmdInt = (cmdBytes[0].toInt() and 0xFF) or
                            ((cmdBytes[1].toInt() and 0xFF) shl 8) or
                            ((cmdBytes[2].toInt() and 0xFF) shl 16) or
                            ((cmdBytes[3].toInt() and 0xFF) shl 24)
                    val magic = cmdInt xor -0x1
                    var crc = 0
                    for (b in data) crc += (b.toInt() and 0xFF)

                    val bb = java.nio.ByteBuffer.allocate(24 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.putInt(cmdInt)
                    bb.putInt(arg0)
                    bb.putInt(arg1)
                    bb.putInt(data.size)
                    bb.putInt(crc)
                    bb.putInt(magic)
                    if (data.isNotEmpty()) bb.put(data)
                    return bb.array()
                }

                // 1. Handshake CNXN
                val hostPayload = "host::\u0000".toByteArray(Charsets.US_ASCII)
                out.write(makeAdbMsg("CNXN", 0x01000000, 4096, hostPayload))
                out.flush()

                val headerBuf = ByteArray(24)
                val bytesRead = `in`.read(headerBuf)
                if (bytesRead >= 24) {
                    val bb = java.nio.ByteBuffer.wrap(headerBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.getInt() // cmd
                    bb.getInt() // arg0
                    bb.getInt() // arg1
                    val dataLen = bb.getInt()
                    // consume payload
                    if (dataLen > 0) {
                        val payload = ByteArray(dataLen)
                        var totalRead = 0
                        while (totalRead < dataLen) {
                            val r = `in`.read(payload, totalRead, dataLen - totalRead)
                            if (r <= 0) break
                            totalRead += r
                        }
                    }

                    // 2. OPEN shell command
                    val shellCmd = "shell:$command\u0000".toByteArray(Charsets.US_ASCII)
                    out.write(makeAdbMsg("OPEN", 1, 0, shellCmd))
                    out.flush()
                    success = true
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onComplete?.invoke(success)
        }
    }

    fun sendAdbKeyEvent(keyCodeName: String, onFallback: (() -> Unit)? = null) {
        sendAdbShellCommand("input keyevent $keyCodeName") { ok ->
            if (!ok && onFallback != null) {
                onFallback()
            }
        }
    }

    // Opens the genuine Android TV / Haier TV input selection overlay via local ADB keyevent KEYCODE_TV_INPUT
    fun openNativeTvInputs(context: Context) {
        sendAdbKeyEvent("KEYCODE_TV_INPUT") {
            try {
                val intent = Intent("com.cvte.intent.SHOW_INPUT").apply {
                    setClassName("com.cvte.tv.globalui", "com.cvte.tv.globalui.InputSourceActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Opens Android TV Bluetooth pairing and remotes screen
    fun openBluetoothSettings(context: Context) {
        val intentsToTry = listOf(
            Intent("com.google.android.intent.action.CONNECT_INPUT"),
            Intent("android.settings.BLUETOOTH_SETTINGS"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        )
        for (intent in intentsToTry) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (ignored: Exception) {}
        }
        // Fallback via ADB
        sendAdbShellCommand("am start -a com.google.android.intent.action.CONNECT_INPUT")
    }

    // Power action: trigger Android TV Power/Sleep menu or sleep
    fun triggerPowerAction(context: Context) {
        // Send KEYCODE_POWER to turn off/sleep the screen
        sendAdbKeyEvent("KEYCODE_POWER") {
            try {
                val intent = Intent(Intent.ACTION_SCREEN_OFF).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetworkInfo
            active != null && active.isConnected
        } catch (e: Exception) {
            true
        }
    }

    private fun drawableToBitmapOptimized(drawable: Drawable, sizePx: Int = 120): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width <= sizePx && bmp.height <= sizePx) return bmp
            return Bitmap.createScaledBitmap(bmp, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    suspend fun loadAllCategoriesAsync(context: Context): List<XmbCategory> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val hiddenPkgs = getHiddenPackages(context)

        // 1. Dedicated Native TV Input Shortcut Tile
        val nativeInputsTile = XmbItem(
            id = "native_inputs_tile",
            title = "Входы ТВ",
            subtitle = "Открыть меню HDMI, TV, AV",
            iconVector = Icons.Default.Input
        ) { ctx -> openNativeTvInputs(ctx) }

        // 2. Settings Items
        val settingsItems = listOf(
            XmbItem(
                id = "settings_all",
                title = "Все настройки",
                subtitle = "Системные параметры Android TV",
                iconVector = Icons.Default.Settings
            ) { ctx ->
                try {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            XmbItem(
                id = "settings_wifi",
                title = "Сеть и Интернет",
                subtitle = "Wi-Fi, Ethernet, точка доступа",
                iconVector = Icons.Default.Wifi
            ) { ctx ->
                try {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            XmbItem(
                id = "settings_bluetooth",
                title = "Bluetooth и пульты",
                subtitle = "Геймпады, наушники, пульты ДУ",
                iconVector = Icons.Default.Bluetooth
            ) { ctx ->
                openBluetoothSettings(ctx)
            },
            XmbItem(
                id = "settings_apps",
                title = "Приложения",
                subtitle = "Разрешения, очистка кэша, удаление",
                iconVector = Icons.Default.Apps
            ) { ctx ->
                try {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        )

        // 3. Query all installed apps (Leanback + Standard Launcher)
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }

        val allApps = (pm.queryIntentActivities(mainIntent, 0) + pm.queryIntentActivities(leanbackIntent, 0))
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val mediaKeywords = listOf("youtube", "kion", "kinopoisk", "ivi", "okko", "media", "video", "player", "vlc", "hd", "tv", "film", "movie", "vk video", "rutube", "mpv", "channels")
        val gameKeywords = listOf("game", "retro", "emulator", "play", "steam", "moonlight", "ppsspp", "aepsx", "retroarch")

        val mediaItems = mutableListOf<XmbItem>()
        val gameItems = mutableListOf<XmbItem>()
        val allAppItems = mutableListOf<XmbItem>()

        // Insert native inputs tile at top of all apps
        allAppItems.add(nativeInputsTile)

        for (resolveInfo in allApps) {
            val pkg = resolveInfo.activityInfo.packageName
            // Skip hidden packages
            if (hiddenPkgs.contains(pkg)) continue

            val label = resolveInfo.loadLabel(pm).toString()
            val lower = label.lowercase() + " " + pkg.lowercase()

            var cachedBmp = iconCache.get(pkg)
            if (cachedBmp == null) {
                try {
                    val drawable = resolveInfo.loadIcon(pm)
                    cachedBmp = drawableToBitmapOptimized(drawable)
                    iconCache.put(pkg, cachedBmp)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            val item = XmbItem(
                id = pkg,
                title = label,
                subtitle = pkg,
                cachedBitmap = cachedBmp
            ) { ctx ->
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    ctx.startActivity(launchIntent)
                }
            }

            allAppItems.add(item)
            if (gameKeywords.any { lower.contains(it) }) {
                gameItems.add(item)
            } else if (mediaKeywords.any { lower.contains(it) }) {
                mediaItems.add(item)
            }
        }

        // Apply custom reordering for each category
        fun applyOrder(items: List<XmbItem>, categoryId: String): List<XmbItem> {
            val customOrder = getCustomOrder(context, categoryId)
            if (customOrder.isEmpty()) return items
            val itemMap = items.associateBy { it.id }
            val ordered = mutableListOf<XmbItem>()
            for (id in customOrder) {
                val found = itemMap[id]
                if (found != null) {
                    ordered.add(found)
                }
            }
            // Append any new apps not yet in customOrder
            for (item in items) {
                if (!ordered.any { it.id == item.id }) {
                    ordered.add(item)
                }
            }
            return ordered
        }

        val orderedAllApps = applyOrder(allAppItems, "all")
        val orderedGames = applyOrder(gameItems, "games")
        val orderedMedia = applyOrder(mediaItems, "media")

        listOf(
            XmbCategory(
                id = "all",
                title = "Все приложения",
                iconVector = Icons.Default.Apps,
                items = if (orderedAllApps.isNotEmpty()) orderedAllApps else listOf(
                    XmbItem("no_apps", "Нет установленных приложений", null, Icons.Default.Info) {}
                )
            ),
            XmbCategory(
                id = "games",
                title = "Игры и Эмуляторы",
                iconVector = Icons.Default.SportsEsports,
                items = if (orderedGames.isNotEmpty()) orderedGames else listOf(
                    XmbItem("no_games", "Игры не установлены", "Установите игры или ретро-эмуляторы", Icons.Default.SportsEsports) {}
                )
            ),
            XmbCategory(
                id = "media",
                title = "Медиа & ТВ",
                iconVector = Icons.Default.VideoLibrary,
                items = if (orderedMedia.isNotEmpty()) orderedMedia else listOf(
                    XmbItem("no_media", "Медиа не установлено", "Установите YouTube, плееры или онлайн-кинотеатры", Icons.Default.VideoLibrary) {}
                )
            ),
            XmbCategory(
                id = "settings",
                title = "Настройки",
                iconVector = Icons.Default.Settings,
                items = settingsItems
            )
        )
    }

    fun openAppDetails(ctx: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
