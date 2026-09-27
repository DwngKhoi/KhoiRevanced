package dev.khoirevanced.runtime.agent

import dalvik.system.DexClassLoader

/**
 * The payload's class loader, with the parent consulted last for the packages
 * where the payload's own copy has to win.
 *
 * ## The clash
 *
 * The payload ships `com.google.protobuf` in 3 of its 19 dex files. YouTube 21.38
 * ships a different, older protobuf-lite in `base.apk!classes3.dex`. A
 * `DexClassLoader` with the host as its parent is parent-first, so the host's copy
 * wins for every class both of them define, and the payload's generated messages
 * -- compiled against the newer runtime -- are executed on the older one:
 *
 *     java.lang.NoSuchMethodError: No static method getEmptyRegistry() in class
 *       Lcom/google/protobuf/ExtensionRegistryLite;
 *       at com.google.protobuf.GeneratedMessageLite.parseFrom(GeneratedMessageLite.java:1735)
 *       at ...innertube.NextResponseOuterClass$NextResponse.parseFrom(...)
 *       at HideVideoActionButtonsPatch.kt:91
 *
 * It is worth being precise about the damage, because it does not look like a
 * classloading problem from the outside. `VideoActionButtonsFilter` takes the
 * parsed message as a `MessageLite` parameter and parses it again into
 * `SingleColumnWatchNextResults`, so the payload's messages and its own generated
 * types have to be the *same* generation as each other. With the parent winning,
 * they are not: the result is a `NoSuchMethodError` on one response, and a feed
 * that renders "An error occurred", because the exception is raised inside a hook
 * callback on the response-parsing path and a callback's exception propagates into
 * the host exactly as it does under LSPosed.
 *
 * ## Why the payload's copy has to win
 *
 * The exchange with the host is bytes, not objects:
 * `it.args[0].toByteArray()` on the way in, and only payload-owned types on the
 * way out. The host keeps using its own protobuf for its own messages throughout.
 * So making the payload self-consistent does not put two generations of protobuf
 * on one object graph -- it puts two self-consistent generations in two class
 * loaders, which is the same situation as any other library that shades a
 * dependency.
 *
 * The prefix list is explicit rather than "everything the payload defines", for
 * two reasons. The payload deliberately relies on the host for large parts of
 * the framework, so a blanket parent-last would break those. And the set of
 * packages that genuinely clash is a property of the host build, so it is
 * measured by [DexClassNames] and reported, rather than assumed here.
 */
internal class PayloadClassLoader(
    dexPath: String,
    optimizedDirectory: String,
    librarySearchPath: String?,
    parent: ClassLoader,
    private val parentLastPrefixes: List<String>,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (parentLastPrefixes.none { name.startsWith(it) }) {
            return super.loadClass(name, resolve)
        }
        // findLoadedClass first, so a class already defined here is reused rather
        // than redefined: a second definition of the same name in the same loader
        // is not possible, but this also keeps the common case off the slow path.
        val own = findLoadedClass(name) ?: runCatching { findClass(name) }.getOrNull()
        if (own != null) {
            if (resolve) resolveClass(own)
            return own
        }
        // The payload does not define it after all -- a package prefix can cover a
        // class the host alone has. Falling back keeps that from being a hard
        // failure, and the reason is recorded because it means the prefix list is
        // wider than the payload's actual contents.
        RuntimeLog.warn(
            "classloader",
            "$name matches a parent-last prefix but the payload does not define it; " +
                "using the host's copy",
        )
        return super.loadClass(name, resolve)
    }
}
