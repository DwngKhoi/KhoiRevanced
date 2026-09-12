package io.github.nexalloy.morphe.shared.misc.litho.filter

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.morphe.shared.misc.debugging.experimentalBooleanFeatureFlagFingerprint

/**
 * Hooks a boolean feature flag without stacking Pine bridges on both the
 * public accessor and the lower-level experimental flag reader.
 *
 * Upstream Xposed can safely hook both layers. ART 16's reflection path used
 * by Pine cannot reliably invoke a hooked method from another hooked method;
 * the nested backup call either deadlocks player layout or crashes in
 * Method.invoke. In direct runtime all flag callbacks therefore share the
 * lower-level reader. This keeps every feature override active while creating
 * only one native bridge in the call chain.
 */
internal fun PatchExecutor.forceBooleanFeatureFlag(featureId: Long, value: Boolean) {
    if (!isDirectRuntime()) {
        ::featureFlagCheck.hookMethod {
            before { param ->
                if (param.args[0] == featureId) param.result = value
            }
        }
        return
    }

    XposedBridge.hookMethod(
        ::experimentalBooleanFeatureFlagFingerprint.method,
        object : XC_MethodHook(PRIORITY_HIGHEST) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[1] == featureId) param.result = value
            }
        }
    )
}

internal fun PatchExecutor.transformBooleanFeatureFlag(
    featureId: Long,
    transform: (Boolean) -> Boolean,
) {
    if (!isDirectRuntime()) {
        ::featureFlagCheck.hookMethod {
            after { param ->
                if (param.args[0] == featureId) {
                    param.result = transform(param.result as Boolean)
                }
            }
        }
        return
    }

    XposedBridge.hookMethod(
        ::experimentalBooleanFeatureFlagFingerprint.method,
        object : XC_MethodHook(PRIORITY_HIGHEST) {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args[1] == featureId) {
                    param.result = transform(param.result as Boolean)
                }
            }
        }
    )
}

internal fun isDirectRuntime() =
    System.getProperty("khoirevanced.direct-runtime") == "true"
