"""Initial KoPilot database schema.

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-11-14
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0001_initial_schema"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "user_accounts",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("email", sa.String(length=255), nullable=False, unique=True),
        sa.Column("password_hash", sa.String(length=255), nullable=False),
        sa.Column("display_name", sa.String(length=120), nullable=True),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("created_by", sa.String(length=64), nullable=True),
        sa.Column("updated_by", sa.String(length=64), nullable=True),
        sa.Column("is_deleted", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("length(email) >= 5", name="ck_user_email_min_len"),
    )
    op.create_index("ix_user_accounts_email", "user_accounts", ["email"])
    op.create_index("ix_user_accounts_is_deleted", "user_accounts", ["is_deleted"])

    op.create_table(
        "user_preferences",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("user_id", sa.String(length=36), sa.ForeignKey("user_accounts.id", ondelete="CASCADE"), nullable=False, unique=True),
        sa.Column("language", sa.String(length=16), nullable=False, server_default="en"),
        sa.Column("distance_unit", sa.String(length=8), nullable=False, server_default="km"),
        sa.Column("voice_name", sa.String(length=32), nullable=False, server_default="marin"),
        sa.Column("speed_alert_threshold_kmh", sa.Integer(), nullable=False, server_default="5"),
        sa.Column("hazard_advisories_enabled", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("telemetry_opt_in", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("preferences_json", sa.Text(), nullable=False, server_default="{}"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("created_by", sa.String(length=64), nullable=True),
        sa.Column("updated_by", sa.String(length=64), nullable=True),
        sa.CheckConstraint("speed_alert_threshold_kmh >= 0 AND speed_alert_threshold_kmh <= 40", name="ck_pref_speed_threshold"),
    )
    op.create_index("ix_user_preferences_user_id", "user_preferences", ["user_id"])

    op.create_table(
        "device_sessions",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("device_id", sa.String(length=128), nullable=False, unique=True),
        sa.Column("user_id", sa.String(length=36), sa.ForeignKey("user_accounts.id", ondelete="SET NULL"), nullable=True),
        sa.Column("app_version", sa.String(length=32), nullable=False, server_default="1.0.0"),
        sa.Column("android_sdk", sa.Integer(), nullable=True),
        sa.Column("device_model", sa.String(length=128), nullable=True),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("created_by", sa.String(length=64), nullable=True),
        sa.Column("updated_by", sa.String(length=64), nullable=True),
        sa.Column("is_deleted", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_device_sessions_device_id", "device_sessions", ["device_id"])
    op.create_index("ix_device_sessions_user_id", "device_sessions", ["user_id"])
    op.create_index("ix_device_sessions_is_deleted", "device_sessions", ["is_deleted"])

    op.create_table(
        "trip_records",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("device_id", sa.String(length=128), sa.ForeignKey("device_sessions.device_id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", sa.String(length=36), sa.ForeignKey("user_accounts.id", ondelete="SET NULL"), nullable=True),
        sa.Column("origin_label", sa.String(length=255), nullable=True),
        sa.Column("destination_label", sa.String(length=255), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("distance_km", sa.Float(), nullable=False, server_default="0"),
        sa.Column("duration_seconds", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("max_speed_kmh", sa.Float(), nullable=False, server_default="0"),
        sa.Column("avg_speed_kmh", sa.Float(), nullable=False, server_default="0"),
        sa.Column("signs_detected_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("alerts_triggered_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("created_by", sa.String(length=64), nullable=True),
        sa.Column("updated_by", sa.String(length=64), nullable=True),
        sa.Column("is_deleted", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("distance_km >= 0", name="ck_trip_distance_non_neg"),
        sa.CheckConstraint("duration_seconds >= 0", name="ck_trip_duration_non_neg"),
    )
    op.create_index("ix_trip_records_device_id", "trip_records", ["device_id"])
    op.create_index("ix_trip_records_user_id", "trip_records", ["user_id"])
    op.create_index("ix_trip_records_started_at", "trip_records", ["started_at"])
    op.create_index("ix_trip_records_is_deleted", "trip_records", ["is_deleted"])

    op.create_table(
        "telemetry_events",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("device_id", sa.String(length=128), sa.ForeignKey("device_sessions.device_id", ondelete="CASCADE"), nullable=False),
        sa.Column("event_type", sa.String(length=64), nullable=False),
        sa.Column("severity", sa.String(length=16), nullable=False, server_default="info"),
        sa.Column("app_version", sa.String(length=32), nullable=False, server_default="1.0.0"),
        sa.Column("payload_json", sa.Text(), nullable=False, server_default="{}"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("created_by", sa.String(length=64), nullable=True),
        sa.Column("updated_by", sa.String(length=64), nullable=True),
    )
    op.create_index("ix_telemetry_events_device_id", "telemetry_events", ["device_id"])
    op.create_index("ix_telemetry_events_event_type", "telemetry_events", ["event_type"])

    op.create_table(
        "usage_meters",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("device_id", sa.String(length=128), sa.ForeignKey("device_sessions.device_id", ondelete="CASCADE"), nullable=False),
        sa.Column("resource_type", sa.String(length=32), nullable=False),
        sa.Column("units", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("created_by", sa.String(length=64), nullable=True),
        sa.Column("updated_by", sa.String(length=64), nullable=True),
        sa.CheckConstraint("units > 0", name="ck_usage_units_pos"),
    )
    op.create_index("ix_usage_meters_device_resource", "usage_meters", ["device_id", "resource_type"])


def downgrade() -> None:
    op.drop_table("usage_meters")
    op.drop_table("telemetry_events")
    op.drop_table("trip_records")
    op.drop_table("device_sessions")
    op.drop_table("user_preferences")
    op.drop_table("user_accounts")
