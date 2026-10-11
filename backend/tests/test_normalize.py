import json
from datetime import datetime, timezone
from pathlib import Path

import pytest

from app.normalize import (
    ProviderPayloadError,
    normalize_schedule,
    normalize_session_results,
    read_schedule,
)

FIXTURE = Path(__file__).parent / "fixtures" / "jolpica_schedule_2026.json"


def load_fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def test_conventional_weekend_collapses_qualifying_segments():
    schedule = normalize_schedule(load_fixture())
    australia = schedule.events[0]

    assert schedule.season == 2026
    assert australia.round == 1
    assert australia.name == "Australian Grand Prix"
    assert australia.circuit.locality == "Melbourne"
    assert [session.type for session in australia.sessions] == ["FP1", "FP2", "FP3", "Q", "R"]
    assert australia.sessions[3].name == "Qualifying"
    assert australia.sessions[3].start == datetime(2026, 3, 7, 5, 0, tzinfo=timezone.utc)
    assert australia.sessions[3].timezone == "Australia/Melbourne"
    assert australia.sessions[3].cancelled is False


def test_sprint_weekend_omits_sessions_the_provider_did_not_list():
    china = normalize_schedule(load_fixture()).events[1]

    assert [session.type for session in china.sessions] == ["FP1", "SQ", "SR", "Q", "R"]
    assert china.sessions[1].name == "Sprint Qualifying"
    assert china.sessions[1].timezone == "Asia/Shanghai"


def test_provider_ids_and_metadata_timestamp_stay_out_of_the_model():
    dumped = normalize_schedule(load_fixture()).model_dump_json()

    assert "round_hidden" not in dumped
    assert "session_hidden" not in dumped
    assert "circuit_hidden" not in dumped
    assert "wikipedia" not in dumped
    assert "2026-10-11T17:29:10" not in dumped


def test_sessions_are_ordered_by_start_time():
    payload = _payload(
        [
            _event(
                1,
                "Reversed Grand Prix",
                [
                    _slot("R", "Race", "2026-03-08T04:00:00Z"),
                    _slot("FP1", "Practice 1", "2026-03-06T01:30:00Z"),
                ],
            )
        ]
    )

    sessions = normalize_schedule(payload).events[0].sessions
    assert [session.type for session in sessions] == ["FP1", "R"]


def test_cancelled_round_marks_every_session_cancelled():
    payload = _payload(
        [
            _event(
                4,
                "Cancelled Grand Prix",
                [_slot("R", "Race", "2026-05-01T13:00:00Z")],
                cancelled=True,
            )
        ]
    )

    assert normalize_schedule(payload).events[0].sessions[0].cancelled is True


def test_slot_is_cancelled_only_when_every_segment_is_cancelled():
    partial = _payload(
        [
            _event(
                1,
                "Partial Grand Prix",
                [
                    _slot(
                        "Q",
                        "Qualifying",
                        "2026-03-07T05:00:00Z",
                        parts=[
                            {"type": "Q1", "is_cancelled": True},
                            {"type": "Q2", "is_cancelled": False},
                        ],
                    )
                ],
            )
        ]
    )
    full = _payload(
        [
            _event(
                1,
                "Fully Cancelled Qualifying",
                [
                    _slot(
                        "Q",
                        "Qualifying",
                        "2026-03-07T05:00:00Z",
                        parts=[
                            {"type": "Q1", "is_cancelled": True},
                            {"type": "Q3", "is_cancelled": True},
                        ],
                    )
                ],
            )
        ]
    )

    assert normalize_schedule(partial).events[0].sessions[0].cancelled is False
    assert normalize_schedule(full).events[0].sessions[0].cancelled is True


def test_slot_without_a_start_time_is_omitted():
    payload = _payload(
        [
            _event(
                1,
                "Incomplete Grand Prix",
                [
                    _slot("FP1", "Practice 1", "2026-03-06T01:30:00Z"),
                    {"code": "FP2", "title": "Practice 2", "sessions": []},
                ],
            )
        ]
    )

    assert [session.type for session in normalize_schedule(payload).events[0].sessions] == ["FP1"]


def test_unknown_session_code_is_kept():
    payload = _payload([_event(1, "Future Format", [_slot("WARMUP", "Warm Up", "2026-03-06T00:00:00Z")])])

    assert normalize_schedule(payload).events[0].sessions[0].type == "WARMUP"


def test_malformed_payload_is_rejected():
    with pytest.raises(ProviderPayloadError):
        normalize_schedule({})


def test_provider_round_index_keeps_ids_for_lookup_only():
    _schedule, rounds = read_schedule(load_fixture())

    assert rounds[1].provider_id == "round_hidden_australia"
    assert "Q" in rounds[1].sessions
    assert "SQ" not in rounds[1].sessions
    assert "SQ" in rounds[2].sessions


def test_qualifying_results_keep_segment_order_and_omit_missing_segments():
    results = normalize_session_results(
        json.loads((FIXTURE.parent / "jolpica_qualifying_2026_r1.json").read_text(encoding="utf-8")),
        season=2026,
        round_number=1,
        session_type="Q",
    )

    russell, alonso, verstappen = results.results
    assert results.session_type == "Q"
    assert results.segment_codes == ["Q1", "Q2", "Q3"]
    assert [segment.code for segment in russell.segments] == ["Q1", "Q2", "Q3"]
    assert [segment.code for segment in alonso.segments] == ["Q1"]
    assert verstappen.classified is False
    assert verstappen.position is None
    assert verstappen.position_text == "NC"
    assert verstappen.status == "ACCIDENT"
    assert verstappen.segments[0].time is None
    dumped = results.model_dump_json()
    assert "driver_hidden" not in dumped
    assert "team_hidden" not in dumped
    assert "wikipedia" not in dumped


def test_sprint_qualifying_uses_the_same_segment_shape():
    results = normalize_session_results(
        json.loads(
            (FIXTURE.parent / "jolpica_sprint_qualifying_2026_r2.json").read_text(encoding="utf-8")
        ),
        season=2026,
        round_number=2,
        session_type="SQ",
    )

    assert results.event_name == "Chinese Grand Prix"
    assert results.results[0].segments[2].code == "SQ3"
    assert results.results[0].segments[2].time == "1:31.520"


def _payload(events: list[dict]) -> dict:
    return {
        "metadata": {"timestamp": "2026-10-11T00:00:00Z"},
        "data": {"year": 2026, "events": events},
    }


def _event(number: int, name: str, slots: list[dict], *, cancelled: bool = False) -> dict:
    return {
        "round": {"id": "round_secret", "number": number, "name": name, "is_cancelled": cancelled},
        "circuit": {
            "id": "circuit_secret",
            "name": "Test Circuit",
            "locality": "Test City",
            "country": "Testland",
        },
        "schedule": slots,
    }


def _slot(code: str, title: str, timestamp: str, parts: list[dict] | None = None) -> dict:
    return {
        "code": code,
        "title": title,
        "timestamp": timestamp,
        "timezone": "UTC",
        "sessions": parts if parts is not None else [{"id": "session_secret", "type": code, "is_cancelled": False}],
    }
