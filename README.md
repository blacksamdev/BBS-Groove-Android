# BBS grOOve — Android 🎵

Port Android du lecteur audio de la suite BBS. Résout chaque titre Spotify via
scraping public, puis joue l'audio depuis YouTube. Lecture locale ou Chromecast.

Construction calquée sur **BBS Popcorn Android** (ossature Gradle, Chaquopy,
Media3, Cast SDK, CI), logique métier reprise de **BBS grOOve** desktop.

## Fonctionnement

- Coller une URL Spotify (track, album, playlist) → lecture immédiate
- Recherche libre artiste / titre
- File de lecture avec shuffle / repeat
- Artwork + métadonnées affichés
- Casting Chromecast (bascule automatique local ↔ cast)

## Architecture

```
Spotify (scraping public)  →  métadonnées + ordre playlist   [spotifyscraper, Chaquopy]
yt-dlp (bestaudio)         →  résolution audio YouTube        [yt-dlp, Chaquopy]
Media3 / ExoPlayer         →  lecture locale
Media3 CastPlayer          →  lecture Chromecast
Cast SDK                   →  découverte + session
Kotlin + Material3         →  UI
```

Différences avec le desktop (non portés en v0.1) : mpv/IPC (remplacé par Media3),
lyrics synchronisées, autoplay Last.fm, mode gaming flottant, playlists perso.

## Stack technique

| Composant     | Technologie                          |
| ------------- | ------------------------------------ |
| Métadonnées   | spotifyscraper (mode requests)       |
| Audio         | yt-dlp (bestaudio)                   |
| Runtime Python| Chaquopy                             |
| Lecture       | Media3 / ExoPlayer                   |
| Casting       | Cast SDK + media3-cast               |
| UI            | Kotlin + Material3                   |
| CI            | GitHub Actions → APK                 |

---

GPL-3.0 — développé par blacksamdev — en hommage à Samuel Bellamy 🏴‍☠️, le Prince des Pirates, capitaine du Whydah.
