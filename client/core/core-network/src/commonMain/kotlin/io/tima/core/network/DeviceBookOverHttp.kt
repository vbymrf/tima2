package io.tima.core.network

import io.tima.domain.account.AccountDevice
import io.tima.domain.account.DeviceBook
import io.tima.domain.account.DevicesStep
import io.tima.domain.account.RevokeStep

/** Свои устройства по HTTP — переходник к порту домена. */
class DeviceBookOverHttp(private val api: DevicesApi) : DeviceBook {

    override suspend fun mine(): DevicesStep = when (val answer = api.mine()) {
        is MyDevicesResult.Devices -> DevicesStep.Devices(
            answer.devices.map { AccountDevice(it.deviceId, it.name, it.createdAt, it.current) },
        )
        is MyDevicesResult.NoConnection -> DevicesStep.Offline(answer.link.retryDelayMs)
        is MyDevicesResult.Refused -> DevicesStep.Refused(answer.code)
    }

    override suspend fun revoke(deviceId: String): RevokeStep = when (val answer = api.revoke(deviceId)) {
        RevokeResult.Revoked -> RevokeStep.Revoked
        RevokeResult.LastDevice -> RevokeStep.LastDevice
        RevokeResult.Gone -> RevokeStep.Gone
        is RevokeResult.NoConnection -> RevokeStep.Offline(answer.link.retryDelayMs)
        is RevokeResult.Refused -> RevokeStep.Refused(answer.code)
    }
}
