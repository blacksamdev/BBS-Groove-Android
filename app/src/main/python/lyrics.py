"""
lyrics.py — Récupère les paroles (synchronisées ou brutes) depuis lrclib.net.

Porté depuis bbs_groove.core.lyrics_fetcher (desktop). lrclib.net est gratuit,
sans clé API, pur urllib (stdlib) -> compatible Chaquopy sans dépendance.

Retourne à Kotlin une structure JSON-sérialisable :
  {
    'has_synced': bool,
    'synced': [ {'t': secondes_float, 'line': str}, ... ],
    'plain':  str
  }
ou None si aucune parole trouvée (-> l'app rebascule sur pochette+titre).
"""

import json
import urllib.parse
import urllib.request

from titleclean import clean_track

_BASE = "https://lrclib.net/api"


def _parse_lrc(synced_raw):
    """Parse le LRC '[mm:ss.xx] texte' -> liste de dicts {t, line}."""
    out = []
    for line in synced_raw.splitlines():
        if line.startswith('[') and ']' in line:
            tag, _, text = line.partition(']')
            tag = tag.lstrip('[')
            try:
                parts = tag.split(':')
                secs = int(parts[0]) * 60 + float(parts[1])
                out.append({'t': secs, 'line': text.strip()})
            except (ValueError, IndexError):
                pass
    return out


def fetch(artist, title, duration_ms=0):
    """Cherche les paroles sur lrclib.net. Retourne dict ou None."""
    # Nettoyer le titre YouTube pour maximiser les correspondances lrclib
    artist, title = clean_track(artist, title)

    query = urllib.parse.urlencode({'q': f'{artist} {title}'})
    url = f'{_BASE}/search?{query}'
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'BBS-Groove/1.0'})
        with urllib.request.urlopen(req, timeout=8) as r:
            results = json.loads(r.read())
    except Exception:
        return None

    if not results or not isinstance(results, list):
        return None

    # Meilleur résultat par proximité de durée
    duration_s = (duration_ms or 0) / 1000
    best = results[0]
    if duration_s > 0:
        best = min(
            results,
            key=lambda item: abs((item.get('duration') or 0) - duration_s)
        )

    plain = best.get('plainLyrics', '') or ''
    synced_raw = best.get('syncedLyrics', '') or ''
    synced = _parse_lrc(synced_raw) if synced_raw else []

    if not plain and not synced:
        return None

    return {
        'has_synced': len(synced) > 0,
        'synced':     synced,
        'plain':      plain,
    }
