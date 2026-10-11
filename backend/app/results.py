import asyncio
import time

from app.jolpica import (
    CACHE_TTL_SECONDS,
    JolpicaClient,
    ProviderUnavailable,
    ResultsNotFound,
    ScheduleService,
)
from app.models import PracticeResults, RaceResults, SessionResults
from app.normalize import (
    ProviderPayloadError,
    normalize_practice_results,
    normalize_race_results,
    normalize_session_results,
)

ResultSet = SessionResults | RaceResults | PracticeResults


class RoundNotFound(Exception):
    pass


class SessionNotScheduled(Exception):
    pass


class ResultsService:
    def __init__(
        self,
        schedules: ScheduleService,
        client: JolpicaClient,
        *,
        ttl_seconds: float = CACHE_TTL_SECONDS,
    ) -> None:
        self._schedules = schedules
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._cache: dict[tuple[int, int, str], tuple[float, ResultSet]] = {}
        self._lock = asyncio.Lock()

    async def get_results(self, year: int, round_number: int, session_type: str) -> ResultSet:
        key = (year, round_number, session_type)
        cached = self._read_cache(key)
        if cached is not None:
            return cached

        async with self._lock:
            cached = self._read_cache(key)
            if cached is not None:
                return cached
            results = await self._fetch(year, round_number, session_type)
            self._cache[key] = (time.monotonic() + self._ttl_seconds, results)
            return results

    async def _fetch(self, year: int, round_number: int, session_type: str) -> ResultSet:
        # Public routes use the season round number. Jolpica results are
        # addressed by an opaque round id that stays inside this lookup.
        season = await self._schedules.load(year)
        if not any(event.round == round_number for event in season.schedule.events):
            raise RoundNotFound(year, round_number)

        provider_round = season.rounds.get(round_number)
        if provider_round is None:
            raise ProviderUnavailable("missing round id")
        if session_type not in provider_round.sessions:
            raise SessionNotScheduled(session_type)

        payload = await self._client.fetch_results(provider_round.provider_id, session_type)
        try:
            if session_type in {"R", "SR"}:
                return normalize_race_results(
                    payload,
                    season=year,
                    round_number=round_number,
                    session_type=session_type,
                )
            if session_type in {"FP1", "FP2", "FP3"}:
                return normalize_practice_results(
                    payload,
                    season=year,
                    round_number=round_number,
                    session_type=session_type,
                )
            return normalize_session_results(
                payload,
                season=year,
                round_number=round_number,
                session_type=session_type,
            )
        except ProviderPayloadError as exc:
            raise ProviderUnavailable("unexpected payload") from exc

    def _read_cache(self, key: tuple[int, int, str]) -> ResultSet | None:
        item = self._cache.get(key)
        if item is None:
            return None
        expires_at, results = item
        if time.monotonic() >= expires_at:
            del self._cache[key]
            return None
        return results
