# YouTube App

Нативное Android-приложение (Kotlin) для просмотра YouTube с поддержкой Google-аккаунта, прокси, скачивания видео, настройки качества и субтитров.

## Возможности

- ✅ Авторизация через Google-аккаунт (OAuth 2.0, `GoogleAuthUtil` access token)
- ✅ Поиск видео и лента: YouTube Data API v3 c фолбэком на публичный innertube API (`FeedRepository`)
- ✅ Персональная лента: загрузки ваших подписок (OAuth без ключа) + рекомендации YouTube на основе истории просмотров
- ✅ Воспроизведение: **ExoPlayer (media3) + HLS** через потоки innertube player API (`StreamRepository`), fallback на WebView IFrame
- ✅ Вкладка «Похожие» в плеере — watch-next рекомендации с youtube.com
- ✅ Поддержка HTTP/HTTPS и SOCKS5 прокси (system properties для плеера, `Settings.Global.HTTP_PROXY` для WebView, OkHttp builder)
- ✅ **Подбор прокси одним нажатием** (`ProxyAdapter`): загрузка открытых списков бесплатных прокси, замер пинга, 5 адресов с наименьшим пингом в настройках, первый применяется к хосту
- ✅ **Управление рекомендациями** (по мотивам статьи Т—Ж): «Не интересует» и «Не рекомендовать видео с этого канала» в меню ролика, режим инкогнито, учёт времени просмотра, очистка истории и сброс скрытого в настройках
- ✅ Скачивание видео (`DownloadService`): прогрессивные потоки или HLS-сегменты с выбором качества
- ✅ Настройки качества видео **по разрешению, как в YouTube** (240p, 360p, 480p, 720p, 1080p, 1440p, 2160p): рендишн и HLS-вариант выбираются по высоте, битрейт — запасной критерий
- ✅ Субтитры с выбором языка (VTT)
- ✅ Адаптивный дизайн для телефонов и планшетов
- ✅ Тёмная тема

## Как это работает

### Воспроизведение

Прямой доступ к youtube.com с многих провайдеров режется DPI, а DNS отдаёт SERVFAIL, поэтому весь трафик идёт через прокси (настраивается в приложении).

URL потоков не отдаёт Data API — они извлекаются через innertube player API с клиентом **VISIONOS** (clientName 101): он не требует PO-токенов и не триггерит бот-детект, который получают обычные WEB-клиенты с датацентрных прокси:

1. `GET https://www.youtube.com/` с куками `SOCS=CAI; PREF=hl=en&tz=UTC` → `VISITOR_DATA` + session cookies;
2. `POST /youtubei/v1/player` с VISIONOS-контекстом → подписанные googlevideo URL, HLS-манифест, itag-список, captionTracks.

Подписанные URL привязаны к IP, поэтому воспроизведение и скачивание идут через тот же прокси.

**Качество выбирается по разрешению, как в меню качества YouTube**: пресет (`VideoQuality.height`, 240p…2160p) задаёт целевую высоту; из лестницы конкретного видео берётся самый высокий формат не выше неё (`StreamRepository.formatByHeight`), его высота ограничивает `DefaultTrackSelector`, а битрейт формата (с запасом ×1.5) — пропускную способность. Скачивание выбирает HLS-вариант по `RESOLUTION` (тот же принцип), а если у манифеста нет атрибута — по `BANDWIDTH` не выше `targetBitrate` пресета.

**Подбор прокси**: кнопка «Получить прокси» в настройках запускает `ProxyAdapter` — он качает открытые списки (ProxyScrape, TheSpeedX, monosans, Geonode), отбирает до 120 `ip:port`, измеряет TCP-пинг параллельно и отдаёт 5 быстрых адресов списком; первый применяется к полям «Хост»/«Порт».

### Лента и поиск

