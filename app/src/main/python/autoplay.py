"""
autoplay.py — Suggestions de pistes similaires (BBS Groove Android).

Deux sources, au choix de l'utilisateur (réglage côté Kotlin) :
  - youtube : via yt-dlp, on récupère la "radio mix" YouTube de la piste
              (related). Aucune clé requise.
  - lastfm  : via l'API publique track.getSimilar de Last.fm. Recommandations
              musicales de qualité, mais nécessite une clé API gratuite.

Les deux renvoient une liste de tracks JSON-sérialisables (mêmes champs que
resolver.search) : title, artist, duration_ms, artwork_url, webpage_url.
Le stream est résolu plus tard, à la lecture (comme partout dans l'app).
"""

import json
import urllib.parse
import urllib.request

import yt_dlp

from titleclean import clean_track


# ── Nettoyage des titres de vidéos YouTube -> (artiste, titre) propres ──


_YDL_FLAT = {
    'quiet':        True,
    'no_warnings':  True,
    'noplaylist':   False,   # on veut la playlist radio
    'extract_flat': True,
}


def _yt_track(entry):
    if not entry:
        return None
    dur = entry.get('duration') or 0
    vid = entry.get('id', '')
    thumb = f"https://img.youtube.com/vi/{vid}/hqdefault.jpg" if vid else ""
    return {
        'title':       entry.get('title', ''),
        'artist':      entry.get('channel') or entry.get('uploader', ''),
        'duration_ms': int(dur * 1000) if dur else 0,
        'artwork_url': thumb,
        'webpage_url': entry.get('url') or entry.get('webpage_url', ''),
    }


def similar_youtube(artist, title, limit=15):
    """
    Radio YouTube de la piste : on relance une recherche enrichie et on récupère
    la mix associée. Approche pragmatique sans clé : recherche "artist title"
    puis exploitation de la playlist radio (RD...) si disponible.
    """
    out = []
    query = f"ytsearch1:{artist} - {title}"
    try:
        # 1) trouver la vidéo source
        with yt_dlp.YoutubeDL({'quiet': True, 'no_warnings': True,
                               'extract_flat': True, 'noplaylist': True}) as ydl:
            info = ydl.extract_info(query, download=False)
        entries = info.get('entries') or []
        if not entries:
            return out
        vid = entries[0].get('id', '')
        if not vid:
            return out

        # 2) ouvrir la radio mix YouTube (playlist RD<videoid>)
        radio_url = f"https://www.youtube.com/watch?v={vid}&list=RD{vid}"
        with yt_dlp.YoutubeDL(_YDL_FLAT) as ydl:
            radio = ydl.extract_info(radio_url, download=False)
        for e in (radio.get('entries') or []):
            t = _yt_track(e)
            # éviter de re-suggérer la piste source
            if t and t.get('webpage_url') and vid not in t['webpage_url']:
                out.append(t)
            if len(out) >= limit:
                break
    except Exception:
        pass
    return out


def similar_lastfm(artist, title, api_key, limit=15):
    """
    track.getSimilar (API publique Last.fm). Retourne des paires artiste/titre,
    sans audio : le stream sera résolu via yt-dlp à la lecture.
    """
    out = []
    if not api_key:
        return out
    artist, title = clean_track(artist, title)
    params = {
        'method':  'track.getsimilar',
        'artist':  artist,
        'track':   title,
        'api_key': api_key,
        'format':  'json',
        'limit':   str(limit),
        'autocorrect': '1',
    }
    url = "https://ws.audioscrobbler.com/2.0/?" + urllib.parse.urlencode(params)
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'BBSGroove/1.0'})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode('utf-8'))
        tracks = (data.get('similartracks') or {}).get('track') or []
        for t in tracks:
            name = t.get('name', '')
            art = (t.get('artist') or {}).get('name', '')
            if not name or not art:
                continue
            # artwork éventuel
            images = t.get('image') or []
            artwork = images[-1].get('#text', '') if images else ''
            out.append({
                'title':       name,
                'artist':      art,
                'duration_ms': 0,
                'artwork_url': artwork,
                'webpage_url': '',   # pas d'URL YT : résolution par recherche
            })
            if len(out) >= limit:
                break
    except Exception:
        pass
    return out


def suggest(mode, artist, title, api_key="", limit=15):
    """
    Dispatch selon le mode d'autoplay ('youtube' | 'lastfm' | autre=off).

    Fallback : en mode 'lastfm', si Last.fm ne renvoie aucun résultat
    (titre inconnu de sa base, contenu non-musical…), on bascule
    automatiquement sur la radio YouTube pour ne jamais couper la lecture.
    """
    if mode == 'youtube':
        return similar_youtube(artist, title, limit)
    if mode == 'lastfm':
        results = similar_lastfm(artist, title, api_key, limit)
        if results:
            return results
        # Last.fm vide -> filet de secours YouTube
        return similar_youtube(artist, title, limit)
    return []
