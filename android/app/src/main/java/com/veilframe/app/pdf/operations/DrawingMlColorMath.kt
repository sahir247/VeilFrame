package com.veilframe.app.pdf.operations

/** Shared sRGB/HSL lightness math for saved DrawingML colors.
 * Channels are normalized to [0,1]; no intermediate byte rounding.
 */
internal object DrawingMlColorMath {
    fun lightness(rgb: DoubleArray,transform: (Double)->Double): DoubleArray {
        require(rgb.size==3 && rgb.all { it.isFinite() && it in 0.0..1.0 })
        val red=rgb[0]; val green=rgb[1]; val blue=rgb[2]
        val max=maxOf(red,green,blue); val min=minOf(red,green,blue)
        val delta=max-min; val light=(max+min)/2
        val saturation=if(delta==0.0) 0.0 else delta/(1-kotlin.math.abs(2*light-1))
        val hue=if(delta==0.0) 0.0 else when(max) {
            red -> ((green-blue)/delta+6)%6
            green -> (blue-red)/delta+2
            else -> (red-green)/delta+4
        }
        val next=transform(light).also { require(it.isFinite()) }.coerceIn(0.0,1.0)
        val chroma=(1-kotlin.math.abs(2*next-1))*saturation
        val x=chroma*(1-kotlin.math.abs(hue%2-1))
        val m=next-chroma/2
        val channels=when(hue.toInt()) {
            0 -> doubleArrayOf(chroma,x,0.0)
            1 -> doubleArrayOf(x,chroma,0.0)
            2 -> doubleArrayOf(0.0,chroma,x)
            3 -> doubleArrayOf(0.0,x,chroma)
            4 -> doubleArrayOf(x,0.0,chroma)
            else -> doubleArrayOf(chroma,0.0,x)
        }
        return channels.map { (it+m).coerceIn(0.0,1.0) }.toDoubleArray()
    }
}
