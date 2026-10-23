package com.mas.strictguard

data class SiteCfg(
    val mode: Int = 0,
    val schOn: Boolean = false,
    val start: Int = 21 * 60,
    val end: Int = 5 * 60,
    val days: Int = 127,
    val limitMin: Int = 0,
    val hourLimit: Int = 0,
    val twUse: Int = 0,
    val twWait: Int = 0,
    val twAdaptive: Boolean = true
) {
    /** True bila situs diblokir penuh (mode 0), atau hanya dibatasi jadwal tanpa batas pemakaian/timer tunggu. */
    val blocksFully: Boolean
        get() = mode == 0 || (schOn && limitMin <= 0 && hourLimit <= 0 && (twUse <= 0 || twWait <= 0))
}
