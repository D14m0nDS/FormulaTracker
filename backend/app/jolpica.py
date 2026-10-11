import asyncio
import re
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import httpx

from app.models import SeasonSchedule
from app.normalize import ProviderPayloadError, ProviderRound, read_schedule

_PROVIDER_ID = re.compile(r"^[A-Za-z0-9_]+$")

JOLPICA_BASE_URL = "https://api.jolpi.ca"
REQUEST_TIMEOUT = httpx.Timeout(10.0)
# A season calendar changes rarely. Fifteen minutes keeps ordinary refreshes
# inside Jolpica's unauthenticated limit of 500 requests per hour.
CACHE_TTL_SECONDS = 15 * 60
# Ergast defaults to 30 rows and caps a page at 100. Standings ask for the
# maximum page and follow offset when the table is longer than that.
ERGAST_PAGE_SIZE = 100
RETRY_DELAY_SECONDS = 0.5

Sleep = Callable[[float], Awaitable[None]]


class SeasonNotFound(Exception):
    pass


class StandingsNotFound(Exception):
    pass


class ResultsNotFound(Exception):
    pass


class ProviderUnavailable(Exception):
    pass


@dataclass
class CachedSeason:
    schedule: SeasonSchedule
    rounds: dict[int, ProviderRound]


def _standings_page(payload: dict, row_key: str) -> tuple[list, int]:
    mrdata = payload.get("MRData")
    if not isinstance(mrdata, dict):
        raise ProviderUnavailable("unexpected payload")
    try:
        total = int(mrdata["total"])
    except (KeyError, TypeError, ValueError) as exc:
        raise ProviderUnavailable("unexpected payload") from exc
    table = mrdata.get("StandingsTable")
    lists = table.get("StandingsLists") if isinstance(table, dict) else None
    if not isinstance(lists, list) or not lists:
        return [], total
    first = lists[0]
    rows = first.get(row_key) if isinstance(first, dict) else None
    if not isinstance(rows, list):
        raise ProviderUnavailable("unexpected payload")
    return rows, total


def _replace_standings_rows(payload: dict, row_key: str, rows: list) -> dict:
    mrdata = payload.get("MRData")
    table = mrdata.get("StandingsTable") if isinstance(mrdata, dict) else None
    lists = table.get("StandingsLists") if isinstance(table, dict) else None
    if isinstance(lists, list) and lists and isinstance(lists[0], dict):
        lists[0][row_key] = rows
    return payload


class JolpicaClient:
    def __init__(
        self,
        *,
        client: httpx.AsyncClient | None = None,
        base_url: str = JOLPICA_BASE_URL,
        sleep: Sleep = asyncio.sleep,
    ) -> None:
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(base_url=base_url, timeout=REQUEST_TIMEOUT)
        self._sleep = sleep

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def fetch_schedule(self, year: int) -> dict:
        response = await self._get(f"/f1/alpha/schedules/{year}/")
        if response.status_code == 404:
            raise SeasonNotFound(year)
        if response.status_code != 200:
            raise ProviderUnavailable(response.status_code)
        try:
            payload = response.json()
        except ValueError as exc:
            raise ProviderUnavailable("invalid json") from exc
        if not isinstance(payload, dict):
            raise ProviderUnavailable("invalid json")
        return payload

    async def fetch_results(self, provider_round_id: str, session_type: str) -> dict:
        if _PROVIDER_ID.fullmatch(provider_round_id) is None:
            raise ProviderUnavailable("unexpected round id")
        response = await self._get(f"/f1/alpha/results/{provider_round_id}/{session_type}/")
        if response.status_code == 404:
            raise ResultsNotFound(provider_round_id, session_type)
        if response.status_code != 200:
            raise ProviderUnavailable(response.status_code)
        try:
            payload = response.json()
        except ValueError as exc:
            raise ProviderUnavailable("invalid json") from exc
        if not isinstance(payload, dict):
            raise ProviderUnavailable("invalid json")
        return payload

    async def fetch_driver_standings(self, year: int) -> dict:
        return await self._fetch_ergast_standings(
            f"/ergast/f1/{year}/driverStandings.json",
            "DriverStandings",
            year,
        )

    async def fetch_constructor_standings(self, year: int) -> dict:
        return await self._fetch_ergast_standings(
            f"/ergast/f1/{year}/constructorStandings.json",
            "ConstructorStandings",
            year,
        )

    async def _fetch_ergast_standings(self, path: str, row_key: str, year: int) -> dict:
        offset = 0
        first_page: dict | None = None
        rows: list = []
        total = 0
        for _page in range(5):
            payload = await self._get_json(
                f"{path}?limit={ERGAST_PAGE_SIZE}&offset={offset}",
                not_found=StandingsNotFound(year),
            )
            page_rows, page_total = _standings_page(payload, row_key)
            if first_page is None:
                first_page = payload
                total = page_total
            rows.extend(page_rows)
            offset += ERGAST_PAGE_SIZE
            if offset >= total or not page_rows:
                break
        else:
            raise ProviderUnavailable("standings page limit")
        if first_page is None:
            raise ProviderUnavailable("unexpected payload")
        return _replace_standings_rows(first_page, row_key, rows)

    async def _get_json(self, path: str, *, not_found: Exception) -> dict:
        response = await self._get(path)
        if response.status_code == 404:
            raise not_found
        if response.status_code != 200:
            raise ProviderUnavailable(response.status_code)
        try:
            payload = response.json()
        except ValueError as exc:
            raise ProviderUnavailable("invalid json") from exc
        if not isinstance(payload, dict):
            raise ProviderUnavailable("invalid json")
        return payload

    async def _get(self, path: str) -> httpx.Response:
        # Stepping through seasons fires a standings request per year. Jolpica's
        # unauthenticated limit is 4 requests/second, so a short 429 needs more
        # than one retry before it is reported as a failure.
        for attempt in range(3):
            try:
                response = await self._client.get(path)
            except httpx.HTTPError as exc:
                if attempt < 2:
                    await self._sleep(RETRY_DELAY_SECONDS)
                    continue
                raise ProviderUnavailable("request failed") from exc

            if attempt < 2 and (response.status_code == 429 or response.status_code >= 500):
                await self._sleep(RETRY_DELAY_SECONDS)
                continue
            return response

        raise ProviderUnavailable("request failed")


class ScheduleService:
    def __init__(self, client: JolpicaClient, *, ttl_seconds: float = CACHE_TTL_SECONDS) -> None:
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._cache: dict[int, tuple[float, CachedSeason]] = {}
        self._lock = asyncio.Lock()

    async def aclose(self) -> None:
        await self._client.aclose()

    async def get_schedule(self, year: int) -> SeasonSchedule:
        return (await self.load(year)).schedule

    async def load(self, year: int) -> CachedSeason:
        cached = self._read_cache(year)
        if cached is not None:
            return cached

        async with self._lock:
            cached = self._read_cache(year)
            if cached is not None:
                return cached
            payload = await self._client.fetch_schedule(year)
            try:
                schedule, rounds = read_schedule(payload)
            except ProviderPayloadError as exc:
                raise ProviderUnavailable("unexpected payload") from exc
            cached = CachedSeason(schedule, rounds)
            self._cache[year] = (time.monotonic() + self._ttl_seconds, cached)
            return cached

    def _read_cache(self, year: int) -> CachedSeason | None:
        item = self._cache.get(year)
        if item is None:
            return None
        expires_at, cached = item
        if time.monotonic() >= expires_at:
            del self._cache[year]
            return None
        return cached
