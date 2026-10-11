package com.glancemap.glancemapwearos.data.repository

import com.glancemap.glancemapwearos.core.maps.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PoiMetadataCodecsTest {
    private fun <T> roundTrip(
        codec: PoiMetadataCodec<T>,
        value: T,
    ): T {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { codec.write(it, value) }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use(codec::read)
    }

    @Test
    fun `category hierarchy synthetic aliases and exact order survive encoding`() {
        val metadata =
            PoiCategoryMetadata(
                listOf(
                    PoiCategory(-12, "Group", null, 0, true),
                    PoiCategory(9, "Café 水 🚰", -12, 1, false),
                    PoiCategory(3, "Third", -12, 1, false),
                ),
                mapOf(-12 to setOf(9, 3, 27), 9 to setOf(9, 27)),
            )
        assertEquals(metadata, roundTrip(PoiCategoryMetadataCodec, metadata))
        val empty = PoiCategoryMetadata(emptyList(), emptyMap())
        assertEquals(empty, roundTrip(PoiCategoryMetadataCodec, empty))
    }

    @Test
    fun `bounds and unique point counts retain exact values including absent coverage`() {
        listOf(null, GeoBounds(-12.5, 47.2, -80.1, 17.3)).forEach {
            assertEquals(it, roundTrip(PoiCoverageMetadataCodec, it))
        }
        listOf(0, 17, Int.MAX_VALUE).forEach {
            assertEquals(it, roundTrip(PoiPointCountMetadataCodec, it))
        }
    }

    @Test
    fun `invalid bounds category sizes and duplicate identifiers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(PoiCoverageMetadataCodec, GeoBounds(Double.NaN, 1.0, 2.0, 3.0))
        }
        listOf(-1, Int.MAX_VALUE).forEach { count ->
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeInt(count) }
            assertThrows(IllegalArgumentException::class.java) {
                PoiCategoryMetadataCodec.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
        val category = PoiCategory(1, "Duplicate", null, 0, false)
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(PoiCategoryMetadataCodec, PoiCategoryMetadata(listOf(category, category), emptyMap()))
        }
    }
}
