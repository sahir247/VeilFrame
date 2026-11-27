package com.veilframe.app.cv.motion

/** Freeze source display geometry before opening either native pipe. */
internal data class MotionLabSourceGeometry(
    val width: Int, val height: Int, val rotation: Int, val sarWidth: Int = 1, val sarHeight: Int = 1
) {
    init {
        require(width in 1..16384 && height in 1..16384 && rotation in setOf(0,90,180,270)) {
            "Unsupported source video geometry or rotation"
        }
        require(sarWidth in 1..65535 && sarHeight in 1..65535) { "Invalid source pixel aspect ratio" }
    }
    data class Plan(val geometry: MotionLabRawFramePipe.Geometry, val decodeFilter: String, val encodeFilter: String)

    fun plan(rife: Boolean): Plan {
        val swap = rotation == 90 || rotation == 270
        val orientedWidth = if (swap) height else width
        val orientedHeight = if (swap) width else height
        val sample = if (rife) MotionLabFrameSampling.sampleSize(orientedWidth,orientedHeight) else 1
        val w = (orientedWidth+sample-1)/sample
        val h = (orientedHeight+sample-1)/sample
        val geometry = MotionLabRawFramePipe.Geometry(w,h)
        val rotationFilter = when(rotation) {
            90 -> "transpose=clock,"
            180 -> "hflip,vflip,"
            270 -> "transpose=cclock,"
            else -> ""
        }
        val paddedW = (w+1)/2*2
        val paddedH = (h+1)/2*2
        val sw = if (swap) sarHeight else sarWidth
        val sh = if (swap) sarWidth else sarHeight
        // Preserve display aspect including the explicit one-pixel codec pad.
        val n = orientedWidth.toLong()*sw*paddedH
        val d = orientedHeight.toLong()*sh*paddedW
        fun gcd(a: Long,b: Long): Long = if (b == 0L) a else gcd(b,a%b)
        val divisor = gcd(n,d)
        return Plan(geometry, "${rotationFilter}scale=$w:$h:flags=lanczos",
            "pad=$paddedW:$paddedH,setsar=${n/divisor}/${d/divisor}:max=65535")
    }
}
