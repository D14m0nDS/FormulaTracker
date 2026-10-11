from datetime import datetime, timezone

from pydantic import BaseModel, field_serializer


def format_utc(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    else:
        value = value.astimezone(timezone.utc)
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


class Circuit(BaseModel):
    name: str
    locality: str
    country: str


class Session(BaseModel):
    type: str
    name: str
    start: datetime
    timezone: str | None = None
    cancelled: bool

    @field_serializer("start")
    def serialize_start(self, value: datetime) -> str:
        return format_utc(value)


class Event(BaseModel):
    round: int
    name: str
    circuit: Circuit
    sessions: list[Session]


class SeasonSchedule(BaseModel):
    season: int
    events: list[Event]


class Driver(BaseModel):
    code: str
    given_name: str
    family_name: str
    number: int | None = None


class SegmentResult(BaseModel):
    code: str
    position: int | None = None
    time: str | None = None


class ClassificationEntry(BaseModel):
    position: int | None = None
    position_text: str
    classified: bool
    status: str | None = None
    time: str | None = None
    driver: Driver
    team: str
    segments: list[SegmentResult]


class SessionResults(BaseModel):
    season: int
    round: int
    event_name: str
    session_type: str
    session_name: str
    start: datetime
    timezone: str | None = None
    segment_codes: list[str]
    results: list[ClassificationEntry]

    @field_serializer("start")
    def serialize_start(self, value: datetime) -> str:
        return format_utc(value)


class FastestLap(BaseModel):
    position: int | None = None
    time: str | None = None


class RaceEntry(BaseModel):
    position: int | None = None
    position_text: str
    classified: bool
    status: str | None = None
    time: str | None = None
    points: float | None = None
    laps: int | None = None
    grid: int | None = None
    fastest_lap: FastestLap | None = None
    driver: Driver
    team: str


class DriverStanding(BaseModel):
    position: int | None = None
    position_text: str
    points: float
    wins: int
    driver: Driver
    teams: list[str]


class ConstructorStanding(BaseModel):
    position: int | None = None
    position_text: str
    points: float
    wins: int
    team: str


class PracticeEntry(BaseModel):
    position: int | None = None
    position_text: str
    classified: bool
    time: str | None = None
    laps: int | None = None
    driver: Driver
    team: str


class PracticeResults(BaseModel):
    season: int
    round: int
    event_name: str
    session_type: str
    session_name: str
    start: datetime
    timezone: str | None = None
    results: list[PracticeEntry]

    @field_serializer("start")
    def serialize_start(self, value: datetime) -> str:
        return format_utc(value)


class SeasonStandings(BaseModel):
    season: int
    after_round: int | None = None
    drivers: list[DriverStanding]
    constructors: list[ConstructorStanding]


class DriverPhoto(BaseModel):
    code: str
    name: str
    photo_url: str | None = None


class SeasonDrivers(BaseModel):
    season: int
    drivers: list[DriverPhoto]


class RaceResults(BaseModel):
    season: int
    round: int
    event_name: str
    session_type: str
    session_name: str
    start: datetime
    timezone: str | None = None
    results: list[RaceEntry]

    @field_serializer("start")
    def serialize_start(self, value: datetime) -> str:
        return format_utc(value)
