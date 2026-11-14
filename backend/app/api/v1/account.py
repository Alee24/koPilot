from fastapi import APIRouter, Depends
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthSubject, require_device_or_user
from app.models import TelemetryEvent, TripRecord, UserAccount, get_db_session
from app.schemas import AccountDeleteResponse

router = APIRouter(prefix="/account", tags=["account"])


@router.delete("/data", response_model=AccountDeleteResponse, summary="Delete user/device trips, telemetry, and account data")
async def delete_account_data(
    subject: AuthSubject = Depends(require_device_or_user),
    db: AsyncSession = Depends(get_db_session),
) -> AccountDeleteResponse:
    if subject.subject_type == "user":
        trip_del = await db.execute(delete(TripRecord).where(TripRecord.user_id == subject.subject_id))
        telem_del_count = 0
        if subject.device_id:
            telem_res = await db.execute(delete(TelemetryEvent).where(TelemetryEvent.device_id == subject.device_id))
            telem_del_count = telem_res.rowcount or 0
        user_res = await db.execute(select(UserAccount).where(UserAccount.id == subject.subject_id))
        user = user_res.scalar_one_or_none()
        deleted_user = False
        if user is not None:
            await db.delete(user)
            deleted_user = True
        await db.commit()
        return AccountDeleteResponse(
            status="deleted",
            deleted_trips_count=trip_del.rowcount or 0,
            deleted_telemetry_count=telem_del_count,
            deleted_user_account=deleted_user,
        )

    trip_del = await db.execute(delete(TripRecord).where(TripRecord.device_id == subject.subject_id))
    telem_del = await db.execute(delete(TelemetryEvent).where(TelemetryEvent.device_id == subject.subject_id))
    await db.commit()
    return AccountDeleteResponse(
        status="deleted",
        deleted_trips_count=trip_del.rowcount or 0,
        deleted_telemetry_count=telem_del.rowcount or 0,
        deleted_user_account=False,
    )
