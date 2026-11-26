package com.veilframe.app.pdf.operations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.apache.poi.openxml4j.opc.TargetMode
import org.apache.poi.xslf.usermodel.XSLFSheet
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor

/** Resolve picture bytes in the owning OPC part, never by guessed global relationship ID. */
internal object PptxPictureRenderer {
    private const val MAX_BYTES = 32*1024*1024
    private const val MAX_PIXELS = 16_777_216L
    private const val MAX_EDGE = 8192
    private val drawingNamespaces = setOf("http://schemas.openxmlformats.org/drawingml/2006/main",
        "http://purl.oclc.org/ooxml/drawingml/main")
    private val presentationNamespaces = setOf("http://schemas.openxmlformats.org/presentationml/2006/main",
        "http://purl.oclc.org/ooxml/presentationml/main")
    private val relationNamespaces = listOf("http://schemas.openxmlformats.org/officeDocument/2006/relationships",
        "http://purl.oclc.org/ooxml/officeDocument/relationships")

    data class Crop(val left: Float = 0f,val top: Float = 0f,val right: Float = 0f,val bottom: Float = 0f) {
        init {
            require(listOf(left,top,right,bottom).all { it.isFinite() && it in 0f..1f } &&
                left+right<1f && top+bottom<1f) { "Unsupported or invalid slide picture crop" }
        }
    }
    data class Reference(val relationship: String,val crop: Crop)

    private fun Node.named(local: String,namespaces: Set<String>) =
        localName == local && namespaceURI in namespaces
    private fun children(node: Node): Sequence<Node> = sequence {
        var child = node.firstChild
        while(child != null) { yield(child); child=child.nextSibling }
    }

    fun reference(root: Node): Reference? {
        val pending = ArrayDeque<Node>()
        pending.add(root)
        var visited = 0
        var result: Reference? = null
        while(pending.isNotEmpty()) {
            if (++visited>50_000) throw IOException("Slide picture XML exceeds traversal budget")
            val node = pending.removeLast()
            if (node.named("blipFill",drawingNamespaces+presentationNamespaces)) {
                // DrawingML blipFill may contain a raster fallback plus an SVG extension.
                // The primary blip's embedded relationship is the offline raster source.
                val blip = children(node).filter { it.named("blip",drawingNamespaces) }.singleOrNull()
                    ?: throw IOException("Slide picture fill has no unique primary blip")
                val ids = relationNamespaces.mapNotNull { ns ->
                    blip.attributes?.getNamedItemNS(ns,"embed")?.nodeValue?.takeIf { it.isNotBlank() }
                }.distinct()
                if (ids.size!=1 || ids[0].length>128) throw IOException("Slide picture has no unique embedded relationship")
                if (children(node).any { it.named("tile",drawingNamespaces) })
                    throw IOException("Tiled slide picture fills are unsupported")
                fun cropOf(element: Node?): Crop {
                    fun fraction(name: String): Float {
                        val raw = element?.attributes?.getNamedItem(name)?.nodeValue ?: return 0f
                        return (if(raw.endsWith("%")) raw.dropLast(1).toFloatOrNull()?.div(100f)
                            else raw.toFloatOrNull()?.div(100000f))
                            ?: throw IOException("Invalid slide picture crop")
                    }
                    return Crop(fraction("l"),fraction("t"),fraction("r"),fraction("b"))
                }
                val crops = children(node).filter { it.named("srcRect",drawingNamespaces) }.toList()
                if (crops.size>1) throw IOException("Ambiguous slide picture crop")
                val stretches = children(node).filter { it.named("stretch",drawingNamespaces) }.toList()
                if (stretches.size>1) throw IOException("Ambiguous slide picture stretch")
                val destinationCrop = stretches.singleOrNull()?.let {
                    children(it).filter { e -> e.named("fillRect",drawingNamespaces) }.toList()
                }.orEmpty()
                if (destinationCrop.size>1 || cropOf(destinationCrop.singleOrNull()) != Crop())
                    throw IOException("Nontrivial slide picture destination stretch is unsupported")
                if (result!=null) throw IOException("Multiple slide picture fills in one shape are unsupported")
                result = Reference(ids.single(),cropOf(crops.singleOrNull()))
                continue
            }
            for (child in children(node)) {
                if (pending.size>=50_000) throw IOException("Slide picture XML exceeds traversal budget")
                if (child.nodeType==Node.ELEMENT_NODE || child.nodeType==Node.DOCUMENT_NODE ||
                    child.nodeType==Node.DOCUMENT_FRAGMENT_NODE) pending.add(child)
            }
        }
        return result
    }

