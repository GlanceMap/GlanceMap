package com.glancemap.glancemapcompanionapp.map

import java.io.File

/** Filesystem truth used by both bundle planning and imported routing-folder data. */
internal fun availablePhoneRoutingFiles(
    expected: List<String>,
    routingDirectory: File,
): List<String> =
    expected.filter { fileName ->
        isUsablePhoneRoutingFile(File(routingDirectory, File(fileName).name))
    }
