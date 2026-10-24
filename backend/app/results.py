import asyncio
import time

from app.jolpica import (
    CACHE_TTL_SECONDS,
    JolpicaClient,
    ProviderUnavailable,
    ResultsNotFound,
    ScheduleService,
)
from app.models import Event, PracticeResults, RaceResults, Session, SessionResults
from app.normalize import (
    ProviderPayloadError,
    normalize_practice_results,
    normalize_race_results,
    normalize_session_results,
)
from app.openf1_results import OpenF1ResultsService

ResultSet = SessionResults | RaceResults | PracticeResults

# A session with no rows yet is asked again soon, so Jolpica or OpenF1 results
# show up within about a minute of appearing. Fallback rows are kept a little
# longer, then Jolpica's own copy replaces them once it is published.
EMPTY_TTL_SECONDS = 60
FALLBACK_TTL_SECONDS = 5 * 60


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
        fallback: OpenF1ResultsService | None = None,
        clock=time.monotonic,
    ) -> None:
        self._schedules = schedules
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._fallback = fallback
        self._clock = clock
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
            results, ttl_seconds = await self._fetch(year, round_number, session_type)
            self._cache[key] = (self._clock() + ttl_seconds, results)
            return results

    async def _fetch(
        self,
        year: int,
        round_number: int,
        session_type: str,
    ) -> tuple[ResultSet, float]:
        # Public routes use the season round number. Jolpica results are
        # addressed by an opaque round id that stays inside this lookup.
        season = await self._schedules.load(year)
        event = next((item for item in season.schedule.events if item.round == round_number), None)
        if event is None:
            raise RoundNotFound(year, round_number)

        provider_round = season.rounds.get(round_number)
        if provider_round is None:
            raise ProviderUnavailable("missing round id")
        if session_type not in provider_round.sessions:
            raise SessionNotScheduled(session_type)
        listed = next((item for item in event.sessions if item.type == session_type), None)

        try:
            payload = await self._client.fetch_results(provider_round.provider_id, session_type)
        except ResultsNotFound:
            fallback = await self._from_fallback(year, event, listed)
            if fallback is None:
                raise
            return fallback, min(self._ttl_seconds, FALLBACK_TTL_SECONDS)

        results = self._normalize(payload, year, round_number, session_type)
        if results.results:
            return results, self._ttl_seconds
        fallback = await self._from_fallback(year, event, listed)
        if fallback is not None:
            return fallback, min(self._ttl_seconds, FALLBACK_TTL_SECONDS)
        return results, min(self._ttl_seconds, EMPTY_TTL_SECONDS)

    async def _from_fallback(self, year: int, event: Event, listed: Session | None) -> ResultSet | None:
        if self._fallback is None or listed is None:
            return None
        return await self._fallback.fetch(year, event, listed)

    def _normalize(self, payload: dict, year: int, round_number: int, session_type: str) -> ResultSet:
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
        if self._clock() >= expires_at:
            del self._cache[key]
            return None
        return results
