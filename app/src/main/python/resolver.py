"""
resolver.py — Résolution titre -> URL audio YouTube (BBS Groove Android / Chaquopy).

Deux niveaux :
  - resolve_candidates() : recherche RAPIDE (extract_flat) -> liste de candidats
                           avec leur URL de PAGE YouTube (pour scoring/versions).
                           Ces URLs ne sont PAS jouables telles quelles.
  - resolve()            : extraction COMPLÈTE (bestaudio) du meilleur candidat
                           -> URL de FLUX média directe, jouable par ExoPlayer.
  - resolve_from_url()   : extraction complète depuis une URL de page YouTube.

bestaudio/best : flux audio direct (googlevideo), pas de muxing, ffmpeg non requis.
"""

import yt_dlp


# Extraction complète : produit une URL de flux média lisible par ExoPlayer
_YDL_OPTS = {
    'format':       'bestaudio/best',
    'quiet':        True,
    'no_warnings':  True,
    'noplaylist':   True,
    'extract_flat': False,
}

# Recherche rapide : ne résout PAS les flux (URLs de page seulement)
_YDL_FLAT = {
    'quiet':        True,
    'no_warnings':  True,
    'noplaylist':   True,
    'extract_flat': True,
}


def _stream_url(entry):
    """Extrait une URL de FLUX média direct depuis un entry yt-dlp complet."""
    if not entry:
        return None
    # 'url' au niveau racine d'un entry complet = flux direct sélectionné
    formats = entry.get('formats', [])
    audio = [f for f in formats
             if f.get('vcodec') == 'none' and f.get('acodec') != 'none' and f.get('url')]
    if audio:
        # meilleur bitrate audio-only
        audio.sort(key=lambda f: f.get('abr') or 0)
        return audio[-1]['url']
    if entry.get('url'):
        return entry['url']
    if formats and formats[-1].get('url'):
        return formats[-1]['url']
    return None


def _page_url(entry):
    """URL de PAGE YouTube (pour candidats/scoring)."""
    if not entry:
        return None
    return entry.get('webpage_url') or entry.get('url')


def resolve_candidates(artist, title, duration_ms=0):
    """Recherche rapide -> candidats (URL de page, durée, score)."""
    duration = (duration_ms or 0) / 1000
    query = f"ytsearch5:{artist} - {title}"
    out = []
    try:
        with yt_dlp.YoutubeDL(_YDL_FLAT) as ydl:
            info = ydl.extract_info(query, download=False)
        if not info:
            return out
        entries = info.get('entries') or [info]
        for entry in entries:
            if not entry:
                continue
            page = _page_url(entry)
            if not page:
                continue
            yt_dur = entry.get('duration') or 0
            score = (abs(yt_dur - duration) / duration
                     if duration > 0 and yt_dur > 0 else 1.0)
            out.append({
                'page_url':   page,
                'title':      entry.get('title', ''),
                'channel':    entry.get('channel') or entry.get('uploader', ''),
                'duration_s': yt_dur,
                'score':      score,
            })
        out.sort(key=lambda c: c['score'])
    except Exception:
        pass
    return out


def resolve(artist, title, duration_ms=0):
    """
    Résout (artist,title) -> URL de FLUX média jouable.
    1) recherche rapide du meilleur candidat (page URL)
    2) extraction complète bestaudio de cette page -> flux direct
    """
    cands = resolve_candidates(artist, title, duration_ms)
    if not cands:
        return None
    return resolve_from_url(cands[0]['page_url'])


def resolve_from_url(yt_url):
    """Extraction complète d'une URL de page YouTube -> URL de flux média."""
    if not yt_url:
        return None
    try:
        with yt_dlp.YoutubeDL(_YDL_OPTS) as ydl:
            info = ydl.extract_info(yt_url, download=False)
            if info:
                return _stream_url(info)
    except Exception:
        pass
    return None


def search(query, limit=15):
    """Recherche libre YouTube -> liste de tracks (métadonnées + page_url)."""
    out = []
    try:
        with yt_dlp.YoutubeDL(_YDL_FLAT) as ydl:
            info = ydl.extract_info(f"ytsearch{limit}:{query}", download=False)
        entries = info.get('entries', []) if info else []
        for e in entries:
            if not e:
                continue
            dur = e.get('duration') or 0
            if dur <= 0:
                continue
            page = e.get('webpage_url') or e.get('url') or ''
            vid = e.get('id', '')
            thumb = f"https://img.youtube.com/vi/{vid}/hqdefault.jpg" if vid else ""
            out.append({
                'title':       e.get('title', ''),
                'artist':      e.get('channel') or e.get('uploader', ''),
                'duration_ms': int(dur * 1000),
                'artwork_url': thumb,
                'webpage_url': page,
            })
    except Exception:
        pass
    return out
