package com.glancemap.glancemapwearos.data.repository

import com.glancemap.glancemapwearos.core.maps.GeoBounds
import com.glancemap.glancemapwearos.core.maps.geoBoundsOrNull
import java.io.DataInputStream
import java.io.DataOutputStream

private const val MAX_COLLECTION_ITEMS = 16_384

internal object PoiCategoryMetadataCodec : PoiMetadataCodec<PoiCategoryMetadata> {
    override fun read(input: DataInputStream): PoiCategoryMetadata {
        val categories =
            List(input.readCollectionSize()) {
                PoiCategory(
                    id = input.readInt(),
                    name = input.readUTF(),
                    parentId = if (input.readBoolean()) input.readInt() else null,
                    depth = input.readInt().also { require(it >= 0) },
                    hasChildren = input.readBoolean(),
                )
            }
        val aliases =
            buildMap {
                repeat(input.readCollectionSize()) {
                    val id = input.readInt()
                    require(id !in this)
                    val values = List(input.readCollectionSize()) { input.readInt() }
                    require(values.distinct().size == values.size)
                    put(id, values.toSet())
                }
            }
        require(categories.map { it.id }.distinct().size == categories.size)
        return PoiCategoryMetadata(categories, aliases)
    }

    override fun write(
        output: DataOutputStream,
        value: PoiCategoryMetadata,
    ) {
        output.writeInt(value.categories.size)
        value.categories.forEach { category ->
            output.writeInt(category.id)
            output.writeUTF(category.name)
            output.writeBoolean(category.parentId != null)
            category.parentId?.let(output::writeInt)
            output.writeInt(category.depth)
            output.writeBoolean(category.hasChildren)
        }
        output.writeInt(value.aliases.size)
        value.aliases.forEach { (id, aliases) ->
            output.writeInt(id)
            output.writeInt(aliases.size)
            aliases.forEach(output::writeInt)
        }
    }
}

internal object PoiCoverageMetadataCodec : PoiMetadataCodec<GeoBounds?> {
    override fun read(input: DataInputStream): GeoBounds? {
        if (!input.readBoolean()) return null
        val bounds = GeoBounds(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble())
        require(geoBoundsOrNull(bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon) == bounds)
        return bounds
    }

    override fun write(
        output: DataOutputStream,
        value: GeoBounds?,
    ) {
        output.writeBoolean(value != null)
        value?.let { bounds ->
            output.writeDouble(bounds.minLat)
            output.writeDouble(bounds.maxLat)
            output.writeDouble(bounds.minLon)
            output.writeDouble(bounds.maxLon)
        }
    }
}

internal object PoiPointCountMetadataCodec : PoiMetadataCodec<Int> {
    override fun read(input: DataInputStream): Int = input.readInt()

    override fun write(
        output: DataOutputStream,
        value: Int,
    ) = output.writeInt(value)
}

private fun DataInputStream.readCollectionSize(): Int {
    val size = readInt()
    require(size in 0..MAX_COLLECTION_ITEMS && size <= available() / Int.SIZE_BYTES)
    return size
}
