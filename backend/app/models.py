from datetime import datetime, timezone

from pydantic import BaseModel, field_serializer


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
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        else:
            value = value.astimezone(timezone.utc)
        return value.strftime("%Y-%m-%dT%H:%M:%SZ")


class Event(BaseModel):
    round: int
    name: str
    circuit: Circuit
    sessions: list[Session]


class SeasonSchedule(BaseModel):
    season: int
    events: list[Event]
