from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import get_db_session
from app.schemas import TelemetryEventRequest
from app.services import record_telemetry_event

router = APIRouter(prefix="/telemetry", tags=["telemetry"])


@router.post("/events", status_code=status.HTTP_202_ACCEPTED, summary="Record privacy-conscious telemetry event")
async def ingest_telemetry_event(
    payload: TelemetryEventRequest,
    db: AsyncSession = Depends(get_db_session),
) -> dict[str, str]:
    event = await record_telemetry_event(
        db=db,
        device_id=payload.device_id,
        event_type=payload.event_type,
        severity=payload.severity,
        app_version=payload.app_version,
        payload=payload.payload,
    )
    return {"status": "accepted", "event_id": event.id}
