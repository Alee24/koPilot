import pytest


@pytest.mark.asyncio
async def test_device_token_and_user_auth_lifecycle(client):
    # 1. Anonymous device registration
    dev_resp = await client.post(
        "/api/v1/auth/device",
        json={"device_id": "android-device-123456", "app_version": "1.0.0", "android_sdk": 35},
    )
    assert dev_resp.status_code == 200
    dev_data = dev_resp.json()
    assert dev_data["subject_type"] == "device"
    assert dev_data["access_token"]

    # 2. Optional user account registration
    reg_resp = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "driver@kopilot.co.ke",
            "password": "StrongPassword123!",
            "display_name": "Amina",
            "device_id": "android-device-123456",
        },
    )
    assert reg_resp.status_code == 201
    user_token = reg_resp.json()["access_token"]
    headers = {"Authorization": f"Bearer {user_token}"}

    # 3. Duplicate email rejected
    dup_resp = await client.post(
        "/api/v1/auth/register",
        json={"email": "driver@kopilot.co.ke", "password": "StrongPassword123!"},
    )
    assert dup_resp.status_code == 409

    # 4. Login works
    login_resp = await client.post(
        "/api/v1/auth/login",
        json={"email": "driver@kopilot.co.ke", "password": "StrongPassword123!"},
    )
    assert login_resp.status_code == 200

    # 5. Preferences update & read
    put_resp = await client.put(
        "/api/v1/auth/preferences",
        headers=headers,
        json={
            "language": "sw",
            "distance_unit": "km",
            "voice_name": "marin",
            "speed_alert_threshold_kmh": 10,
            "hazard_advisories_enabled": False,
            "telemetry_opt_in": False,
            "extra": {"auto_mute_in_calls": True},
        },
    )
    assert put_resp.status_code == 200
    get_resp = await client.get("/api/v1/auth/preferences", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["language"] == "sw"
    assert get_resp.json()["speed_alert_threshold_kmh"] == 10

    # 6. Delete account & data
    del_resp = await client.delete("/api/v1/account/data", headers=headers)
    assert del_resp.status_code == 200
    assert del_resp.json()["deleted_user_account"] is True
