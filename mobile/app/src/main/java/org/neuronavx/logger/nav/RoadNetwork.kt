package org.neuronavx.logger.nav

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.math.*

/**
 * Drivable road segments from `scripts/extract_offline_roads.py`, indexed on a 50 m grid.
 *
 * Coordinates live in a local east/north frame around the file's base point. Over the
 * ~40 km Greater Noida extent the equirectangular scale error is below 0.4%, far below
 * what matters for a road direction.
 */
class RoadNetwork private constructor(
    private val baseLat: Double,
    private val baseLon: Double,
    private val ax: FloatArray, private val ay: FloatArray,
    private val bx: FloatArray, private val by: FloatArray,
    private val oneway: BooleanArray,
    private val grid: HashMap<Long, IntArray>,
) {
    private val lonScale = cos(Math.toRadians(baseLat))

    val segmentCount: Int get() = ax.size

    /** A segment near a query point, with its drawn direction (clockwise from north). */
    data class Nearby(val lateralM: Double, val headingRad: Double, val lengthM: Double, val oneway: Boolean)

    fun near(lat: Double, lon: Double, radiusM: Double): List<Nearby> {
        val px = east(lon); val py = north(lat)
        val c0x = floor((px - radiusM) / CELL_M).toLong(); val c1x = floor((px + radiusM) / CELL_M).toLong()
        val c0y = floor((py - radiusM) / CELL_M).toLong(); val c1y = floor((py + radiusM) / CELL_M).toLong()
        val seen = HashSet<Int>()
        val out = ArrayList<Nearby>()
        for (cx in c0x..c1x) for (cy in c0y..c1y) {
            val cell = grid[key(cx, cy)] ?: continue
            for (i in cell) {
                if (!seen.add(i)) continue
                val dx = (bx[i] - ax[i]).toDouble(); val dy = (by[i] - ay[i]).toDouble()
                val len2 = dx * dx + dy * dy
                val f = (((px - ax[i]) * dx + (py - ay[i]) * dy) / len2).coerceIn(0.0, 1.0)
                val lateral = hypot(px - ax[i] - f * dx, py - ay[i] - f * dy)
                if (lateral <= radiusM) out += Nearby(lateral, atan2(dx, dy), sqrt(len2), oneway[i])
            }
        }
        return out
    }

    private fun east(lon: Double) = Math.toRadians(lon - baseLon) * EARTH_RADIUS_M * lonScale
    private fun north(lat: Double) = Math.toRadians(lat - baseLat) * EARTH_RADIUS_M

    companion object {
        const val ASSET = "offline_roads_greater_noida.bin"
        private const val EARTH_RADIUS_M = 6378137.0
        private const val CELL_M = 50.0

        private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)

        fun read(stream: InputStream): RoadNetwork {
            val input = DataInputStream(BufferedInputStream(stream, 1 shl 16))
            val magic = ByteArray(4).also(input::readFully)
            require(String(magic, Charsets.US_ASCII) == "WNRD") { "Not a Waynova road file" }
            require(input.readUnsignedByte() == 1) { "Unsupported road file version" }
            val baseLatE6 = Integer.reverseBytes(input.readInt())
            val baseLonE6 = Integer.reverseBytes(input.readInt())
            val lines = Integer.reverseBytes(input.readInt())
            require(lines in 0..2_000_000) { "Corrupt road file" }
            val baseLat = baseLatE6 / 1e6; val baseLon = baseLonE6 / 1e6
            val lonScale = cos(Math.toRadians(baseLat))
            fun varint(): Long {
                var result = 0L; var shift = 0
                while (true) {
                    val b = input.read(); if (b < 0) throw EOFException("Truncated road file")
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7; require(shift < 64) { "Corrupt road file" }
                }
            }
            fun zigzag(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }
            val ax = FloatList(); val ay = FloatList(); val bx = FloatList(); val by = FloatList()
            val ways = ArrayList<Boolean>()
            repeat(lines) {
                val isOneway = input.readUnsignedByte() and 1 == 1
                val count = varint().toInt()
                require(count in 2..100_000) { "Corrupt road file" }
                var lat = baseLatE6.toLong(); var lon = baseLonE6.toLong()
                var px = 0f; var py = 0f
                for (p in 0 until count) {
                    lat += zigzag(); lon += zigzag()
                    val x = (Math.toRadians((lon - baseLonE6) / 1e6) * EARTH_RADIUS_M * lonScale).toFloat()
                    val y = (Math.toRadians((lat - baseLatE6) / 1e6) * EARTH_RADIUS_M).toFloat()
                    if (p > 0 && hypot((x - px).toDouble(), (y - py).toDouble()) >= 0.5) {
                        ax.add(px); ay.add(py); bx.add(x); by.add(y); ways += isOneway
                    }
                    if (p == 0 || hypot((x - px).toDouble(), (y - py).toDouble()) >= 0.5) { px = x; py = y }
                }
            }
            val cells = HashMap<Long, IntList>()
            for (i in 0 until ax.size) {
                val x0 = floor(min(ax[i], bx[i]) / CELL_M).toLong(); val x1 = floor(max(ax[i], bx[i]) / CELL_M).toLong()
                val y0 = floor(min(ay[i], by[i]) / CELL_M).toLong(); val y1 = floor(max(ay[i], by[i]) / CELL_M).toLong()
                for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { IntList() }.add(i)
            }
            val grid = HashMap<Long, IntArray>(cells.size * 2)
            for ((k, v) in cells) grid[k] = v.toArray()
            return RoadNetwork(baseLat, baseLon, ax.toArray(), ay.toArray(), bx.toArray(), by.toArray(),
                ways.toBooleanArray(), grid)
        }

        /** Build a network from lat/lon polylines, for tests and synthetic roads. */
        fun of(roads: List<RoadLine>): RoadNetwork {
            val all = roads.flatMap { it.points }
            require(all.isNotEmpty())
            val bytes = java.io.ByteArrayOutputStream()
            val baseLat = (all.sumOf { it.lat } / all.size * 1e6).roundToInt()
            val baseLon = (all.sumOf { it.lon } / all.size * 1e6).roundToInt()
            fun varint(v: Long) { var x = v; while (true) { val b = (x and 0x7f).toInt(); x = x ushr 7
                if (x != 0L) bytes.write(b or 0x80) else { bytes.write(b); return } } }
            fun zig(v: Long) = varint((v shl 1) xor (v shr 63))
            bytes.write("WNRD".toByteArray()); bytes.write(1)
            fun int(v: Int) = bytes.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            int(baseLat); int(baseLon); int(roads.size)
            for (road in roads) {
                bytes.write(if (road.oneway) 1 else 0); varint(road.points.size.toLong())
                var pLat = baseLat.toLong(); var pLon = baseLon.toLong()
                for (p in road.points) {
                    val la = (p.lat * 1e6).roundToLong(); val lo = (p.lon * 1e6).roundToLong()
                    zig(la - pLat); zig(lo - pLon); pLat = la; pLon = lo
                }
            }
            return read(bytes.toByteArray().inputStream())
        }
    }

    private class FloatList {
        private var data = FloatArray(1024); var size = 0; private set
        fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        operator fun get(i: Int) = data[i]
        fun toArray() = data.copyOf(size)
    }

    private class IntList {
        private var data = IntArray(4); private var size = 0
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray() = data.copyOf(size)
    }
}
