import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import AuditMixin, Base, SoftDeleteMixin


class DeviceSession(Base, AuditMixin, SoftDeleteMixin):
    """Tracks Android device installations for rate limiting, metering, and optional user link."""

    __tablename__ = "device_sessions"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    device_id: Mapped[str] = mapped_column(String(128), unique=True, nullable=False, index=True)
    user_id: Mapped[str | None] = mapped_column(
        String(36),
        ForeignKey("user_accounts.id", ondelete="SET NULL"),
        nullable=True,
    )
    app_version: Mapped[str] = mapped_column(String(32), default="1.0.0", nullable=False)
    android_sdk: Mapped[int | None] = mapped_column(nullable=True)
    device_model: Mapped[str | None] = mapped_column(String(128), nullable=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    user: Mapped["UserAccount | None"] = relationship(back_populates="devices")

    __table_args__ = (
        Index("ix_device_sessions_user_id", "user_id"),
    )
