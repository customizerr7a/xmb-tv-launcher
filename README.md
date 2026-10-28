# 📺 XMB Smart TV Launcher

<p align="center">
  <img src="preview.png" alt="XMB Smart TV Launcher Preview" width="850">
</p>

<p align="center">
  <b>A lightweight, blazing-fast, and aesthetic XMB / PlayStation-style launcher tailored for Android TV.</b><br>
  <i>Optimized specifically for low-end hardware (1GB RAM TVs) with zero-cost idle shaders and native TV integration.</i>
</p>

<p align="center">
  <a href="#-english">English</a> •
  <a href="#-русский">Русский</a>
</p>

---

## 🇬🇧 English

### ✨ Key Features

- **⚡ Ultra-Low Memory & CPU Footprint:**
  - Designed specifically for low-spec Android TV sets (e.g., Haier, Yandex TV, budget smart TVs with $\sim 1$ GB RAM).
  - Idle RAM consumption around **$\sim 45-60$ MB** (compared to 250+ MB for stock bloated launchers).
  - Static GPU gradient waves with **0% continuous rendering overhead** — eliminates CPU/GPU throttling and swap thrashing.
- **🎮 PS4 / XMB-Inspired Navigation:**
  - True Leanback horizontal carousel with auto-centering focused cards.
  - Full Android TV remote control (D-Pad, OK/Enter, Menu, Back).
- **⏱️ Custom Animation Timing:**
  - Adjust animation duration in precise **20ms steps** (or cycle presets: `0ms` instant, `100ms`, `150ms`, `200ms`, `250ms`, `300ms`).
- **🗂️ App Management & Custom Reordering:**
  - **Long-press [OK]:** opens the app actions modal (Move, Hide, App Info).
  - **Tile Swapping Mode:** seamlessly move tiles left or right using `◀` / `▶` and save custom layout.
  - **App Hiding:** hide clutter/bloatware from the ribbon and restore anytime in Settings.
- **🔌 Hardware TV Inputs via Local ADB:**
  - Built-in lightweight TCP ADB client (`127.0.0.1:5555`) to send genuine `KEYCODE_TV_INPUT` events without requiring root.
  - Instantly summons native side-panel TV input selector (HDMI 1, HDMI 2, AV, TV).
- **📶 Bluetooth & Remotes:**
  - One-click trigger for native Android TV Bluetooth accessory search and pairing (`CONNECT_INPUT`).
- **🖼️ Smart Wallpaper Customizer:**
  - Low-overhead GPU gradient themes (Classic Navy, Obsidian Graphite, Cyberpunk Glow, Emerald Pine).
  - Integrated storage file browser for custom `.png`, `.jpg`, `.webp` images with automatic downsampling to protect against OOM crashes.
- **🕒 Top Bar Status:**
  - Live Wi-Fi indicator, date, and digital clock.

---

### 🕹️ Remote Control Cheat Sheet

| Button | Action |
|---|---|
| **D-Pad Left / Right** | Browse app ribbon / adjust animation ms |
| **D-Pad Up / Down** | Switch between Top Bar, Categories, and App Ribbon |
| **OK / Enter (Click)** | Launch selected application |
| **OK / Enter (Hold 0.5s)** | Open App Actions (Move, Hide, Info) |
| **MENU** | Quick wallpaper picker |
| **BACK** | Dismiss overlay dialogs / Return to apps |

---

### 🔨 Building & Installation

#### Requirements
- Android SDK (API 26..34)
- JDK 17
- Gradle (handled automatically via `./gradlew`)

#### Build APK
```bash
git clone https://github.com/pidorsosalhuy/xmb-tv-launcher.git
cd xmb-tv-launcher

# Build debug APK
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

#### Install on Android TV
```bash
adb connect <YOUR_TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🇷🇺 Русский

### ✨ Особенности и преимущества

- **⚡ Минимальное потребление памяти и процессора:**
  - Специально спроектирован для бюджетных смарт-ТВ со слабым железом (1 ГБ ОЗУ, 720p/1080p).
  - Потребляет всего **$\sim 45-60$ МБ ОЗУ** (вместо 250+ МБ у стандартных тяжелых оболочек).
  - Статический шейдерный градиент без постоянного рендеринга — 0% нагрузки на CPU в простое.
- **🎮 Интерфейс в стиле XMB / PlayStation:**
  - Плавная горизонтальная лента с центровкой выбранного тайла.
  - Полноценная поддержка пульта ДУ (D-Pad, OK, Меню, Назад).
- **⏱️ Пользовательская скорость анимаций:**
  - Точная настройка миллисекунд отклика шагами по **20 мс** либо быстрые пресеты от `0ms` (мгновенно) до `300ms` (плавный Android TV).
- **🗂️ Сортировка и скрытие программ:**
  - **Зажатие OK:** вызов меню приложения (Переместить, Скрыть, О приложении).
  - **Режим перемещения:** стрелками `◀` / `▶` расставляйте программы в удобном порядке.
  - **Скрытие приложений:** спрячьте ненужный системный мусор (всегда можно вернуть в Настройках).
- **🔌 Нативное меню входов ТВ через локальный ADB:**
  - Интегрированный сокетный клиент ADB к `127.0.0.1:5555` передает событие `KEYCODE_TV_INPUT` без root-прав.
  - Вызывает родную боковую панель ТВ с выбором HDMI 1, HDMI 2, AV, TV.
- **📶 Сопряжение Bluetooth и пультов:**
  - Быстрый переход к нативному экрану поиска пультов и геймпадов Android TV (`CONNECT_INPUT`).
- **🖼️ Кастомные обои и темы:**
  - Встроенные цветовые темы с нулевой нагрузкой на память.
  - Проводник по любым папкам телевизора (флешки, внутренняя память) для выбора `.png`, `.jpg`, `.webp` с авто-масштабированием для защиты от нехватки памяти.

---

### 🕹️ Управление с пульта

| Кнопка | Действие |
|---|---|
| **Влево / Вправо** | Листание приложений / регулировка миллисекунд |
| **Вверх / Вниз** | Переход между верхней панелью, категориями и лентой |
| **OK (Короткое нажатие)** | Запуск выбранного приложения |
| **OK (Зажатие 0.5 сек)** | Контекстное меню (Переместить, Скрыть, Инфо) |
| **MENU** | Быстрое меню обоев и тем |
| **НАЗАД** | Закрытие окон и возврат к ленте |

---

### 📄 Лицензия
Распространяется под лицензией [MIT](LICENSE).
