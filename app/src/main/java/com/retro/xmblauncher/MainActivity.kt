package com.retro.xmblauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class NavigationZone {
    TOP_BAR,
    CATEGORIES,
    CONTENT,
    WALLPAPER_MODAL,
    APP_ACTIONS_MODAL,
    SETTINGS_MODAL
}

enum class WallpaperModalSection {
    TABS,
    PRESETS,
    QUICK_ROOTS,
    FILES
}

data class TopBarAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvLauncherApp()
        }
    }
}

@Composable
fun TvLauncherApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Configurable Animation Speed (240ms, 120ms, 0ms)
    var animDurationMs by remember { mutableIntStateOf(AppManager.getAnimationDuration(context)) }

    // Active Theme & Custom Wallpaper State
    var currentTheme by remember { mutableStateOf(WallpaperManager.getSelectedTheme(context)) }
    var customWallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var customWallpaperPath by remember { mutableStateOf(WallpaperManager.getCustomWallpaperPath(context)) }
    var showWallpaperDialog by remember { mutableStateOf(false) }

    // Settings Modal State
    var showSettingsDialog by remember { mutableStateOf(false) }
    var settingsSelectedSection by remember { mutableIntStateOf(0) } // 0: Wallpapers, 1: Hidden, 2: Anim, 3: System
    var settingsHiddenAppIndex by remember { mutableIntStateOf(0) }

    // Categories and Apps State
    var categories by remember { mutableStateOf<List<XmbCategory>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    // Reorder Mode state
    var isReorderingMode by remember { mutableStateOf(false) }

    // App Actions Dialog State (Long press OK)
    var showAppActionsDialog by remember { mutableStateOf(false) }
    var targetActionItem by remember { mutableStateOf<XmbItem?>(null) }
    var actionDialogSelectedIndex by remember { mutableIntStateOf(0) }

    // Hidden Packages State
    var hiddenPackagesList by remember { mutableStateOf<List<String>>(emptyList()) }

    // Wallpaper file browsing state
    var currentBrowsingDir by remember { mutableStateOf(File("/sdcard/Download").takeIf { it.exists() } ?: File("/sdcard")) }
    var directoryEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    val quickRoots = remember { WallpaperManager.getQuickRoots() }

    // Live Wi-Fi Status
    var isWifiConnected by remember { mutableStateOf(AppManager.isNetworkConnected(context)) }

    // Prevents key repeat from immediately triggering dialog action while OK is still held down
    var ignoreOkUntilRelease by remember { mutableStateOf(false) }
    var okPressStartTime by remember { mutableLongStateOf(0L) }
    var okLongPressTriggered by remember { mutableStateOf(false) }

    fun reloadApps() {
        coroutineScope.launch {
            categories = AppManager.loadAllCategoriesAsync(context)
            hiddenPackagesList = AppManager.getHiddenPackages(context).toList()
            isLoadingApps = false
        }
    }

    LaunchedEffect(customWallpaperPath) {
        if (customWallpaperPath != null) {
            withContext(Dispatchers.IO) {
                customWallpaperBitmap = WallpaperManager.loadDownsampledBitmap(customWallpaperPath!!)
            }
        } else {
            customWallpaperBitmap = null
        }
    }

    LaunchedEffect(Unit) {
        reloadApps()
        while (true) {
            isWifiConnected = AppManager.isNetworkConnected(context)
            delay(4000)
        }
    }

    LaunchedEffect(currentBrowsingDir) {
        withContext(Dispatchers.IO) {
            directoryEntries = WallpaperManager.browseDirectory(currentBrowsingDir)
        }
    }

    // Navigation State
    var activeZone by remember { mutableStateOf(NavigationZone.CONTENT) }
    var selectedTopBarIndex by remember { mutableIntStateOf(0) }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var selectedContentIndex by remember { mutableIntStateOf(0) }

    // Wallpaper modal state
    var modalTab by remember { mutableIntStateOf(0) } // 0 = Presets, 1 = Folders
    var modalSection by remember { mutableStateOf(WallpaperModalSection.PRESETS) }
    var modalThemeIndex by remember { mutableIntStateOf(ThemePreset.values().indexOf(currentTheme)) }
    var modalRootIndex by remember { mutableIntStateOf(0) }
    var modalFileIndex by remember { mutableIntStateOf(0) }

    val focusRequester = remember { FocusRequester() }
    val contentListState = rememberLazyListState()

    // Smooth Android TV Ribbon Scrolling: smoothly glides and keeps upcoming apps in view
    LaunchedEffect(selectedContentIndex, activeZone) {
        if (activeZone == NavigationZone.CONTENT && categories.isNotEmpty()) {
            val cat = categories.getOrNull(selectedCategoryIndex)
            val itemsCount = cat?.items?.size ?: 0
            if (itemsCount > 0) {
                val targetScroll = (selectedContentIndex - 2).coerceAtLeast(0)
                if (animDurationMs > 0) {
                    contentListState.animateScrollToItem(
                        index = targetScroll,
                        scrollOffset = 0
                    )
                } else {
                    contentListState.scrollToItem(targetScroll)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Top Bar Actions
    val topBarItems = remember {
        listOf(
            TopBarAction("tv_home", "Главная", Icons.Default.Tv) {
                selectedCategoryIndex = 0
                selectedContentIndex = 0
                activeZone = NavigationZone.CONTENT
            },
            TopBarAction("inputs", "Входы ТВ", Icons.Default.Input) {
                AppManager.openNativeTvInputs(context)
            },
            TopBarAction("wallpaper", "Обои и Темы", Icons.Default.Palette) {
                showWallpaperDialog = true
                activeZone = NavigationZone.WALLPAPER_MODAL
                modalSection = if (modalTab == 0) WallpaperModalSection.PRESETS else WallpaperModalSection.FILES
            },
            TopBarAction("settings", "Настройки", Icons.Default.Settings) {
                hiddenPackagesList = AppManager.getHiddenPackages(context).toList()
                settingsSelectedSection = 0
                settingsHiddenAppIndex = 0
                showSettingsDialog = true
                activeZone = NavigationZone.SETTINGS_MODAL
            },
            TopBarAction("power", "Питание / Сон", Icons.Default.PowerSettingsNew) {
                AppManager.triggerPowerAction(context)
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                val nativeKey = keyEvent.nativeKeyEvent.keyCode
                val currentCategory = categories.getOrNull(selectedCategoryIndex)
                val contentCount = currentCategory?.items?.size ?: 0

                // KeyUp handler: release OK lock
                if (keyEvent.type == KeyEventType.KeyUp) {
                    if (nativeKey == KeyEvent.KEYCODE_DPAD_CENTER || nativeKey == KeyEvent.KEYCODE_ENTER) {
                        ignoreOkUntilRelease = false
                        if (okLongPressTriggered) {
                            okLongPressTriggered = false
                            return@onKeyEvent true
                        }
                        // Short Click in Content Zone
                        val elapsed = System.currentTimeMillis() - okPressStartTime
                        if (elapsed < 450 && activeZone == NavigationZone.CONTENT && !isReorderingMode) {
                            val activeItem = currentCategory?.items?.getOrNull(selectedContentIndex)
                            activeItem?.onSelect?.invoke(context)
                        } else if (elapsed < 450 && activeZone == NavigationZone.TOP_BAR) {
                            topBarItems.getOrNull(selectedTopBarIndex)?.action?.invoke()
                        } else if (elapsed < 450 && activeZone == NavigationZone.CATEGORIES) {
                            activeZone = NavigationZone.CONTENT
                        }
                        return@onKeyEvent true
                    }
                }

                // 1. REORDERING MODE NAVIGATION (Swap left/right, OK to commit)
                if (isReorderingMode && activeZone == NavigationZone.CONTENT) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (nativeKey) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (selectedContentIndex > 0 && currentCategory != null) {
                                    val currentItems = currentCategory.items.toMutableList()
                                    val fromIdx = selectedContentIndex
                                    val toIdx = fromIdx - 1
                                    Collections.swap(currentItems, fromIdx, toIdx)
                                    // FORCE COMPOSE STATE RECOMPOSE by assigning new list
                                    val newCategories = categories.map { cat ->
                                        if (cat.id == currentCategory.id) {
                                            cat.copy(items = currentItems)
                                        } else cat
                                    }
                                    categories = newCategories
                                    selectedContentIndex = toIdx
                                    AppManager.saveCustomOrder(context, currentCategory.id, currentItems.map { it.id })
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (selectedContentIndex < contentCount - 1 && currentCategory != null) {
                                    val currentItems = currentCategory.items.toMutableList()
                                    val fromIdx = selectedContentIndex
                                    val toIdx = fromIdx + 1
                                    Collections.swap(currentItems, fromIdx, toIdx)
                                    // FORCE COMPOSE STATE RECOMPOSE by assigning new list
                                    val newCategories = categories.map { cat ->
                                        if (cat.id == currentCategory.id) {
                                            cat.copy(items = currentItems)
                                        } else cat
                                    }
                                    categories = newCategories
                                    selectedContentIndex = toIdx
                                    AppManager.saveCustomOrder(context, currentCategory.id, currentItems.map { it.id })
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                                isReorderingMode = false
                                Toast.makeText(context, "Порядок приложений сохранён", Toast.LENGTH_SHORT).show()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                // 2. SETTINGS MODAL NAVIGATION
                else if (showSettingsDialog) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (nativeKey) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (settingsSelectedSection == 1 && settingsHiddenAppIndex > 0) {
                                    settingsHiddenAppIndex--
                                } else if (settingsSelectedSection > 0) {
                                    settingsSelectedSection--
                                    settingsHiddenAppIndex = 0
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (settingsSelectedSection == 1 && settingsHiddenAppIndex < hiddenPackagesList.size - 1) {
                                    settingsHiddenAppIndex++
                                } else if (settingsSelectedSection < 3) {
                                    settingsSelectedSection++
                                    settingsHiddenAppIndex = 0
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (settingsSelectedSection == 2) { // Animation Speed: decrease ms by 20ms down to 0
                                    val newMs = (animDurationMs - 20).coerceAtLeast(0)
                                    animDurationMs = newMs
                                    AppManager.setAnimationDuration(context, newMs)
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (settingsSelectedSection == 2) { // Animation Speed: increase ms by 20ms up to 600ms
                                    val newMs = (animDurationMs + 20).coerceAtMost(600)
                                    animDurationMs = newMs
                                    AppManager.setAnimationDuration(context, newMs)
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                when (settingsSelectedSection) {
                                    0 -> { // Wallpapers
                                        showSettingsDialog = false
                                        showWallpaperDialog = true
                                        activeZone = NavigationZone.WALLPAPER_MODAL
                                    }
                                    1 -> { // Hidden Apps: unhide selected
                                        val pkg = hiddenPackagesList.getOrNull(settingsHiddenAppIndex)
                                        if (pkg != null) {
                                            AppManager.unhidePackage(context, pkg)
                                            Toast.makeText(context, "Приложение восстановлено", Toast.LENGTH_SHORT).show()
                                            reloadApps()
                                        }
                                    }
                                    2 -> { // Animation speed cycle preset: 0 -> 100 -> 200 -> 250 -> 300 -> 0
                                        val next = when (animDurationMs) {
                                            0 -> 100
                                            100 -> 150
                                            150 -> 200
                                            200 -> 250
                                            250 -> 300
                                            else -> 0
                                        }
                                        animDurationMs = next
                                        AppManager.setAnimationDuration(context, next)
                                    }
                                    3 -> { // System Settings
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            })
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                showSettingsDialog = false
                                activeZone = NavigationZone.CONTENT
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                // 3. APP ACTIONS MODAL (Long Press OK Options)
                else if (showAppActionsDialog) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        // DO NOT ACCEPT OK/ENTER IF THE USER HAS NOT RELEASED OK FROM THE LONG PRESS!
                        if (nativeKey == KeyEvent.KEYCODE_DPAD_CENTER || nativeKey == KeyEvent.KEYCODE_ENTER) {
                            if (ignoreOkUntilRelease) {
                                return@onKeyEvent true
                            }
                            val item = targetActionItem
                            when (actionDialogSelectedIndex) {
                                0 -> { // Переместить
                                    showAppActionsDialog = false
                                    activeZone = NavigationZone.CONTENT
                                    isReorderingMode = true
                                }
                                1 -> { // Скрыть
                                    if (item != null) {
                                        AppManager.hidePackage(context, item.id)
                                        Toast.makeText(context, "Приложение «${item.title}» скрыто", Toast.LENGTH_SHORT).show()
                                        reloadApps()
                                    }
                                    showAppActionsDialog = false
                                    activeZone = NavigationZone.CONTENT
                                }
                                2 -> { // О приложении
                                    if (item != null) {
                                        AppManager.openAppDetails(context, item.id)
                                    }
                                    showAppActionsDialog = false
                                    activeZone = NavigationZone.CONTENT
                                }
                                3 -> { // Отмена
                                    showAppActionsDialog = false
                                    activeZone = NavigationZone.CONTENT
                                }
                            }
                            return@onKeyEvent true
                        }

                        when (nativeKey) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (actionDialogSelectedIndex > 0) actionDialogSelectedIndex--
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (actionDialogSelectedIndex < 3) actionDialogSelectedIndex++
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                showAppActionsDialog = false
                                activeZone = NavigationZone.CONTENT
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                // 4. WALLPAPER MODAL NAVIGATION
                else if (showWallpaperDialog) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (nativeKey) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                when (modalSection) {
                                    WallpaperModalSection.FILES -> {
                                        if (modalFileIndex > 0) {
                                            modalFileIndex--
                                        } else {
                                            modalSection = WallpaperModalSection.QUICK_ROOTS
                                        }
                                    }
                                    WallpaperModalSection.QUICK_ROOTS -> {
                                        modalSection = WallpaperModalSection.TABS
                                    }
                                    WallpaperModalSection.PRESETS -> {
                                        modalSection = WallpaperModalSection.TABS
                                    }
                                    WallpaperModalSection.TABS -> {}
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                when (modalSection) {
                                    WallpaperModalSection.TABS -> {
                                        modalSection = if (modalTab == 0) WallpaperModalSection.PRESETS else WallpaperModalSection.QUICK_ROOTS
                                    }
                                    WallpaperModalSection.QUICK_ROOTS -> {
                                        if (directoryEntries.isNotEmpty()) {
                                            modalSection = WallpaperModalSection.FILES
                                            modalFileIndex = 0
                                        }
                                    }
                                    WallpaperModalSection.PRESETS -> {}
                                    WallpaperModalSection.FILES -> {
                                        if (modalFileIndex < directoryEntries.size - 1) {
                                            modalFileIndex++
                                        }
                                    }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                when (modalSection) {
                                    WallpaperModalSection.TABS -> {
                                        if (modalTab > 0) {
                                            modalTab--
                                            modalSection = WallpaperModalSection.PRESETS
                                        }
                                    }
                                    WallpaperModalSection.PRESETS -> {
                                        if (modalThemeIndex > 0) modalThemeIndex--
                                    }
                                    WallpaperModalSection.QUICK_ROOTS -> {
                                        if (modalRootIndex > 0) modalRootIndex--
                                    }
                                    WallpaperModalSection.FILES -> {}
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                when (modalSection) {
                                    WallpaperModalSection.TABS -> {
                                        if (modalTab < 1) {
                                            modalTab++
                                            modalSection = WallpaperModalSection.QUICK_ROOTS
                                        }
                                    }
                                    WallpaperModalSection.PRESETS -> {
                                        if (modalThemeIndex < ThemePreset.values().size - 1) modalThemeIndex++
                                    }
                                    WallpaperModalSection.QUICK_ROOTS -> {
                                        if (modalRootIndex < quickRoots.size - 1) modalRootIndex++
                                    }
                                    WallpaperModalSection.FILES -> {}
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                when (modalSection) {
                                    WallpaperModalSection.TABS -> {
                                        modalSection = if (modalTab == 0) WallpaperModalSection.PRESETS else WallpaperModalSection.QUICK_ROOTS
                                    }
                                    WallpaperModalSection.PRESETS -> {
                                        val selected = ThemePreset.values().getOrNull(modalThemeIndex) ?: ThemePreset.TV_CLASSIC
                                        currentTheme = selected
                                        WallpaperManager.setSelectedTheme(context, selected)
                                        WallpaperManager.setCustomWallpaperPath(context, null)
                                        customWallpaperPath = null
                                        customWallpaperBitmap = null
                                        showWallpaperDialog = false
                                        activeZone = NavigationZone.CONTENT
                                    }
                                    WallpaperModalSection.QUICK_ROOTS -> {
                                        val root = quickRoots.getOrNull(modalRootIndex)?.second
                                        if (root != null && root.exists()) {
                                            currentBrowsingDir = root
                                            modalSection = WallpaperModalSection.FILES
                                            modalFileIndex = 0
                                        }
                                    }
                                    WallpaperModalSection.FILES -> {
                                        val entry = directoryEntries.getOrNull(modalFileIndex)
                                        if (entry != null) {
                                            if (entry.isDirectory) {
                                                currentBrowsingDir = entry.file
                                                modalFileIndex = 0
                                            } else {
                                                WallpaperManager.setCustomWallpaperPath(context, entry.file.absolutePath)
                                                customWallpaperPath = entry.file.absolutePath
                                                showWallpaperDialog = false
                                                activeZone = NavigationZone.CONTENT
                                            }
                                        }
                                    }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                if (modalSection == WallpaperModalSection.FILES && currentBrowsingDir.parentFile != null && currentBrowsingDir.absolutePath != "/sdcard") {
                                    currentBrowsingDir = currentBrowsingDir.parentFile!!
                                    modalFileIndex = 0
                                } else {
                                    showWallpaperDialog = false
                                    activeZone = NavigationZone.CONTENT
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                // 5. MAIN CONTENT AND TOP BAR NAVIGATION
                else {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        // Global shortcut: MENU or BLUE key toggles Wallpaper dialog
                        if (nativeKey == KeyEvent.KEYCODE_MENU || nativeKey == KeyEvent.KEYCODE_PROG_BLUE) {
                            showWallpaperDialog = !showWallpaperDialog
                            activeZone = if (showWallpaperDialog) NavigationZone.WALLPAPER_MODAL else NavigationZone.CONTENT
                            if (showWallpaperDialog) {
                                modalSection = if (modalTab == 0) WallpaperModalSection.PRESETS else WallpaperModalSection.FILES
                            }
                            return@onKeyEvent true
                        }

                        // Long press detection on OK
                        if (nativeKey == KeyEvent.KEYCODE_DPAD_CENTER || nativeKey == KeyEvent.KEYCODE_ENTER) {
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                okPressStartTime = System.currentTimeMillis()
                                okLongPressTriggered = false
                            } else if (keyEvent.nativeKeyEvent.repeatCount >= 1 && !okLongPressTriggered) {
                                val elapsed = System.currentTimeMillis() - okPressStartTime
                                if (elapsed >= 350 || keyEvent.nativeKeyEvent.repeatCount >= 2) {
                                    if (activeZone == NavigationZone.CONTENT && !isReorderingMode) {
                                        val activeItem = currentCategory?.items?.getOrNull(selectedContentIndex)
                                        if (activeItem != null && activeItem.id != "no_apps" && activeItem.id != "native_inputs_tile") {
                                            okLongPressTriggered = true
                                            ignoreOkUntilRelease = true // User must lift finger before selecting option!
                                            targetActionItem = activeItem
                                            actionDialogSelectedIndex = 0
                                            showAppActionsDialog = true
                                            activeZone = NavigationZone.APP_ACTIONS_MODAL
                                            return@onKeyEvent true
                                        }
                                    }
                                }
                            }
                            return@onKeyEvent true
                        }

                        when (nativeKey) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                when (activeZone) {
                                    NavigationZone.CONTENT -> activeZone = NavigationZone.CATEGORIES
                                    NavigationZone.CATEGORIES -> activeZone = NavigationZone.TOP_BAR
                                    NavigationZone.TOP_BAR -> {}
                                    else -> {}
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                when (activeZone) {
                                    NavigationZone.TOP_BAR -> activeZone = NavigationZone.CATEGORIES
                                    NavigationZone.CATEGORIES -> activeZone = NavigationZone.CONTENT
                                    NavigationZone.CONTENT -> {}
                                    else -> {}
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                when (activeZone) {
                                    NavigationZone.TOP_BAR -> {
                                        if (selectedTopBarIndex > 0) selectedTopBarIndex--
                                    }
                                    NavigationZone.CATEGORIES -> {
                                        if (selectedCategoryIndex > 0) {
                                            selectedCategoryIndex--
                                            selectedContentIndex = 0
                                        }
                                    }
                                    NavigationZone.CONTENT -> {
                                        if (selectedContentIndex > 0) {
                                            selectedContentIndex--
                                        } else if (contentCount > 0) {
                                            selectedContentIndex = contentCount - 1
                                        }
                                    }
                                    else -> {}
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                when (activeZone) {
                                    NavigationZone.TOP_BAR -> {
                                        if (selectedTopBarIndex < topBarItems.size - 1) selectedTopBarIndex++
                                    }
                                    NavigationZone.CATEGORIES -> {
                                        if (selectedCategoryIndex < categories.size - 1) {
                                            selectedCategoryIndex++
                                            selectedContentIndex = 0
                                        }
                                    }
                                    NavigationZone.CONTENT -> {
                                        if (selectedContentIndex < contentCount - 1) {
                                            selectedContentIndex++
                                        } else if (contentCount > 0) {
                                            selectedContentIndex = 0
                                        }
                                    }
                                    else -> {}
                                }
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                if (activeZone != NavigationZone.CONTENT) {
                                    activeZone = NavigationZone.CONTENT
                                    true
                                } else if (selectedContentIndex > 0) {
                                    selectedContentIndex = 0
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    } else false
                }
            }
    ) {
        // 1. Static lightweight background (GPU direct gradient or downscaled bitmap)
        TvBackground(
            theme = currentTheme,
            customBitmap = customWallpaperBitmap
        )

        // Subtle dark contrast overlay for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f)
                        )
                    )
                )
        )

        // 2. Main TV Interface
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Status Bar: System Action Icons + Live Wi-Fi + Digital Clock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    topBarItems.forEachIndexed { index, item ->
                        val isSelected = activeZone == NavigationZone.TOP_BAR && index == selectedTopBarIndex
                        TvTopBarIcon(
                            icon = item.icon,
                            label = item.label,
                            isSelected = isSelected,
                            accentColor = currentTheme.glowColor,
                            animMs = animDurationMs,
                            onClick = {
                                selectedTopBarIndex = index
                                activeZone = NavigationZone.TOP_BAR
                                item.action()
                            }
                        )
                    }
                }

                // Clock Widget with Wi-Fi indicator
                TvClockWidget(isWifiConnected = isWifiConnected)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Category Bar
            if (categories.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    categories.forEachIndexed { catIdx, category ->
                        val isCatActive = catIdx == selectedCategoryIndex
                        val isCatFocused = activeZone == NavigationZone.CATEGORIES && isCatActive
                        TvCategoryPill(
                            title = category.title,
                            icon = category.iconVector,
                            isActive = isCatActive,
                            isFocused = isCatFocused,
                            accentColor = currentTheme.primaryAccent,
                            glowColor = currentTheme.glowColor,
                            animMs = animDurationMs,
                            onClick = {
                                selectedCategoryIndex = catIdx
                                selectedContentIndex = 0
                                activeZone = NavigationZone.CONTENT
                            }
                        )
                    }
                }
            }

            // Main Content Ribbon (Buttery smooth horizontal glide)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                val currentCategory = categories.getOrNull(selectedCategoryIndex)
                if (isLoadingApps) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = currentTheme.glowColor)
                    }
                } else if (currentCategory != null && currentCategory.items.isNotEmpty()) {
                    LazyRow(
                        state = contentListState,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(
                            items = currentCategory.items,
                            key = { _, item -> item.id }
                        ) { itemIdx, item ->
                            val isFocused = activeZone == NavigationZone.CONTENT && itemIdx == selectedContentIndex
                            TvContentTile(
                                item = item,
                                isFocused = isFocused,
                                isReordering = isReorderingMode && isFocused,
                                glowColor = currentTheme.glowColor,
                                animMs = animDurationMs,
                                onClick = {
                                    selectedContentIndex = itemIdx
                                    activeZone = NavigationZone.CONTENT
                                    item.onSelect(context)
                                }
                            )
                        }
                    }
                }
            }

            // Active Item Info & TV Remote Button Hints
            val currentCategory = categories.getOrNull(selectedCategoryIndex)
            val focusedItem = currentCategory?.items?.getOrNull(selectedContentIndex)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = focusedItem?.title ?: "Smart TV",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isReorderingMode) {
                            "↔️ РЕЖИМ ПЕРЕМЕЩЕНИЯ: нажимайте ◀ ▶ чтобы сдвинуть, затем [OK] чтобы закрепить"
                        } else {
                            focusedItem?.subtitle ?: "Главное меню ТВ"
                        },
                        color = if (isReorderingMode) Color(0xFFFFD54F) else currentTheme.glowColor.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = if (isReorderingMode) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // TV Remote Navigation Legend
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (isReorderingMode) {
                        TvRemoteBadge(button = "◀▶", label = "Сдвинуть")
                        TvRemoteBadge(button = "OK", label = "Закрепить")
                    } else {
                        TvRemoteBadge(button = "OK", label = "Запуск (зажать: меню)")
                        TvRemoteBadge(button = "МЕНЮ", label = "Обои")
                        TvRemoteBadge(button = "▲▼", label = "Меню / Приложения")
                    }
                }
            }
        }

        // 3. Settings Modal Overlay (In-place Compose overlay: 100% key-reliable!)
        if (showSettingsDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(680.dp)
                        .height(390.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF101522))
                        .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Параметры и Настройки ТВ",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                TvRemoteBadge("НАЗАД", "Закрыть")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Section 0: Wallpapers
                            SettingsRowCard(
                                title = "🖼️  Параметры обоев",
                                subtitle = "Сменить цветовую тему, выбрать картинку из папок или сбросить",
                                isSelected = settingsSelectedSection == 0,
                                extraContent = {
                                    Text(
                                        text = "Тема: ${currentTheme.title}",
                                        color = Color(0xFF00E5FF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Section 1: Hidden Apps
                            SettingsRowCard(
                                title = "👁️  Скрытые приложения (${hiddenPackagesList.size})",
                                subtitle = if (hiddenPackagesList.isEmpty()) "Все приложения отображаются в меню" else "Нажмите OK, чтобы восстановить скрытую программу",
                                isSelected = settingsSelectedSection == 1,
                                extraContent = {
                                    if (hiddenPackagesList.isNotEmpty()) {
                                        Text(
                                            text = hiddenPackagesList.joinToString(", "),
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Section 2: Animation Speed
                            val animLabel = if (animDurationMs == 0) {
                                "0ms (Отключены - Мгновенно)"
                            } else {
                                "${animDurationMs}ms (${if (animDurationMs <= 120) "Быстрые" else if (animDurationMs <= 250) "Стандарт ТВ" else "Плавные"})"
                            }
                            SettingsRowCard(
                                title = "⚡  Настройка анимации интерфейса",
                                subtitle = "Точная настройка скорости отклика: ◀ / ▶ шагами по 20ms или [OK] пресет",
                                isSelected = settingsSelectedSection == 2,
                                extraContent = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = animLabel,
                                            color = Color(0xFF00E5FF),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "[◀ -20ms | ▶ +20ms]",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Section 3: Android TV System Settings
                            SettingsRowCard(
                                title = "⚙️  Системные настройки Android TV",
                                subtitle = "Открыть параметры телевизора: Сеть, Пульты, Экран, Звук",
                                isSelected = settingsSelectedSection == 3
                            )
                        }

                        // Bottom navigation prompt
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Используйте ▲ ▼ для выбора раздела, [OK] для применения или открытия",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvRemoteBadge("OK", "Открыть / Выбрать")
                            }
                        }
                    }
                }
            }
        }

        // 4. App Actions Modal Overlay (In-place Compose overlay: no key repeat bug!)
        if (showAppActionsDialog && targetActionItem != null) {
            val item = targetActionItem!!
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(460.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF101522))
                        .border(2.dp, currentTheme.glowColor, RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "Действия с приложением",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 18.dp)
                        )

                        val actions = listOf(
                            Triple("↔️  Переместить приложение", "Сдвинуть в начало или между программами", Icons.Default.SwapHoriz),
                            Triple("👁️  Скрыть приложение", "Убрать из списка (можно вернуть в Настройках)", Icons.Default.VisibilityOff),
                            Triple("⚙️  О приложении", "Системные параметры, память, разрешения", Icons.Default.Info),
                            Triple("❌  Отмена", "Вернуться назад", Icons.Default.Close)
                        )

                        actions.forEachIndexed { idx, (title, subtitle, icon) ->
                            val isSelected = idx == actionDialogSelectedIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) currentTheme.glowColor.copy(alpha = 0.25f)
                                        else Color.White.copy(alpha = 0.05f)
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) currentTheme.glowColor else Color.White.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) currentTheme.glowColor else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = title,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f),
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        text = subtitle,
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TvRemoteBadge("OK", "Выбрать")
                            Spacer(modifier = Modifier.width(8.dp))
                            TvRemoteBadge("НАЗАД", "Закрыть")
                        }
                    }
                }
            }
        }

        // 5. Wallpaper Switcher Modal Overlay (In-place Compose overlay)
        if (showWallpaperDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(720.dp)
                        .height(400.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF101522))
                        .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Header Tabs: [Готовые темы] | [📁 Выбрать из любой папки]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                val tab0Selected = modalSection == WallpaperModalSection.TABS && modalTab == 0
                                val tab1Selected = modalSection == WallpaperModalSection.TABS && modalTab == 1

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (modalTab == 0) Color(0xFF0070D1) else Color.White.copy(alpha = 0.08f))
                                        .border(
                                            width = if (tab0Selected) 2.dp else 1.dp,
                                            color = if (tab0Selected) Color(0xFF00E5FF) else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            modalTab = 0
                                            modalSection = WallpaperModalSection.PRESETS
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "Готовые темы Smart TV",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (modalTab == 1) Color(0xFF0070D1) else Color.White.copy(alpha = 0.08f))
                                        .border(
                                            width = if (tab1Selected) 2.dp else 1.dp,
                                            color = if (tab1Selected) Color(0xFF00E5FF) else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            modalTab = 1
                                            modalSection = WallpaperModalSection.QUICK_ROOTS
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "📁 Выбрать из любой папки (PNG/JPG)",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            if (modalTab == 0) {
                                // TAB 0: THEME PRESETS
                                Text(
                                    text = "Цветовые схемы Smart TV (0% нагрузки на память):",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.align(Alignment.Start)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ThemePreset.values().forEachIndexed { index, preset ->
                                        val isSelected = modalSection == WallpaperModalSection.PRESETS && index == modalThemeIndex
                                        val isCurrent = customWallpaperPath == null && preset == currentTheme

                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    modalThemeIndex = index
                                                    currentTheme = preset
                                                    WallpaperManager.setSelectedTheme(context, preset)
                                                    WallpaperManager.setCustomWallpaperPath(context, null)
                                                    customWallpaperPath = null
                                                    customWallpaperBitmap = null
                                                    showWallpaperDialog = false
                                                    activeZone = NavigationZone.CONTENT
                                                }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(80.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(
                                                        Brush.linearGradient(
                                                            listOf(preset.backgroundColors[2], preset.backgroundColors[0])
                                                        )
                                                    )
                                                    .border(
                                                        width = if (isSelected) 2.5.dp else 1.dp,
                                                        color = if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                            ) {
                                                if (isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Active",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(26.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = preset.title,
                                                color = if (isSelected) Color(0xFF00E5FF) else Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            } else {
                                // TAB 1: ANY FOLDER FILE BROWSER
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    quickRoots.forEachIndexed { rIdx, (label, folder) ->
                                        val isFocused = modalSection == WallpaperModalSection.QUICK_ROOTS && rIdx == modalRootIndex
                                        val isActiveFolder = currentBrowsingDir.absolutePath == folder.absolutePath

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isActiveFolder) Color(0xFF0070D1).copy(alpha = 0.6f)
                                                    else Color.White.copy(alpha = 0.08f)
                                                )
                                                .border(
                                                    width = if (isFocused) 2.dp else 0.5.dp,
                                                    color = if (isFocused) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    currentBrowsingDir = folder
                                                    modalSection = WallpaperModalSection.FILES
                                                    modalFileIndex = 0
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isFocused || isActiveFolder) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Папка: ${currentBrowsingDir.absolutePath}",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.align(Alignment.Start)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                if (directoryEntries.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Папка пуста или нет картинок (.jpg, .png, .webp)",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(170.dp)
                                    ) {
                                        itemsIndexed(directoryEntries) { idx, entry ->
                                            val isSelected = modalSection == WallpaperModalSection.FILES && idx == modalFileIndex
                                            val isCurrentActive = customWallpaperPath == entry.file.absolutePath

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.25f)
                                                        else Color.White.copy(alpha = 0.04f)
                                                    )
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                                        color = if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.1f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        if (entry.isDirectory) {
                                                            currentBrowsingDir = entry.file
                                                            modalFileIndex = 0
                                                        } else {
                                                            WallpaperManager.setCustomWallpaperPath(context, entry.file.absolutePath)
                                                            customWallpaperPath = entry.file.absolutePath
                                                            showWallpaperDialog = false
                                                            activeZone = NavigationZone.CONTENT
                                                        }
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Image,
                                                        contentDescription = null,
                                                        tint = if (entry.isDirectory) Color(0xFFFFD54F) else Color(0xFF00E5FF),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text(
                                                        text = entry.displayName,
                                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                                        fontSize = 13.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (isCurrentActive) {
                                                    Text(
                                                        text = "Установлено",
                                                        color = Color(0xFF00E676),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Navigation Hints
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (modalTab == 0) {
                                    ThemePreset.values().getOrNull(modalThemeIndex)?.description ?: ""
                                } else {
                                    "Нажмите [OK] для входа в папку или выбора картинки"
                                },
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvRemoteBadge("OK", "Выбрать")
                                TvRemoteBadge("НАЗАД", "Закрыть")
                            }
                        }
                    }
                }
            }
        }
    }
}

