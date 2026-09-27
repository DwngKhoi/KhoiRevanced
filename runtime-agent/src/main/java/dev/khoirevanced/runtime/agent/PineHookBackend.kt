package dev.khoirevanced.runtime.agent

import dev.khoirevanced.runtime.api.HookBackend
import dev.khoirevanced.runtime.api.HookCapability
import dev.khoirevanced.runtime.api.HookHandle
import dev.khoirevanced.runtime.api.HookParam
import dev.khoirevanced.runtime.api.MethodHookCallback
import java.lang.reflect.Member
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook

/**
 * [HookBackend] implemented on Pine, the ART hook engine this runtime ships.
 *
 * ## Why not LSPlant
 *
 * LSPlant resolves the ART symbols it needs by name, through the
 * `art_symbol_resolver` this backend used to provide. On the target device
 * (OnePlus Ace 6T, OxygenOS 16.0.10.500, Android 16) a release `libart.so` is
 * stripped, and `art::GetMethodShorty` is not among the 1922 symbols its
 * `.dynsym` defines. The string "Shorty" does not occur anywhere in the file, so
 * no resolver reading that image can find it by name. LSPlant treats that symbol
 * as mandatory -- in upstream v6.4 and in the fork Vector ships alike -- and
 * `lsplant::Init` returns false before it resolves anything else.
 *
 * Pine needs no such symbol. It locates the `ArtMethod` field offsets by probing
 * memory at runtime rather than by name lookup, and its only `dlsym` probe,
 * `_ZN3art9ArtMethod8CopyFromEPS0_NS_11PointerSizeE`, is present in that
 * `.dynsym`. Pine also disables the hidden-API policy itself, which is the other
 * precondition LSPlant's JNIEnv could not satisfy here.
 *
 * ## Semantics
 *
 * Pine's [MethodHook] already offers `beforeCall`/`afterCall`, which is the same
 * before/after shape [MethodHookCallback] exposes, so the bridge is a direct
 * adaptation rather than a re-implementation. An early return is signalled by
 * writing the result or throwable onto the call frame, which is what stops Pine
 * from invoking the original method.
 */
internal object PineHookBackend : HookBackend {

    override val capabilities: Set<HookCapability> = setOf(
        HookCapability.METHOD,
        HookCapability.CONSTRUCTOR,
        HookCapability.INVOKE_ORIGINAL,
    )

    override fun hook(member: Member, callback: MethodHookCallback): HookHandle {
        val unhook = Pine.hook(member, object : MethodHook() {
            override fun beforeCall(frame: Pine.CallFrame) {
                val param = frame.adapt()
                callback.before(param)
                frame.flush(param)
            }

            override fun afterCall(frame: Pine.CallFrame) {
                val param = frame.adapt()
                callback.after(param)
                frame.flush(param)
            }
        })
        return HookHandle { unhook.unhook() }
    }

    override fun invokeOriginal(member: Member, receiver: Any?, args: Array<Any?>): Any? =
        Pine.invokeOriginalMethod(member, receiver, *args)

    /**
     * Pine exposes no deoptimisation entry point, so this stays false and
     * [HookCapability.DEOPTIMIZE] is not advertised. The one NexAlloy patch that
     * asks for it, `CheckRecycleBitmapMediaSession`, treats a false result as
     * "not available" and leaves the method compiled.
     */
    override fun deoptimize(member: Member): Boolean = false

    /**
     * View a Pine call frame as a [HookParam] so an existing before/after
     * callback can drive it. The frame's `thisObject` and `args` are carried over
     * both ways: `args` is the same array instance, so element writes already
     * propagate, while a replaced receiver has to be copied back explicitly.
     */
    private fun Pine.CallFrame.adapt(): HookParam = HookParam(
        member = method,
        receiver = thisObject,
        args = args,
    ).also { param ->
        param.result = getResult()
        param.throwable = getThrowable()
    }

    /**
     * Write a callback's decisions back onto the frame. Setting a result or a
     * throwable is what tells Pine to skip the original method, so the two are
     * applied in the same order the hook contract requires: a throwable wins.
     */
    private fun Pine.CallFrame.flush(param: HookParam) {
        thisObject = param.receiver
        if (!param.returnEarly) return
        val throwable = param.throwable
        if (throwable != null) setThrowable(throwable) else setResult(param.result)
    }
}
