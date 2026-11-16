package com.veilframe.app.startup

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression protection verifying that R8 and ProGuard rules preserve the generated
 * Room database implementation constructor (WorkDatabase_Impl.<init>()) used by WorkManager.
 *
 * When Room initializes WorkDatabase reflectively during AndroidX Startup, stripping
 * this no-argument constructor triggers an InstantiationException:
 * "Failed to create an instance of class androidx.work.impl.WorkDatabase".
 */
class WorkManagerStartupProGuardTest {

    private fun findProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "android/app").exists() && File(current, ".github").exists()) {
                return current
            }
            if (File(current, "app").exists() && File(current, "src").exists()) {
                val parent = current.parentFile
                if (parent != null) return parent
            }
            current = current.parentFile
        }
        val fallback = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return if (File(fallback, "android").exists()) fallback else fallback.parentFile ?: fallback
    }

    @Test
    fun `proguard rules explicitly preserve WorkDatabase_Impl constructor`() {
        val root = findProjectRoot()
        val proguardFile = File(root, "android/app/proguard-rules.pro")
        assertTrue("proguard-rules.pro must exist", proguardFile.exists())
        val content = proguardFile.readText()

        assertTrue(
            "Must preserve androidx.work.impl.WorkDatabase_Impl class to prevent reflection failure in Room",
            content.contains("androidx.work.impl.WorkDatabase_Impl")
        )
        assertTrue(
            "Must preserve <init>() of WorkDatabase_Impl to prevent InstantiationException on startup",
            content.contains("<init>();")
        )
        assertTrue(
            "Must preserve RoomDatabase implementations and their constructors",
            content.contains("class * extends androidx.room.RoomDatabase")
        )
    }

    @Test
    fun `WorkDatabase_Impl has accessible zero-argument constructor on classpath`() {
        val clazz = Class.forName("androidx.work.impl.WorkDatabase_Impl")
        assertNotNull("WorkDatabase_Impl class must be resolvable", clazz)
        val constructor = clazz.getDeclaredConstructor()
        assertNotNull("WorkDatabase_Impl must have a zero-argument constructor", constructor)
    }

    @Test
    fun `R8 usage output if present does not strip WorkDatabase_Impl constructor`() {
        val root = findProjectRoot()
        val usageFile = File(root, "android/app/build/outputs/mapping/release/usage.txt")
        if (usageFile.exists()) {
            val lines = usageFile.readLines()
            var inWorkDatabaseImpl = false
            for (line in lines) {
                if (line.startsWith("androidx.work.impl.WorkDatabase_Impl:")) {
                    inWorkDatabaseImpl = true
                } else if (inWorkDatabaseImpl) {
                    if (line.startsWith(" ") || line.startsWith("\t")) {
                        val trimmed = line.trim()
                        assertTrue(
                            "WorkDatabase_Impl constructor must NOT be listed in usage.txt (stripped by R8): $line",
                            !trimmed.contains("<init>()")
                        )
                    } else {
                        inWorkDatabaseImpl = false
                    }
                }
            }
        }
    }
}
