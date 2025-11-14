package com.twingo.lib

import org.apache.commons.math3.geometry.euclidean.twod.Vector2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class OverpassWay(val name: String, val speedLimit: String, private val nodes: List<LatLng>) {
    fun getDistanceToPoint(point: LatLng): Double {
        var distance = Double.MAX_VALUE

        for (index in 1..<this.nodes.size) {
            distance =
                min(distance, getDistanceToLine(point, this.nodes[index - 1], this.nodes[index]))
        }
        return distance
    }

    private fun getDistanceToLine(p: LatLng, a: LatLng, b: LatLng): Double {
        // Computes Q the point on AB that is closest to P
        val ap = Vector2D(p.lat, p.lng).subtract(Vector2D(a.lat, a.lng))
        val ab = Vector2D(b.lat, b.lng).subtract(Vector2D(a.lat, a.lng))
        var t = ap.dotProduct(ab) / ab.norm.pow(2)
        t = min(max(0.toDouble(), t), 1.toDouble())
        val q = Vector2D(a.lat, a.lng).add(ab.scalarMultiply(t))

        val pq = q.subtract(Vector2D(p.lat, p.lng))
        return pq.norm / 360 * 12756000 // Converts the distance in degrees to meters
    }
}
