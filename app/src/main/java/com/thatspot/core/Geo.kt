package com.thatspot.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** 지도 계산. 몇 km 안의 거리만 다루므로 구면 공식으로 충분하다. */
object Geo {
    private const val R = 6_371_000.0

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: LatLon, b: LatLon) = distanceM(a.lat, a.lon, b.lat, b.lon)

    /** a 에서 b 를 바라보는 방위각 (0 = 북, 90 = 동). */
    fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
        val dl = Math.toRadians(bLon - aLon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** 두 방위각의 차이 (0..180). */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs((a - b) % 360)
        return if (d > 180) 360 - d else d
    }

    /** 방위각 평균 (가중치 w 는 b 쪽). 359도와 1도의 평균이 0도가 되도록 벡터로 더한다. */
    fun blendHeading(a: Double, b: Double, w: Double): Double {
        val x = (1 - w) * cos(Math.toRadians(a)) + w * cos(Math.toRadians(b))
        val y = (1 - w) * sin(Math.toRadians(a)) + w * sin(Math.toRadians(b))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** 시작점에서 방위각 방향으로 거리만큼 간 점. 시연 경로를 만들 때 쓴다. */
    fun offset(p: LatLon, bearingDeg: Double, meters: Double): LatLon {
        val br = Math.toRadians(bearingDeg)
        val dLat = meters * cos(br) / R
        val dLon = meters * sin(br) / (R * cos(Math.toRadians(p.lat)))
        return LatLon(p.lat + Math.toDegrees(dLat), p.lon + Math.toDegrees(dLon))
    }
}
