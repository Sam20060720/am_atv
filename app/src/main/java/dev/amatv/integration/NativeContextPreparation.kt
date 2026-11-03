package dev.amatv.integration

/** New app context; unlike addQueueItems, no old native provider belongs to it. */
internal fun prepareNewNativeContext(controller: Any, provider: Any) {
    val methods = controller.javaClass.methods
    val explicit = methods.firstOrNull { method ->
        method.name == "prepare" && method.parameterTypes.size == 3 &&
            method.parameterTypes[0].isInstance(provider) &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType &&
            method.parameterTypes[2] == Boolean::class.javaPrimitiveType
    }
    if (explicit != null) {
        // Public PlaybackQueueInsertionType. Bundled 1.1.1 REPLACE(1) rejects
        // replacing a static provider with current + appended future providers.
        explicit.invoke(controller, provider, 5 /* INSERTION_TYPE_CLEAR_AND_REPLACE */, false)
    } else {
        // Compatibility only for SDKs with no explicit insertion overload.
        // Do not retry a failed explicit prepare through the unsafe overload.
        methods.first { method ->
            method.name == "prepare" && method.parameterTypes.size == 2 &&
                method.parameterTypes[0].isInstance(provider) &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }.invoke(controller, provider, false)
    }
}
