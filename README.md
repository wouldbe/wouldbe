# YouTube App

Нативное Android-приложение для просмотра YouTube с поддержкой Google-аккаунта, прокси, скачивания видео, настройки качества и субтитров.

## Возможности

- ✅ Авторизация через Google-аккаунт (OAuth 2.0)
- ✅ Поиск видео через YouTube Data API v3
- ✅ Воспроизведение видео через YouTube IFrame Player API
- ✅ Поддержка HTTP/HTTPS и SOCKS5 прокси
- ✅ Скачивание видео
- ✅ Настройки качества видео (144p - 4K)
- ✅ Субтитры с выбором языка
- ✅ Адаптивный дизайн для телефонов и планшетов
- ✅ Тёмная тема

## Требования

- Android Studio Hedgehog | 2023.1.1 или новее
- JDK 17
- Android SDK 34
- Gradle 8.2

## Установка

1. Клонируйте репозиторий:
```bash
git clone https://github.com/yourusername/YouTubeApp.git
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
│       ├── YouTubeRepository.kt
│       ├── ProxyRepository.kt
│       └── SettingsRepository.kt
├── ui/
│   ├── MainActivity.kt           # Главный экран
│   ├── PlayerActivity.kt         # Плеер
│   ├── SettingsActivity.kt      # Настройки
│   └── adapter/
│       └── VideoAdapter.kt       # Адаптер для списка видео
└── service/
    └── DownloadService.kt        # Сервис загрузки
```

## Используемые библиотеки

- **YouTube Data API v3** - для поиска и получения информации о видео
- **YouTube IFrame Player API** - для воспроизведения видео
- **Google Play Services Auth** - для авторизации через Google
- **OkHttp** - для работы с прокси
- **ExoPlayer** - для воспроизведения видео
- **Glide** - для загрузки изображений
- **Material Design 3** - для UI компонентов

## Лицензия

MIT License
