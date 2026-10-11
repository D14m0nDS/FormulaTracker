import asyncio
import re
import time

from app.jolpica import CACHE_TTL_SECONDS, JolpicaClient, ProviderUnavailable
from app.models import SeasonTeams, TeamColor

_COLOR = re.compile(r"^#[0-9A-Fa-f]{6}$")


def normalize_team_colors(payload: dict, year: int) -> SeasonTeams:
    data = payload.get("data")
    if not isinstance(data, list):
        raise ProviderUnavailable("unexpected teams payload")
    by_name: dict[str, TeamColor] = {}
    for item in data:
        if not isinstance(item, dict) or not _covers_year(item, year):
            continue
        name = str(item.get("name") or "").strip()
        color = item.get("primary_color")
        if not name or not isinstance(color, str) or _COLOR.fullmatch(color) is None:
            continue
        key = name.casefold()
        if key not in by_name:
            by_name[key] = TeamColor(name=name, color=color.upper())
    teams = [by_name[key] for key in sorted(by_name)]
    return SeasonTeams(season=year, teams=teams)


def _covers_year(item: dict, year: int) -> bool:
    seasons = item.get("seasons")
    if not isinstance(seasons, list):
        return False
    return any(isinstance(season, dict) and season.get("year") == year for season in seasons)


class TeamColorsService:
    def __init__(self, client: JolpicaClient, *, ttl_seconds: float = CACHE_TTL_SECONDS) -> None:
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._cache: dict[int, tuple[float, SeasonTeams]] = {}
        self._lock = asyncio.Lock()

    async def get_teams(self, year: int) -> SeasonTeams:
        cached = self._read_cache(year)
        if cached is not None:
            return cached
        async with self._lock:
            cached = self._read_cache(year)
            if cached is not None:
                return cached
            teams = normalize_team_colors(await self._client.fetch_team_colors(year), year)
            self._cache[year] = (time.monotonic() + self._ttl_seconds, teams)
            return teams

    def _read_cache(self, year: int) -> SeasonTeams | None:
        item = self._cache.get(year)
        if item is None:
            return None
        expires_at, teams = item
        if time.monotonic() >= expires_at:
            del self._cache[year]
            return None
        return teams
