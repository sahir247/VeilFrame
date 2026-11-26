package com.veilframe.app.pdf.operations

import java.io.IOException
import kotlin.math.*

/** DrawingML 20.1.9.11 guide formulas and Office's documented angle/sqrt errata.
 * Resolve in declaration order, admitting bounded finite values only.
 */
internal class DrawingMlGuides(width: Double,height: Double,definitions: List<Pair<String,String>>) {
    private val values=mutableMapOf<String,Double>()
    init {
        require(width.isFinite() && height.isFinite() && width>0 && height>0 && definitions.size<=1024)
        val short=min(width,height)
        val long=max(width,height)
        values.putAll(mapOf("l" to 0.0,"t" to 0.0,"r" to width,"b" to height,
            "w" to width,"h" to height,"hc" to width/2,"vc" to height/2,"ss" to short,"ls" to long,
            "cd2" to 10800000.0,"cd4" to 5400000.0,"cd8" to 2700000.0,
            "3cd4" to 16200000.0,"3cd8" to 8100000.0,"5cd8" to 13500000.0,"7cd8" to 18900000.0))
        for(divisor in listOf(2,3,4,5,6,8,10,12,16,20,32)) {
            values["wd"+divisor]=width/divisor
            values["hd"+divisor]=height/divisor
            values["ssd"+divisor]=short/divisor
        }
        val declared=mutableSetOf<String>()
        for((name,formula) in definitions) {
            if(!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) || name in values || !declared.add(name) ||
                formula.length !in 1..256) throw IOException("Invalid or duplicate DrawingML guide: "+name)
            val words=formula.trim().split(Regex("\\s+"))
            val op=words.first()
            val count=when(op) {
                "val","abs","sqrt" -> 1
                "at2","cos","sin","tan","min","max" -> 2
                "*/","+-","+/","?:","cat2","sat2","mod","pin" -> 3
                else -> throw IOException("Unknown DrawingML guide formula")
            }
            if(words.size!=count+1) throw IOException("Wrong DrawingML guide argument count")
            val a=words.drop(1).map(::value)
            fun rad(angle: Double)=Math.toRadians(angle/60000.0)
            fun divide(x: Double,y: Double): Double {
                if(y==0.0) throw IOException("Division by zero in DrawingML guide")
                return x/y
            }
            val result=when(op) {
                "val" -> a[0]
                "abs" -> abs(a[0])
                "sqrt" -> sqrt(abs(a[0]))
                "at2" -> Math.toDegrees(atan2(a[1],a[0]))*60000
                "cos" -> a[0]*cos(rad(a[1]))
                "sin" -> a[0]*sin(rad(a[1]))
                "tan" -> a[0]*tan(rad(a[1]))
                "min" -> min(a[0],a[1])
                "max" -> max(a[0],a[1])
                "*/" -> divide(a[0]*a[1],a[2])
                "+-" -> a[0]+a[1]-a[2]
                "+/" -> divide(a[0]+a[1],a[2])
                "?:" -> if(a[0]>0) a[1] else a[2]
                "cat2" -> a[0]*cos(atan2(a[2],a[1]))
                "sat2" -> a[0]*sin(atan2(a[2],a[1]))
                "mod" -> hypot(hypot(a[0],a[1]),a[2])
                "pin" -> if(a[1]<a[0]) a[0] else if(a[1]>a[2]) a[2] else a[1]
                else -> error("unreachable")
            }
            values[name]=checked(result)
        }
    }
    private fun checked(value: Double): Double {
        if(!value.isFinite() || abs(value)>1e12) throw IOException("DrawingML guide exceeds numeric budget")
        return value
    }
    fun value(token: String): Double = checked(token.toLongOrNull()?.toDouble() ?: values[token]
        ?: throw IOException("Unknown or forward DrawingML guide: "+token))
}
