import asyncio
import time
from collections.abc import Awaitable, Callable

import httpx

from app.models import SeasonSchedule
from app.normalize import ProviderPayloadError, normalize_schedule

JOLPICA_BASE_URL = "https://api.jolpi.ca"
REQUEST_TIMEOUT = httpx.Timeout(10.0)
# A season calendar changes rarely. Fifteen minutes keeps ordinary refreshes
# inside Jolpica's unauthenticated limit of 500 requests per hour.
CACHE_TTL_SECONDS = 15 * 60
RETRY_DELAY_SECONDS = 0.5

Sleep = Callable[[float], Awaitable[None]]


class SeasonNotFound(Exception):
    pass


class ProviderUnavailable(Exception):
    pass


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

    async def _get(self, path: str) -> httpx.Response:
        for attempt in range(2):
            try:
                response = await self._client.get(path)
            except httpx.HTTPError as exc:
                if attempt == 0:
                    await self._sleep(RETRY_DELAY_SECONDS)
                    continue
                raise ProviderUnavailable("request failed") from exc

            # One immediate retry absorbs a transient throttle or server error.
            if attempt == 0 and (response.status_code == 429 or response.status_code >= 500):
                await self._sleep(RETRY_DELAY_SECONDS)
                continue
            return response

        raise ProviderUnavailable("request failed")


class ScheduleService:
    def __init__(self, client: JolpicaClient, *, ttl_seconds: float = CACHE_TTL_SECONDS) -> None:
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._cache: dict[int, tuple[float, SeasonSchedule]] = {}
        self._lock = asyncio.Lock()

    async def aclose(self) -> None:
        await self._client.aclose()

    async def get_schedule(self, year: int) -> SeasonSchedule:
        cached = self._read_cache(year)
        if cached is not None:
            return cached

        async with self._lock:
            cached = self._read_cache(year)
            if cached is not None:
                return cached
            payload = await self._client.fetch_schedule(year)
            try:
                schedule = normalize_schedule(payload)
            except ProviderPayloadError as exc:
                raise ProviderUnavailable("unexpected payload") from exc
            self._cache[year] = (time.monotonic() + self._ttl_seconds, schedule)
            return schedule

    def _read_cache(self, year: int) -> SeasonSchedule | None:
        item = self._cache.get(year)
        if item is None:
            return None
        expires_at, schedule = item
        if time.monotonic() >= expires_at:
            del self._cache[year]
            return None
        return schedule