// Static lightweight TV Background (No continuous shader animation, 0% CPU)
@Composable
fun TvBackground(theme: ThemePreset, customBitmap: Bitmap?) {
    if (customBitmap != null) {
        Image(
            bitmap = customBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        theme.backgroundColors[2],
                        theme.backgroundColors[1],
                        theme.backgroundColors[0]
                    ),
                    center = Offset(width * 0.35f, height * 0.45f),
                    radius = width * 0.85f
                )
            )

            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        theme.backgroundColors[3].copy(alpha = 0.7f),
                        Color.Transparent,
                        theme.glowColor.copy(alpha = 0.12f)
                    ),
                    start = Offset(0f, height),
                    end = Offset(width, 0f)
                )
            )
        }
    }
}

// TV Top Bar Icon
@Composable
fun TvTopBarIcon(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    animMs: Int,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.14f else 1.0f,
        animationSpec = if (animMs > 0) tween(durationMillis = animMs, easing = FastOutSlowInEasing) else tween(0),
        label = "top_icon_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) accentColor.copy(alpha = 0.35f)
                    else Color.White.copy(alpha = 0.08f)
                )
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) accentColor else Color.White.copy(alpha = 0.18f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) accentColor else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp)
            )
        }
        if (isSelected) {
            Text(
                text = label,
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// Category Pill
@Composable
fun TvCategoryPill(
    title: String,
    icon: ImageVector,
    isActive: Boolean,
    isFocused: Boolean,
    accentColor: Color,
    glowColor: Color,
    animMs: Int,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = if (animMs > 0) tween(durationMillis = animMs, easing = FastOutSlowInEasing) else tween(0),
        label = "cat_pill_scale"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    isFocused -> glowColor.copy(alpha = 0.35f)
                    isActive -> accentColor.copy(alpha = 0.25f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .border(
                width = if (isFocused) 2.dp else if (isActive) 1.5.dp else 0.5.dp,
                color = if (isFocused) glowColor else if (isActive) accentColor else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isFocused || isActive) glowColor else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                color = if (isFocused || isActive) Color.White else Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = if (isFocused || isActive) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

// Content Card Tile: smooth focus scale and glowing border
@Composable
fun TvContentTile(
    item: XmbItem,
    isFocused: Boolean,
    isReordering: Boolean,
    glowColor: Color,
    animMs: Int,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isReordering) 1.12f else if (isFocused) 1.10f else 1.0f,
        animationSpec = if (animMs > 0) tween(durationMillis = animMs, easing = FastOutSlowInEasing) else tween(0),
        label = "tile_scale"
    )

    val activeBorderColor by animateColorAsState(
        targetValue = when {
            isReordering -> Color(0xFFFFD54F)
            isFocused -> glowColor
            else -> Color.White.copy(alpha = 0.12f)
        },
        animationSpec = if (animMs > 0) tween(durationMillis = animMs, easing = FastOutSlowInEasing) else tween(0),
        label = "tile_border"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(130.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isReordering) Color(0xFF2C2616)
                    else if (isFocused) Color(0xFF1B2433)
                    else Color(0x9910141D)
                )
                .border(
                    width = if (isReordering) 3.dp else if (isFocused) 2.5.dp else 1.dp,
                    color = activeBorderColor,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            if (item.cachedBitmap != null) {
                Image(
                    bitmap = item.cachedBitmap.asImageBitmap(),
                    contentDescription = item.title,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else if (item.iconVector != null) {
                Icon(
                    imageVector = item.iconVector,
                    contentDescription = item.title,
                    tint = if (isReordering) Color(0xFFFFD54F) else if (isFocused) glowColor else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(50.dp)
                )
            }

            if (isReordering) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFD54F))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Move",
                        tint = Color.Black,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.title,
            color = if (isReordering) Color(0xFFFFD54F) else if (isFocused) Color.White else Color.White.copy(alpha = 0.65f),
            fontSize = 12.sp,
            fontWeight = if (isFocused || isReordering) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

// Remote Badge
@Composable
fun TvRemoteBadge(button: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = button,
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// Isolated Clock Widget with Wi-Fi indicator
@Composable
fun TvClockWidget(isWifiConnected: Boolean) {
    var timeString by remember { mutableStateOf("") }
    var dateString by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
        while (true) {
            val now = Date()
            timeString = timeFormat.format(now)
            dateString = dateFormat.format(now).replaceFirstChar { it.uppercase() }
            delay(1000)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = "Wi-Fi",
            tint = if (isWifiConnected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = dateString,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = timeString,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SettingsRowCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    extraContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }
        if (extraContent != null) {
            Spacer(modifier = Modifier.width(16.dp))
            extraContent()
        }
    }
}
