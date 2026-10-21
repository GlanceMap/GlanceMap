package com.glancemap.glancemapwearos.presentation.features.routetools

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteToolLoopSuggestionsTest {
    @Test
    fun visibleLoopDefaultsResetHiddenOutAndBackState() {
        val options =
            RouteToolOptions(
                toolKind = RouteToolKind.CREATE,
                createMode = RouteCreateMode.LOOP_AROUND_HERE,
                loopShapeMode = LoopShapeMode.ALLOW_OUT_AND_BACK,
            )

        val sanitized = options.withVisibleLoopDefaults()

        assertEquals(LoopShapeMode.PREFER_CIRCUIT, sanitized.loopShapeMode)
    }
}