    fun read(owner: XSLFSheet,reference: Reference,checkpoint: ()->Unit): ByteArray {
        val ownerPart = owner.packagePart
        val relation = ownerPart.getRelationship(reference.relationship)
            ?: throw IOException("Slide picture relationship is missing")
        if (relation.targetMode != TargetMode.INTERNAL || !relation.relationshipType.endsWith("/image"))
            throw IOException("Slide picture relationship is not an internal image")
        val part = ownerPart.getRelatedPart(relation)
            ?: throw IOException("Slide picture part is missing")
        if (!part.contentType.startsWith("image/")) throw IOException("Slide picture part has invalid content type")
        return part.inputStream.use { input ->
            val buffer = ByteArray(64*1024)
            val output = ByteArrayOutputStream()
            while(true) {
                checkpoint()
                val n = input.read(buffer)
                if (n<0) break
                if (n==0 || n>MAX_BYTES-output.size()) throw IOException("Slide image exceeds byte budget or stalled")
                output.write(buffer,0,n)
            }
            if(output.size()==0) throw IOException("Slide image part is empty")
            output.toByteArray()
        }
    }

    internal fun sampleSize(width: Int,height: Int): Int {
        require(width>0 && height>0) { "Invalid slide image dimensions" }
        var sample=1
        while (true) {
            val w=(width.toLong()+sample-1)/sample
            val h=(height.toLong()+sample-1)/sample
            if (w<=MAX_EDGE && h<=MAX_EDGE && w*h<=MAX_PIXELS) return sample
            sample*=2
        }
    }

    internal fun sourceRect(width: Int,height: Int,crop: Crop): Rect {
        val l=floor(width*crop.left).toInt()
        val t=floor(height*crop.top).toInt()
        val r=ceil(width*(1f-crop.right)).toInt().coerceAtMost(width)
        val b=ceil(height*(1f-crop.bottom)).toInt().coerceAtMost(height)
        require(l in 0 until r && t in 0 until b && r<=width && b<=height) { "Empty slide picture crop" }
        return Rect(l,t,r,b)
    }

    fun draw(canvas: Canvas,data: ByteArray,crop: Crop,destination: RectF) {
        if(data.isEmpty() || data.size>MAX_BYTES || destination.isEmpty ||
            !listOf(destination.left,destination.top,destination.right,destination.bottom).all { it.isFinite() })
            throw IOException("Invalid slide image payload or placement")
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if (bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outMimeType?.startsWith("image/")!=true)
            throw IOException("Slide image codec is unsupported or input is corrupt")
        val options=BitmapFactory.Options().apply {
            inSampleSize=sampleSize(bounds.outWidth,bounds.outHeight)
            inPreferredConfig=Bitmap.Config.ARGB_8888
            inScaled=false
        }
        val bitmap=BitmapFactory.decodeByteArray(data,0,data.size,options)
            ?: throw IOException("Slide image could not be decoded")
        try {
            if (bitmap.width>MAX_EDGE || bitmap.height>MAX_EDGE ||
                bitmap.width.toLong()*bitmap.height>MAX_PIXELS) throw IOException("Slide image decoder exceeded budget")
            canvas.drawBitmap(bitmap,sourceRect(bitmap.width,bitmap.height,crop),destination,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally { bitmap.recycle() }
    }
}
