package com.veilframe.app.pdf

import android.content.Context
import android.net.Uri
import com.veilframe.app.pdf.operations.OfficeConverter
import com.veilframe.app.pdf.operations.OfficeSourceFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Owns source and destination staging through conversion and explicit handoff. */
internal object OfficeQuickExport {
    suspend fun generate(context: Context,uri: Uri,name: String,expected: OfficeSourceSnapshot.Identity,
        savedPrint: Boolean,converter: OfficeConverter,publish: suspend (File)->Boolean) {
        val format = OfficeSourceFormat.fromName(name)
        OfficeSourceSnapshot.open(context,uri,format).use { source ->
            expected.requireSame(source.identity)
            val output = File.createTempFile("office_output_",".pdf",context.cacheDir)
            var handedOff = false
            try {
                when(format) {
                    OfficeSourceFormat.XLSX -> converter.convertXlsxToPdf(source.file,output,context,savedPrintLayout=savedPrint)
                    OfficeSourceFormat.PPTX -> converter.convertPptxToPdf(source.file,output,renderMode="BITMAP",context=context)
                    OfficeSourceFormat.TEXT -> converter.convertTextToPdf(source.file,output,context)
                    OfficeSourceFormat.DOCX -> converter.convertDocxToPdf(source.file,output,context)
                }
                currentCoroutineContext().ensureActive()
                check(output.isFile && output.length()>0) { "Office conversion produced no output" }
                handedOff = publish(output)
            } finally { if (!handedOff) output.delete() }
        }
    }
}
