package io.github.nexalloy.morphe.shared.misc.litho.filter

import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.morphe.youtube.insertLiteralOverride

internal fun PatchExecutor.forceBooleanFeatureFlag(featureId: Long, value: Boolean) {
    insertLiteralOverride(featureId, value)
}

internal fun PatchExecutor.transformBooleanFeatureFlag(
    featureId: Long,
    transform: (Boolean) -> Boolean,
) {
    insertLiteralOverride(featureId, transform)
}

internal fun isDirectRuntime() =
    System.getProperty("khoirevanced.direct-runtime") == "true"
