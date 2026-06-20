"""
resolver.py — Résolution titre -> URL audio YouTube (BBS Groove Android / Chaquopy).

Porté depuis bbs_groove.core.resolver (desktop). Logique identique :
  - resolve_candidates() : jusqu'à 5 résultats triés par proximité de durée
  - resolve()            : meilleur match (premier candidat)
  - resolve_from_url()   : stream frais depuis une URL YouTube pérenne

bestaudio/best : flux audio unique, pas de muxing, ffmpeg non requis.
"""

import yt_dlp


_YDL_OPTS = {
    'format':       'bestaudio/best',
    'quiet':        True,
    'no_warnings':  True,
    'noplaylist':   True,
    'extract_flat': False,
}

_YDL_FLAT = {
    'quiet':        True,
    'no_warnings':  True,
    'noplaylist':   True,
    'extract_flat': True,
}


def _extract_url(entry):
    if not entry:
        return None
    if 'url' in entry:
        return entry['url']
    formats = entry.get('formats', [])
    audio = [f for f in formats
             if f.get('vcodec') == 'none' and f.get('acodec') != 'none']
    if audio:
        return audio[-1]['url']
    if formats:
        return formats[-1]['url']
    return None


def resolve_candidates(artist, title, duration_ms=0):
    """Retourne une liste JSON-sérialisable de candidats triés par durée."""
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
            url = _extract_url(entry)
            if not url:
                continue
            yt_dur = entry.get('duration') or 0
            score = (abs(yt_dur - duration) / duration
                     if duration > 0 and yt_dur > 0 else 1.0)
            out.append({
                'url':         url,
                'webpage_url': entry.get('webpage_url', ''),
                'title':       entry.get('title', ''),
                'channel':     entry.get('channel') or entry.get('uploader', ''),
                'duration_s':  yt_dur,
                'score':       score,
            })
        out.sort(key=lambda c: c['score'])
    except Exception:
        pass
    return out


def resolve(artist, title, duration_ms=0):
    """Retourne l'URL stream du meilleur candidat, ou None."""
    cands = resolve_candidates(artist, title, duration_ms)
    return cands[0]['url'] if cands else None


def resolve_from_url(yt_url):
    """Résout une URL YouTube pérenne en URL streaming fraîche."""
    try:
        with yt_dlp.YoutubeDL(_YDL_OPTS) as ydl:
            info = ydl.extract_info(yt_url, download=False)
            if info:
                return _extract_url(info)
    except Exception:
        pass
    return None


def search(query, limit=15):
    """Recherche libre YouTube. Retourne une liste de tracks JSON-sérialisables."""
    import re
    opts = dict(_YDL_FLAT)
    out = []
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch{limit}:{query}", download=False)
        entries = info.get('entries', []) if info else []
        for e in entries:
            if not e:
                continue
            dur = e.get('duration') or 0
            if dur <= 0:
                continue
            yt_url = e.get('url', '')
            vid = re.search(r'v=([^&]+)', yt_url)
            thumb = (f"https://img.youtube.com/vi/{vid.group(1)}/hqdefault.jpg"
                     if vid else "")
            out.append({
                'title':       e.get('title', ''),
                'artist':      e.get('channel') or e.get('uploader', ''),
                'duration_ms': int(dur * 1000),
                'artwork_url': thumb,
                'webpage_url': e.get('url', ''),
            })
    except Exception:
        pass
    return out
