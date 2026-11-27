package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.cos
import kotlin.math.sin

internal data class SignatureAffine(val a: Float, val b: Float, val c: Float,
    val d: Float, val e: Float, val f: Float) {
    fun matrix() = Matrix(a,b,c,d,e,f)
}

/** Coordinates in the displayed crop box, top down, with PDF page rotation applied. */
internal data class SignaturePageGeometry(val left: Float, val bottom: Float,
    val width: Float, val height: Float, val rotation: Int) {
    init {
        require(listOf(left,bottom,width,height).all { it.isFinite() } &&
            width > 0f && height > 0f && rotation in listOf(0,90,180,270)) {
            "Unsupported signature page geometry"
        }
    }
    val displayWidth: Float get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Float get() = if (rotation == 90 || rotation == 270) width else height

    fun toPdf(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        0 -> left+x to bottom+height-y
        90 -> left+y to bottom+x
        180 -> left+width-x to bottom+y
        else -> left+width-y to bottom+height-x
    }

    fun transform(x: Float, y: Float, w: Float, h: Float, degrees: Float,
        previewWidth: Float = displayWidth, previewHeight: Float = displayHeight): SignatureAffine {
        require(listOf(x,y,w,h,degrees,previewWidth,previewHeight).all { it.isFinite() } &&
            w > 0f && h > 0f && previewWidth > 0f && previewHeight > 0f) { "Invalid signature placement" }
        // Android PdfRenderer reports integer points; tolerate its rounding only.
        require(kotlin.math.abs(previewWidth-displayWidth) <= 1.5f &&
            kotlin.math.abs(previewHeight-displayHeight) <= 1.5f) { "Preview page dimensions changed" }
        val rad = Math.toRadians((degrees % 360f).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        fun corner(u: Float, v: Float): Pair<Float,Float> {
            val dx = (u-0.5f)*w
            val dy = (0.5f-v)*h
            val sx = (x+w/2f+dx*c-dy*s)*displayWidth/previewWidth
            val sy = (y+h/2f+dx*s+dy*c)*displayHeight/previewHeight
            return toPdf(sx,sy)
        }
        val origin = corner(0f,0f)
        val right = corner(1f,0f)
        val top = corner(0f,1f)
        return SignatureAffine(right.first-origin.first,right.second-origin.second,
            top.first-origin.first,top.second-origin.second,origin.first,origin.second)
    }

    fun relativeRect(xFraction: Float, yFractionFromBottom: Float, scale: Float,
        bitmapAspect: Float): FloatArray {
        require(listOf(xFraction,yFractionFromBottom,scale,bitmapAspect).all { it.isFinite() } &&
            xFraction in 0f..1f && yFractionFromBottom in 0f..1f &&
            scale in 0.5f..2.5f && bitmapAspect > 0f) { "Invalid relative signature placement" }
        val maxW = minOf(180f*scale,displayWidth)
        val maxH = minOf(60f*scale,displayHeight)
        val w = minOf(maxW,maxH*bitmapAspect)
        val h = w/bitmapAspect
        return floatArrayOf((displayWidth-w)*xFraction,
            (displayHeight-h)*(1f-yFractionFromBottom),w,h)
    }

    companion object {
        fun from(page: PDPage): SignaturePageGeometry {
            val box = page.cropBox
            return SignaturePageGeometry(box.lowerLeftX,box.lowerLeftY,box.width,box.height,page.rotation)
        }

        /** Legacy absolute bottom-left placement; clockwise visual angle. */
        fun absolute(x: Float,y: Float,w: Float,h: Float,degrees: Float): SignatureAffine {
            require(listOf(x,y,w,h,degrees).all { it.isFinite() } && w > 0f && h > 0f) {
                "Invalid signature placement"
            }
            val rad = Math.toRadians((degrees % 360f).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val a = w*c; val b = -w*s; val d = h*c; val cc = h*s
            return SignatureAffine(a,b,cc,d,x+w/2f-(a+cc)/2f,y+h/2f-(b+d)/2f)
        }
    }
}
