from contextlib import asynccontextmanager
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Path, Request

from app.jolpica import (
    JolpicaClient,
    ProviderUnavailable,
    ResultsNotFound,
    ScheduleService,
    SeasonNotFound,
    StandingsNotFound,
)
from app.models import PracticeResults, RaceResults, SeasonSchedule, SeasonStandings, SessionResults
from app.results import ResultsService, RoundNotFound, SessionNotScheduled
from app.standings import StandingsService


def create_app(
    service: ScheduleService | None = None,
    results: ResultsService | None = None,
    standings: StandingsService | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if service is None:
            client = JolpicaClient()
            app.state.schedules = ScheduleService(client)
            app.state.results = ResultsService(app.state.schedules, client)
            app.state.standings = StandingsService(client)
            yield
            await app.state.schedules.aclose()
        else:
            app.state.schedules = service
            app.state.results = results
            app.state.standings = standings
            yield

    app = FastAPI(title="FormulaTracker", lifespan=lifespan)

    def get_schedules(request: Request) -> ScheduleService:
        return request.app.state.schedules

    def get_results(request: Request) -> ResultsService:
        return request.app.state.results

    def get_standings(request: Request) -> StandingsService:
        return request.app.state.standings

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/seasons/{year}/schedule", response_model=SeasonSchedule)
    async def season_schedule(
        year: int = Path(ge=1950, le=2100),
        schedules: ScheduleService = Depends(get_schedules),
    ) -> SeasonSchedule:
        try:
            return await schedules.get_schedule(year)
        except SeasonNotFound:
            raise HTTPException(status_code=404, detail="Season not found") from None
        except ProviderUnavailable:
            raise HTTPException(status_code=502, detail="Schedule provider unavailable") from None

    @app.get("/seasons/{year}/standings", response_model=SeasonStandings)
    async def season_standings(
        year: int = Path(ge=1950, le=2100),
        standings_service: StandingsService = Depends(get_standings),
    ) -> SeasonStandings:
        try:
            return await standings_service.get_standings(year)
        except StandingsNotFound:
            raise HTTPException(status_code=404, detail="Standings not found") from None
        except ProviderUnavailable:
            raise HTTPException(status_code=502, detail="Standings provider unavailable") from None

    @app.get(
        "/seasons/{year}/rounds/{round}/sessions/{session_type}/results",
        response_model=SessionResults | RaceResults | PracticeResults,
    )
    async def session_results(
        year: int = Path(ge=1950, le=2100),
        round: int = Path(ge=1, le=30),
        session_type: Literal["Q", "SQ", "R", "SR", "FP1", "FP2", "FP3"] = Path(),
        results_service: ResultsService = Depends(get_results),
    ) -> SessionResults | RaceResults | PracticeResults:
        try:
            return await results_service.get_results(year, round, session_type)
        except SeasonNotFound:
            raise HTTPException(status_code=404, detail="Season not found") from None
        except RoundNotFound:
            raise HTTPException(status_code=404, detail="Round not found") from None
        except SessionNotScheduled:
            raise HTTPException(status_code=404, detail="Session not on this round") from None
        except ResultsNotFound:
            raise HTTPException(status_code=404, detail="Results not found") from None
        except ProviderUnavailable:
            raise HTTPException(status_code=502, detail="Results provider unavailable") from None

    return app


app = create_app()