- Поиск: `POST /youtubei/v1/search` (`{query}`), пагинация через `continuation` того же endpoint (`/next` для поиска не работает — 400).
- Домашняя лента для гостя пуста (`FEwhat_to_watch` → «Try searching to get started», `FEtrending`/`FEexplore` → 400), поэтому как запасная лента используется поиск по ротации широких запросов.
- `YouTubeRepository` (Data API) — основной источник (поиск, метаданные, «Популярное»); при пустом ответе `MainActivity` переключается на `FeedRepository`.
- **Персонализация ленты**: поверх официального фида добавляются (1) загрузки каналов из ваших подписок — `subscriptions.list`/`channels.list`/`playlistItems.list` строго по OAuth-токену, **без параметра `key`**, поэтому не зависит от ограничений ключа; (2) рекомендации innertube `/next`, посаженные на локальную историю просмотров (`WatchHistory`, до 30 видео). Всё склеивается без дублей (cap 40), пагинация — от Data API.
- Вкладка «Похожие» в плеере: watch-next список текущего видео (`/next`).

### Рекомендации: как в статье Т—Ж

Рекомендации настраиваются прямо в приложении (`Recommendations`, `WatchHistory`, `VideoMenu`):

- **«Не интересует»** — пункт меню «⋮» у ролика в ленте: видео исчезает из рекомендаций, снекбар с «Отменить» возвращает его обратно.
- **«Не рекомендовать видео с этого канала»** — скрывает блогера во всех рекомендательных списках (лента и «Похожие»).
- **Режим инкогнито** (пункт меню тулбара): история просмотров не пишется, персонализация (подписки и сиды `/next`) отключается, лента становится неперсонализированной; в подзаголовке — отметка «Инкогнито».
- **Время просмотра** (шаг 1 статьи) — сиды рекомендаций выбираются не по последним открытиям, а по накопленным миллисекундам просмотра (`WatchHistory.topIds`); плеер записывает разницу позиции при выходе (`PlayerActivity.flushWatchTime`).
- **История и «чистый лист»** (шаги 6–7) — карточка «Рекомендации» в настройках: счётчик истории и скрытого, кнопки «Очистить историю просмотров» и «Вернуть скрытые видео и каналы».

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

## Сборка установщика (APK)

```bash
# release (подписанный установщик) + debug
gradlew assembleRelease assembleDebug
```

Готовые файлы копируются в `dist/`:

- `dist/YouTubeApp-1.0-release.apk` — release-подпись, минимизированный размер;
- `dist/YouTubeApp-1.0-debug.apk` — debug-подпись (SHA-1 уже прописан в ключе API).

Установка на устройство/эмулятор:
```bash
adb install -r dist/YouTubeApp-1.0-release.apk
```

### AAB без подписи (для Google Play App Signing)

```bash
gradlew bundleRelease -PskipReleaseSigning=true
```

Результат — `app/build/outputs/bundle/release/app-release.aab` **без подписи**
(в архиве нет `META-INF/MANIFEST.MF`/`*.RSA`); флаг также отключает подпись APK,
поэтому обычный `gradlew assembleRelease` без флага собирает подписанный установщик,
как раньше. Копия: `dist/YouTubeApp-1.0-unsigned.aab` (upload-ключ накладывает
Google Play при включении Play App Signing).

**Подпись release** — `release.keystore` (alias `youtubeapp`, пароль в gitignore-файле `keystore.properties`,
можно переопределить переменной окружения `RELEASE_STORE_PASSWORD`). Ни ключ, ни пароль в git не попадают.

**SHA-1 для Google Cloud Console** (Credentials → ключ → Android apps, нужно добавить второй отпечаток):

| Ключ | SHA-1 |
|---|---|
| debug | `32:1D:30:91:49:6B:51:B7:7B:BD:6A:14:EC:B0:68:C4:EA:AC:03:AA` |
| release | `41:7F:28:94:DA:67:69:CA:B6:78:68:A1:C7:50:B4:A2:F1:13:F8:C3` |

Пока release-отпечаток не добавлен, Data API в release-сборке отвечает `API_KEY_ANDROID_APP_BLOCKED`,
а лента работает через OAuth-подписки и innertube-рекомендации.

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
│       ├── ProxyAdapter.kt       # Подбор прокси по пингу из открытых списков
│       ├── WatchHistory.kt       # История просмотров + время просмотра
│       ├── Recommendations.kt    # Скрытые видео/каналы, инкогнито, фильтр
│       └── SettingsRepository.kt
├── ui/
│   ├── MainActivity.kt           # Главный экран
│   ├── PlayerActivity.kt         # Плеер (ExoPlayer + HLS, WebView fallback)
│   ├── SettingsActivity.kt       # Настройки
│   ├── VideoMenu.kt              # «Не интересует» / «Не рекомендовать канал»
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
