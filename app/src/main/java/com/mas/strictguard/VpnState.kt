package com.mas.strictguard

import java.util.concurrent.atomic.AtomicInteger

/** Status mesin VPN DNS. Dipisah dari Mode Ketat: keduanya berdiri sendiri. */
enum class VpnState(val label: String) {
    STOPPED("Mati"),
    NEED_PERMISSION("Perlu izin VPN"),
    WAITING_NETWORK("Menunggu jaringan"),
    STARTING("Menyambung"),
    RUNNING("Aktif"),
    RECONNECTING("Menyambung ulang"),
    FAILSAFE("Dijeda otomatis (aman)")
}

object VpnStatus {
    @Volatile
    var state: VpnState = VpnState.STOPPED

    @Volatile
    var detail: String = ""

    val qTotal = AtomicInteger(0)
    val qBlocked = AtomicInteger(0)
    val qForwarded = AtomicInteger(0)
    val qFailed = AtomicInteger(0)
    val qCached = AtomicInteger(0)

    val running: Boolean get() = state == VpnState.RUNNING

    fun resetCounters() {
        qTotal.set(0)
        qBlocked.set(0)
        qForwarded.set(0)
        qFailed.set(0)
        qCached.set(0)
    }

    fun summary(): String = state.label + if (detail.isNotEmpty()) " ($detail)" else ""
}
