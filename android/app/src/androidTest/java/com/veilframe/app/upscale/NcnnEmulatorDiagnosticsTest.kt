package com.veilframe.app.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.upscale.inference.BitmapSource
import com.veilframe.app.upscale.inference.NcnnNative
import com.veilframe.app.upscale.inference.NcnnUpscaleEngine
import com.veilframe.app.upscale.inference.UpscaleInferenceParams
import com.veilframe.app.upscale.inference.UpscaleOutput
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NcnnEmulatorDiagnosticsTest {

    companion object {
        private const val TAG = "VeilFrame.Diag"
    }

    private lateinit var context: Context
    private lateinit var repository: UpscaleModelRepository
    private lateinit var engine: NcnnUpscaleEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = UpscaleModelRepository(context)
        engine = NcnnUpscaleEngine(context, repository)

        val modelsToStage = listOf(
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2,
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4,
            UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        )
        for (m in modelsToStage) {
            val stagedParam = File("/data/local/tmp/veilframe_validation/models/${m.id}/model.param")
            val stagedBin = File("/data/local/tmp/veilframe_validation/models/${m.id}/model.bin")
            if (stagedParam.exists() && stagedBin.exists()) {
                val destParam = repository.getNcnnParamFile(m.id)
                val destBin = repository.getNcnnBinFile(m.id)
                destParam.parentFile?.mkdirs()
                stagedParam.copyTo(destParam, overwrite = true)
                stagedBin.copyTo(destBin, overwrite = true)

                val marker = repository.getNcnnInstallMarkerFile(m.id)
                marker.writeText("${m.paramSha256}\n${m.binSha256}\n")
                Log.i(TAG, "Staged ${m.id} (${destBin.length()} bytes)")
            }
        }
    }

    @Test
    fun testA_AnimeVideoV3_2x() {
        runBlocking {
            Log.i(TAG, "=== DIAGNOSTIC A: AnimeVideoV3 2x ===")
            val inputBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val output = engine.upscale(
                source = BitmapSource(inputBitmap),
                model = UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2,
                targetScale = 2,
                params = UpscaleInferenceParams(chunkSize = 128, overlap = 16)
            ) as UpscaleOutput.Streamed
            Log.i(TAG, "Diag A success: ${output.width}x${output.height}, file=${output.file.length()} bytes")
            assertEquals(256, output.width)
            assertEquals(256, output.height)
        }
    }

    @Test
    fun testB_AnimeVideoV3_4x() {
        runBlocking {
            Log.i(TAG, "=== DIAGNOSTIC B: AnimeVideoV3 4x ===")
            val inputBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val output = engine.upscale(
                source = BitmapSource(inputBitmap),
                model = UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4,
                targetScale = 4,
                params = UpscaleInferenceParams(chunkSize = 128, overlap = 16)
            ) as UpscaleOutput.Streamed
            Log.i(TAG, "Diag B success: ${output.width}x${output.height}, file=${output.file.length()} bytes")
            assertEquals(512, output.width)
            assertEquals(512, output.height)
        }
    }

    @Test
    fun testC_RealEsrganX4Plus() {
        runBlocking {
            Log.i(TAG, "=== DIAGNOSTIC C: RealEsrganX4Plus 4x ===")
            val inputBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val output = engine.upscale(
                source = BitmapSource(inputBitmap),
                model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS,
                targetScale = 4,
                params = UpscaleInferenceParams(chunkSize = 128, overlap = 16)
            ) as UpscaleOutput.Streamed
            Log.i(TAG, "Diag C success: ${output.width}x${output.height}, file=${output.file.length()} bytes")
            assertEquals(512, output.width)
            assertEquals(512, output.height)
        }
    }
}
