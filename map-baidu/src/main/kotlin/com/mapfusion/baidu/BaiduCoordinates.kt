package com.mapfusion.baidu

import com.mapfusion.api.model.CoordType
import com.mapfusion.api.model.LatLng
import kotlin.math.abs

/** 百度墨卡托坐标转 BD09 经纬度，用于兼容定位 SDK 偶发返回 bd09mc 的情况。 */
internal fun bd09MercatorToLatLng(x: Double, y: Double): LatLng {
    val factor = MC_BANDS.indices.firstOrNull { abs(y) >= MC_BANDS[it] }?.let(MC_TO_LL::get)
        ?: MC_TO_LL.last()
    val normalized = abs(y) / factor[9]
    var longitude = factor[0] + factor[1] * abs(x)
    var latitude = factor[2] + factor[3] * normalized
    var power = normalized
    for (index in 4..8) {
        power *= normalized
        latitude += factor[index] * power
    }
    longitude *= if (x < 0) -1 else 1
    latitude *= if (y < 0) -1 else 1
    return LatLng(latitude, longitude, CoordType.BD09)
}

private val MC_BANDS = doubleArrayOf(12_890_594.86, 8_362_377.87, 5_591_021.0, 3_481_989.83, 1_678_043.12, 0.0)

private val MC_TO_LL = arrayOf(
    doubleArrayOf(1.410526172116255e-8, 8.98305509648872e-6, -1.9939833816331, 200.9824383106796, -187.2403703815547, 91.6087516669843, -23.38765649603339, 2.57121317296198, -0.03801003308653, 17_337_981.2),
    doubleArrayOf(-7.435856389565537e-9, 8.983055097726239e-6, -0.78625201886289, 96.32687599759846, -1.85204757529826, -59.36935905485877, 47.40033549296737, -16.50741931063887, 2.28786674699375, 10_260_144.86),
    doubleArrayOf(-3.030883460898826e-8, 8.98305509983578e-6, 0.30071316287616, 59.74293618442277, 7.357984074871, -25.38371002664745, 13.45380521110908, -3.29883767235584, 0.32710905363475, 6_856_817.37),
    doubleArrayOf(-1.981981304930552e-8, 8.983055099779535e-6, 0.03278182852591, 40.31678527705744, 0.65659298677277, -4.44255534477492, 0.85341911805263, 0.12923347998204, -0.04625736007561, 4_482_777.06),
    doubleArrayOf(3.09191371068437e-9, 8.983055096812155e-6, 0.00006995724062, 23.10934304144901, -0.00023663490511, -0.6321817810242, -0.00663494467273, 0.03430082397953, -0.00466043876332, 2_555_164.4),
    doubleArrayOf(2.890871144776878e-9, 8.983055095805407e-6, -3.068298e-8, 7.47137025468032, -0.00000353937994, -0.02145144861037, -0.00001234426596, 0.00010322952773, -0.00000323890364, 826_088.5),
)
