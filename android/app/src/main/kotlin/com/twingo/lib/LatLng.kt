package com.twingo.lib

import kotlin.math.abs

private const val PRECISION = 1e-9

data class LatLng(
    val lat: Double,
    val lng: Double
) {
    override fun toString(): String {
        return "$lat,$lng"
    }
}
