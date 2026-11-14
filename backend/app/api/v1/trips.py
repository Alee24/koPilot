from fastapi import APIRouter, Depends, Query, status
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthSubject, require_device_or_user
from app.models import TripRecord, get_db_session
from app.schemas import TripCreateRequest, TripResponse
from app.services import create_trip_record

router = APIRouter(prefix="/trips", tags=["trips"])


@router.get("", response_model=list[TripResponse], summary="List saved trips for current device or user")
async def list_trips(
    limit: int = Query(default=50, ge=1, le=200),
    subject: AuthSubject = Depends(require_device_or_user),
    db: AsyncSession = Depends(get_db_session),
) -> list[TripResponse]:
    stmt = select(TripRecord).where(TripRecord.is_deleted.is_(False))
    if subject.subject_type == "user":
        stmt = stmt.where(TripRecord.user_id == subject.subject_id)
    else:
        stmt = stmt.where(TripRecord.device_id == subject.subject_id)
    stmt = stmt.order_by(desc(TripRecord.started_at)).limit(limit)
    result = await db.execute(stmt)
    trips = result.scalars().all()
    return [TripResponse.model_validate(t) for t in trips]


@router.post("", response_model=TripResponse, status_code=status.HTTP_201_CREATED, summary="Save a trip summary")
async def create_trip(
    payload: TripCreateRequest,
    subject: AuthSubject = Depends(require_device_or_user),
    db: AsyncSession = Depends(get_db_session),
) -> TripResponse:
    user_id = subject.subject_id if subject.subject_type == "user" else None
    trip = await create_trip_record(
        db=db,
        device_id=payload.device_id,
        user_id=user_id,
        origin_label=payload.origin_label,
        destination_label=payload.destination_label,
        started_at=payload.started_at,
        ended_at=payload.ended_at,
        distance_km=payload.distance_km,
        duration_seconds=payload.duration_seconds,
        max_speed_kmh=payload.max_speed_kmh,
        avg_speed_kmh=payload.avg_speed_kmh,
        signs_detected_count=payload.signs_detected_count,
        alerts_triggered_count=payload.alerts_triggered_count,
    )
    return TripResponse.model_validate(trip)
