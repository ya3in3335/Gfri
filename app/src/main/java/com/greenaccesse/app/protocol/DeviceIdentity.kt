package com.greenaccesse.app.protocol

/** Stable, non-secret identity of this phone. The device_id is random (not derived from hardware identifiers). */
data class DeviceIdentity(val deviceId: String, val deviceName: String, val appVersion: String)
