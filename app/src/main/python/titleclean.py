"""
titleclean.py — Nettoyage des titres de vidéos YouTube -> (artiste, titre) propres.

Module partagé : utilisé par resolver.py (affichage des résultats de recherche)
et autoplay.py (requêtes Last.fm). Transforme par ex.
  "MACKLEMORE & RYAN LEWIS - THRIFT SHOP FEAT. WANZ (OFFICIAL VIDEO)"
en  artiste="MACKLEMORE & RYAN LEWIS", titre="THRIFT SHOP".

Purement cosmétique : n'affecte que les métadonnées affichées, jamais l'URL
de lecture (le stream utilise toujours l'ID/URL YouTube d'origine).
"""

import re


_NOISE_PATTERNS = [
    r'\(official\s*(music\s*)?video\)',
    r'\[official\s*(music\s*)?video\]',
    r'\(official\s*audio\)',
    r'\[official\s*audio\]',
    r'\(lyric[s]?\s*video\)',
    r'\[lyric[s]?\s*video\]',
    r'\(official\s*lyric[s]?\s*video\)',
    r'\(audio\)', r'\[audio\]',
    r'\(visualizer\)', r'\[visualizer\]',
    r'\(HD\)', r'\[HD\]', r'\(HQ\)', r'\[HQ\]',
    r'\(4k\)', r'\[4k\]',
    r'\(remaster(ed)?\s*\d*\)',
    r'\(explicit\)', r'\[explicit\]',
    r'official\s*(music\s*)?video',
    r'lyric[s]?\s*video',
    # Variantes françaises courantes
    r'\(clip\s*officiel\)', r'\[clip\s*officiel\]',
    r'\(clip\s*(officiel\s*)?vid[eé]o\)',
    r'\(vid[eé]o\s*officielle\)', r'\[vid[eé]o\s*officielle\]',
    r'\(audio\s*officiel\)',
    r'\(paroles?\)', r'\[paroles?\]',
    r'clip\s*officiel',
]

_FEAT_PATTERN = r'\s+\b(feat|ft|featuring)\.?\s+.*$'


def _strip_noise(text):
    """Retire les marqueurs de vidéo YouTube (official video, HD, etc.)."""
    t = text
    for pat in _NOISE_PATTERNS:
        t = re.sub(pat, '', t, flags=re.IGNORECASE)
    t = re.sub(r'[\(\[]\s*[\)\]]', '', t)      # crochets/parenthèses vides
    t = re.sub(r'\s{2,}', ' ', t)              # espaces multiples
    return t.strip(' -–—|')


def clean_track(artist, title):
    """
    Extrait (artiste, titre) propres depuis les métadonnées YouTube.
    Gère 'ARTISTE - TITRE', l'artiste dupliqué, les feat., le bruit.
    Retourne (artist_clean, title_clean).
    Robuste : ne renvoie jamais de chaîne vide (repli sur l'original).
    """
    raw_title = _strip_noise(title or '')
    raw_artist = (artist or '').strip()

    art_from_title = None
    if ' - ' in raw_title:
        left, right = raw_title.split(' - ', 1)
        art_from_title = left.strip()
        song = right.strip()
    else:
        song = raw_title

    song = re.sub(_FEAT_PATTERN, '', song, flags=re.IGNORECASE).strip()

    chosen_artist = art_from_title or raw_artist
    chosen_artist = re.sub(r'\s*VEVO$', '', chosen_artist, flags=re.IGNORECASE)
    chosen_artist = re.sub(r'\s*-?\s*Topic$', '', chosen_artist, flags=re.IGNORECASE)
    chosen_artist = re.sub(_FEAT_PATTERN, '', chosen_artist, flags=re.IGNORECASE).strip()

    if chosen_artist and song.lower().startswith(chosen_artist.lower()):
        song = song[len(chosen_artist):].strip(' -–—')

    return (chosen_artist or raw_artist, song or raw_title)
