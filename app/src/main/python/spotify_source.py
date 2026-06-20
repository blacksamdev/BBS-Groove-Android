"""
spotify_source.py — Extraction des métadonnées Spotify (BBS Groove Android).

Utilise spotifyscraper en mode requests (sans authentification, sans Selenium) :
l'API embed de Spotify suffit pour titre / artiste / album / durée / artwork.

Tout est pur Python -> compatible Chaquopy (aucun wheel natif requis).
Retourne des structures JSON-sérialisables consommées côté Kotlin.
"""

try:
    from spotify_scraper import SpotifyClient
except Exception:  # nom de module alternatif selon version
    SpotifyClient = None


def _client():
    if SpotifyClient is None:
        raise RuntimeError("spotifyscraper indisponible")
    return SpotifyClient()


def _track_dict(info):
    artists = info.get('artists') or []
    artist_names = ", ".join(a.get('name', '') for a in artists if a.get('name'))
    album = info.get('album') or {}
    images = album.get('images') or info.get('images') or []
    artwork = images[0].get('url', '') if images else ''
    return {
        'title':       info.get('name', ''),
        'artist':      artist_names or (artists[0].get('name', '') if artists else ''),
        'album':       album.get('name', ''),
        'duration_ms': info.get('duration_ms', 0) or 0,
        'artwork_url': artwork,
        'spotify_id':  info.get('id', ''),
    }


def get_tracks(spotify_url):
    """
    Résout une URL Spotify (track / album / playlist) en liste de tracks.
    Retourne une liste de dicts JSON-sérialisables.
    """
    client = _client()
    try:
        url = spotify_url.strip()
        tracks = []
        if '/track/' in url:
            info = client.get_track_info(url)
            if info:
                tracks.append(_track_dict(info))
        elif '/album/' in url:
            info = client.get_album_info(url)
            for t in (info.get('tracks') or []):
                tracks.append(_track_dict(t))
        elif '/playlist/' in url:
            info = client.get_playlist_info(url)
            for t in (info.get('tracks') or []):
                # selon version, le track peut être sous 'track'
                tracks.append(_track_dict(t.get('track', t)))
        else:
            info = client.get_track_info(url)
            if info:
                tracks.append(_track_dict(info))
        return tracks
    finally:
        try:
            client.close()
        except Exception:
            pass
