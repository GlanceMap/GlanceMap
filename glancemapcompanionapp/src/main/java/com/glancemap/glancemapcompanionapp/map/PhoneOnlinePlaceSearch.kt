package com.glancemap.glancemapcompanionapp.map

import java.io.IOException

internal class PhoneOnlinePlaceSearch(
    private val photon: suspend (PhoneMapSearchRequest) -> List<PhoneMapSearchResult> = PhonePhotonSearch()::search,
    private val frenchAddress: suspend (PhoneMapSearchRequest) -> List<PhoneMapSearchResult> =
        PhoneFrenchAddressSearch()::search,
) {
    suspend fun search(request: PhoneMapSearchRequest): List<PhoneMapSearchResult> {
        if (!request.online) return emptyList()
        val addresses =
            if (frenchPostalAddressQuery(request.query) != null) {
                try {
                    frenchAddress(request)
                } catch (_: IOException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        return addresses.ifEmpty { photon(request) }
    }
}
