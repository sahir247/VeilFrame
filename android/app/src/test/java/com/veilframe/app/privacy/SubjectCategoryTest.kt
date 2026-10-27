package com.veilframe.app.privacy

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubjectCategoryTest {

    @Test
    fun testCocoClassMappingCompleteness() {
        assertEquals(80, CocoClassMetadata.LABELS.size)

        // Class 0: person
        assertEquals("person", CocoClassMetadata.LABELS[0])
        assertEquals(SubjectCategory.PERSON, CocoClassMetadata.getCategory(0))

        // Vehicles: 1..8
        assertEquals(SubjectCategory.VEHICLE, CocoClassMetadata.getCategory(1)) // bicycle
        assertEquals(SubjectCategory.VEHICLE, CocoClassMetadata.getCategory(2)) // car
        assertEquals(SubjectCategory.VEHICLE, CocoClassMetadata.getCategory(7)) // truck

        // Animals: 14..23
        assertEquals(SubjectCategory.ANIMAL, CocoClassMetadata.getCategory(14)) // bird
        assertEquals(SubjectCategory.ANIMAL, CocoClassMetadata.getCategory(15)) // cat
        assertEquals(SubjectCategory.ANIMAL, CocoClassMetadata.getCategory(16)) // dog
        assertEquals(SubjectCategory.ANIMAL, CocoClassMetadata.getCategory(17)) // horse
        assertEquals(SubjectCategory.ANIMAL, CocoClassMetadata.getCategory(21)) // bear

        // Furniture: 56..61
        assertEquals(SubjectCategory.FURNITURE, CocoClassMetadata.getCategory(56)) // chair
        assertEquals(SubjectCategory.FURNITURE, CocoClassMetadata.getCategory(57)) // couch
        assertEquals(SubjectCategory.FURNITURE, CocoClassMetadata.getCategory(60)) // dining table

        // Objects / Items:
        assertEquals(SubjectCategory.OBJECT, CocoClassMetadata.getCategory(24)) // backpack
        assertEquals(SubjectCategory.OBJECT, CocoClassMetadata.getCategory(39)) // bottle
        assertEquals(SubjectCategory.OBJECT, CocoClassMetadata.getCategory(63)) // laptop
        assertEquals(SubjectCategory.OBJECT, CocoClassMetadata.getCategory(67)) // cell phone
    }

    @Test
    fun testSalientSubjectCreation() {
        val subject = SalientSubject(
            rect = RectF(10f, 20f, 100f, 200f),
            category = SubjectCategory.FURNITURE,
            label = "chair",
            score = 0.88f
        )
        assertEquals("chair", subject.label)
        assertEquals(SubjectCategory.FURNITURE, subject.category)
        assertEquals(0.88f, subject.score, 0.001f)
        assertTrue(subject.rect.width() > 0f)
    }
}
