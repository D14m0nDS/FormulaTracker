import asyncio
import time
from datetime import datetime, timedelta, timezone

import httpx

from app.models import DriverPhoto, SeasonDrivers

OPENF1_BASE_URL = "https://api.openf1.org/v1"
FIRST_PHOTO_YEAR = 2023
# OpenF1 treats a session as live from 30 minutes before the start until 30
# minutes after the end. Live data is paid, so photos come from a race that
# finished before that window.
LIVE_MARGIN = timedelta(minutes=30)


class OpenF1Unavailable(Exception):
    pass


def select_race_session(sessions: list, now: datetime) -> dict | None:
    finished: list[tuple[datetime, dict]] = []
    for session in sessions:
        if not isinstance(session, dict):
            continue
        if session.get("session_name") != "Race" or session.get("is_cancelled"):
            continue
        end = parse_openf1_time(session.get("date_end"))
        if end is None or end + LIVE_MARGIN > now:
            continue
        finished.append((end, session))
    if not finished:
        return None
    return max(finished, key=lambda item: item[0])[1]


def normalize_driver_photos(rows: list) -> list[DriverPhoto]:
    by_code: dict[str, DriverPhoto] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        code = str(row.get("name_acronym") or "").strip().upper()
        if not code:
            continue
        url = row.get("headshot_url")
        photo_url = url.strip() if isinstance(url, str) and url.strip() else None
        name = str(row.get("last_name") or code).strip() or code
        current = by_code.get(code)
        if current is None or (current.photo_url is None and photo_url is not None):
            by_code[code] = DriverPhoto(code=code, name=name, photo_url=photo_url)
    return [by_code[code] for code in sorted(by_code)]


class DriverPhotosService:
    def __init__(self, client: httpx.AsyncClient, *, now=None, ttl_seconds: float = 15 * 60):
        self._client = client
        self._now = now or (lambda: datetime.now(timezone.utc))
        self._ttl_seconds = ttl_seconds
        self._cache: dict[int, tuple[float, SeasonDrivers]] = {}
        self._lock = asyncio.Lock()

    async def get_drivers(self, year: int) -> SeasonDrivers:
        if year < FIRST_PHOTO_YEAR:
            return SeasonDrivers(season=year, drivers=[])
        cached = self._read_cache(year)
        if cached is not None:
            return cached
        async with self._lock:
            cached = self._read_cache(year)
            if cached is not None:
                return cached
            drivers = await self._fetch(year)
            self._cache[year] = (time.monotonic() + self._ttl_seconds, drivers)
            return drivers

    async def _fetch(self, year: int) -> SeasonDrivers:
        sessions = await self._get("/sessions", {"year": year, "session_name": "Race"})
        if not isinstance(sessions, list):
            raise OpenF1Unavailable("unexpected sessions payload")
        session = select_race_session(sessions, self._now())
        if session is None:
            return SeasonDrivers(season=year, drivers=[])
        rows = await self._get("/drivers", {"session_key": session.get("session_key")})
        if not isinstance(rows, list):
            raise OpenF1Unavailable("unexpected drivers payload")
        return SeasonDrivers(season=year, drivers=normalize_driver_photos(rows))

    async def _get(self, path: str, params: dict) -> list | dict:
        try:
            response = await self._client.get(path, params=params)
        except httpx.HTTPError as exc:
            raise OpenF1Unavailable("provider request failed") from exc
        if response.status_code != 200:
            raise OpenF1Unavailable("provider request failed")
        try:
            return response.json()
        except ValueError as exc:
            raise OpenF1Unavailable("unexpected payload") from exc

    def _read_cache(self, year: int) -> SeasonDrivers | None:
        item = self._cache.get(year)
        if item is None:
            return None
        expires_at, drivers = item
        if expires_at <= time.monotonic():
            del self._cache[year]
            return None
        return drivers


async def get_openf1_json(client: httpx.AsyncClient, path: str, params: dict) -> list | dict:
    try:
        response = await client.get(path, params=params)
    except httpx.HTTPError as exc:
        raise OpenF1Unavailable("provider request failed") from exc
    if response.status_code != 200:
        raise OpenF1Unavailable("provider request failed")
    try:
        return response.json()
    except ValueError as exc:
        raise OpenF1Unavailable("unexpected payload") from exc


def parse_openf1_time(value) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
