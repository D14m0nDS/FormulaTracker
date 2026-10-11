import asyncio
import time

from app.jolpica import CACHE_TTL_SECONDS, JolpicaClient, ProviderUnavailable, StandingsNotFound
from app.models import ConstructorStanding, Driver, DriverStanding, SeasonStandings


class StandingsPayloadError(Exception):
    """Ergast returned standings JSON that this app cannot use."""


class StandingsService:
    def __init__(self, client: JolpicaClient, *, ttl_seconds: float = CACHE_TTL_SECONDS) -> None:
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._cache: dict[int, tuple[float, SeasonStandings]] = {}
        self._lock = asyncio.Lock()

    async def get_standings(self, year: int) -> SeasonStandings:
        cached = self._read_cache(year)
        if cached is not None:
            return cached

        async with self._lock:
            cached = self._read_cache(year)
            if cached is not None:
                return cached
            driver_payload = await self._client.fetch_driver_standings(year)
            constructor_payload = await self._client.fetch_constructor_standings(year)
            try:
                standings = normalize_standings(driver_payload, constructor_payload, season=year)
            except StandingsPayloadError as exc:
                raise ProviderUnavailable("unexpected payload") from exc
            self._cache[year] = (time.monotonic() + self._ttl_seconds, standings)
            return standings

    def _read_cache(self, year: int) -> SeasonStandings | None:
        item = self._cache.get(year)
        if item is None:
            return None
        expires_at, standings = item
        if time.monotonic() >= expires_at:
            del self._cache[year]
            return None
        return standings


def normalize_standings(driver_payload: dict, constructor_payload: dict, *, season: int) -> SeasonStandings:
    driver_season, driver_round, driver_rows = _table(driver_payload, "DriverStandings")
    constructor_season, constructor_round, constructor_rows = _table(
        constructor_payload,
        "ConstructorStandings",
    )
    if driver_season != season or constructor_season != season:
        raise StandingsPayloadError("standings are for a different season")
    if (
        driver_round is not None
        and constructor_round is not None
        and driver_round != constructor_round
    ):
        raise StandingsPayloadError("driver and constructor standings are after different rounds")

    after_round = driver_round if driver_round is not None else constructor_round
    drivers = [_driver_row(row) for row in driver_rows]
    constructors = [_constructor_row(row) for row in constructor_rows]
    drivers.sort(key=_position_order)
    constructors.sort(key=_position_order)
    return SeasonStandings(
        season=season,
        after_round=after_round,
        drivers=drivers,
        constructors=constructors,
    )


def _table(payload: dict, row_key: str) -> tuple[int, int | None, list]:
    mrdata = payload.get("MRData")
    table = mrdata.get("StandingsTable") if isinstance(mrdata, dict) else None
    if not isinstance(table, dict):
        raise StandingsPayloadError("standings payload has no table")

    season = _whole(table.get("season"))
    if season is None:
        raise StandingsPayloadError("standings payload has no season")

    lists = table.get("StandingsLists")
    if not isinstance(lists, list):
        raise StandingsPayloadError("standings payload has no lists")
    if not lists:
        round_number = _whole(table.get("round"))
        return season, round_number, []

    first = lists[0]
    if not isinstance(first, dict):
        raise StandingsPayloadError("standings list is not an object")
    rows = first.get(row_key)
    if not isinstance(rows, list):
        raise StandingsPayloadError("standings payload has no rows")
    round_number = _whole(first.get("round"))
    if round_number is None:
        round_number = _whole(table.get("round"))
    return season, round_number, rows


def _driver_row(row: object) -> DriverStanding:
    if not isinstance(row, dict):
        raise StandingsPayloadError("driver standing is not an object")
    driver_raw = row.get("Driver")
    if not isinstance(driver_raw, dict):
        raise StandingsPayloadError("driver standing has no driver")

    given_name = driver_raw.get("givenName")
    family_name = driver_raw.get("familyName")
    if not isinstance(given_name, str) or not isinstance(family_name, str) or not family_name:
        raise StandingsPayloadError("driver standing has no name")

    code = driver_raw.get("code")
    teams = []
    constructors = row.get("Constructors")
    if isinstance(constructors, list):
        for constructor in constructors:
            name = constructor.get("name") if isinstance(constructor, dict) else None
            if isinstance(name, str) and name:
                teams.append(name)

    position, position_text = _championship_position(row, "driver position")
    return DriverStanding(
        position=position,
        position_text=position_text,
        points=_required_points(row.get("points")),
        wins=_required_whole(row.get("wins"), "driver wins"),
        driver=Driver(
            code=code if isinstance(code, str) else "",
            given_name=given_name,
            family_name=family_name,
            number=_whole(driver_raw.get("permanentNumber")),
        ),
        teams=teams,
    )


def _constructor_row(row: object) -> ConstructorStanding:
    if not isinstance(row, dict):
        raise StandingsPayloadError("constructor standing is not an object")
    constructor = row.get("Constructor")
    name = constructor.get("name") if isinstance(constructor, dict) else None
    if not isinstance(name, str) or not name:
        raise StandingsPayloadError("constructor standing has no team")
    position, position_text = _championship_position(row, "constructor position")
    return ConstructorStanding(
        position=position,
        position_text=position_text,
        points=_required_points(row.get("points")),
        wins=_required_whole(row.get("wins"), "constructor wins"),
        team=name,
    )


def _position_order(standing: DriverStanding | ConstructorStanding) -> tuple[int, int]:
    # A missing place, such as Jolpica's "-" for an ineligible driver, stays
    # after every numbered row. Wikipedia prints that dash rather than the next rank.
    if standing.position is None:
        return (1, 0)
    return (0, standing.position)


def _championship_position(row: dict, label: str) -> tuple[int | None, str]:
    position = _whole(row.get("position"))
    text = row.get("positionText")
    if isinstance(text, str) and text:
        return position, text
    if position is not None:
        return position, str(position)
    raise StandingsPayloadError(f"standings payload has no {label}")


def _required_whole(value: object, label: str) -> int:
    parsed = _whole(value)
    if parsed is None:
        raise StandingsPayloadError(f"standings payload has no {label}")
    return parsed


def _required_points(value: object) -> float:
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError as exc:
            raise StandingsPayloadError("standings points are not a number") from exc
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise StandingsPayloadError("standings points are not a number")
    return float(value)


def _whole(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.isdigit():
        return int(value)
    return None
