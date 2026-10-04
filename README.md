# YouTube App

Нативное Android-приложение (Kotlin) для просмотра YouTube с поддержкой Google-аккаунта, прокси, скачивания видео, настройки качества и субтитров.

## Возможности

- ✅ Авторизация через Google-аккаунт (OAuth 2.0, `GoogleAuthUtil` access token)
- ✅ Поиск видео и лента: YouTube Data API v3 c фолбэком на публичный innertube API (`FeedRepository`)
- ✅ Воспроизведение: **ExoPlayer (media3) + HLS** через потоки innertube player API (`StreamRepository`), fallback на WebView IFrame
- ✅ Поддержка HTTP/HTTPS и SOCKS5 прокси (system properties для плеера, `Settings.Global.HTTP_PROXY` для WebView, OkHttp builder)
- ✅ Скачивание видео (`DownloadService`): прогрессивные потоки или HLS-сегменты с выбором качества
- ✅ Настройки качества видео (144p - 4K)
- ✅ Субтитры с выбором языка (VTT)
- ✅ Адаптивный дизайн для телефонов и планшетов
- ✅ Тёмная тема

## Как это работает

### Воспроизведение

Прямой доступ к youtube.com с многих провайдеров режется DPI, а DNS отдаёт SERVFAIL, поэтому весь трафик идёт через прокси (настраивается в приложении).

Data API для `videos.list` блокируется (бот-детект на датацентр-IP), поэтому URL потоков извлекаются через innertube player API с клиентом **VISIONOS** (clientName 101) — он не требует PO-токенов и не триггерит проверку «подтвердите, что вы не бот»:

1. `GET https://www.youtube.com/` с куками `SOCS=CAI; PREF=hl=en&tz=UTC` → `VISITOR_DATA` + session cookies;
2. `POST /youtubei/v1/player` с VISIONOS-контекстом → подписанные googlevideo URL, HLS-манифест, itag-список, captionTracks.

Подписанные URL привязаны к IP, поэтому воспроизведение и скачивание идут через тот же прокси.

### Лента и поиск

- Поиск: `POST /youtubei/v1/search` (`{query}`), пагинация через `continuation` того же endpoint (`/next` для поиска не работает — 400).
- Домашняя лента для гостя пуста (`FEwhat_to_watch` → «Try searching to get started», `FEtrending`/`FEexplore` → 400), поэтому как лента используется поиск по ротации широких запросов.
- `YouTubeRepository` (Data API) остаётся основным источником; при пустом ответе `MainActivity` переключается на `FeedRepository`.

## Требования

- Android Studio Hedgehog | 2023.1.1 или новее
- JDK 17
- Android SDK 34
- Gradle 8.2

## Установка

1. Клонируйте репозиторий:
```bash
git clone https://github.com/wouldbe/wouldbe.git
```

2. Откройте проект в Android Studio

3. Добавьте ваш Web Client ID в `app/src/main/res/values/strings.xml`:
```xml
<string name="default_web_client_id">YOUR_WEB_CLIENT_ID</string>
```

4. Соберите и запустите приложение

## Настройка Google API

1. Перейдите в [Google Cloud Console](https://console.cloud.google.com/)
2. Создайте новый проект
3. Включите YouTube Data API v3
4. Создайте OAuth 2.0 Client ID (Web application)
5. Добавьте ваш Client ID в `strings.xml`
6. В настройках ключа (Credentials → ключ → API restrictions) добавьте YouTube Data API v3 — иначе ключ отклоняется с `API_KEY_SERVICE_BLOCKED` (тогда работают только innertube-фолбэки)

## Структура проекта

```
app/src/main/java/com/example/youtubeapp/
├── YouTubeApp.kt                 # Application class
├── data/
│   ├── model/                    # Data models
│   │   ├── Video.kt
│   │   ├── ProxyConfig.kt
│   │   └── DownloadTask.kt
│   └── repository/               # Repositories
│       ├── YouTubeRepository.kt  # YouTube Data API v3
│       ├── FeedRepository.kt     # Поиск/лента через innertube (фолбэк)
│       ├── StreamRepository.kt   # Потоки через innertube player (VISIONOS)
│       ├── AuthRepository.kt     # OAuth access token
│       ├── ProxyRepository.kt    # Прокси
│       └── SettingsRepository.kt
├── ui/
│   ├── MainActivity.kt           # Главный экран
│   ├── PlayerActivity.kt         # Плеер (ExoPlayer + HLS, WebView fallback)
│   ├── SettingsActivity.kt       # Настройки
│   └── adapter/
│       └── VideoAdapter.kt       # Адаптер для списка видео
└── service/
    └── DownloadService.kt        # Сервис загрузки
```

## Используемые библиотеки

- **YouTube Data API v3** - поиск и метаданные (с фолбэком на innertube)
- **Google Play Services Auth** - авторизация через Google
- **OkHttp** - работа с прокси и innertube-запросами
- **ExoPlayer (media3)** - воспроизведение HLS
- **Glide** - загрузка изображений
- **Material Design 3** - UI компоненты

## Лицензия

MIT License
