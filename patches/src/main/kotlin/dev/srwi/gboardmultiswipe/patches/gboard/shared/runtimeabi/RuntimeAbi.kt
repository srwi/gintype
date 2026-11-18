package dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi

internal enum class RuntimeCallId(internal val encodedAbi: String) {
    THICC_TOP_BAR_RUNTIME_ON_CANDIDATES_UPDATED("Ldev/srwi/gboardmultiswipe/extension/thicctopbar/GboardThiccTopBarRuntime;->onCandidatesUpdated(Ljava/lang/Object;Ljava/lang/Object;)V"),
    THICC_TOP_BAR_RUNTIME_ON_CANDIDATES_CLEARED("Ldev/srwi/gboardmultiswipe/extension/thicctopbar/GboardThiccTopBarRuntime;->onCandidatesCleared(Ljava/lang/Object;)V"),
    THICC_TOP_BAR_RUNTIME_ON_SOFT_KEYBOARD_VIEW_INFLATED("Ldev/srwi/gboardmultiswipe/extension/thicctopbar/GboardThiccTopBarRuntime;->onSoftKeyboardViewInflated(Ljava/lang/Object;)V"),
    THICC_TOP_BAR_RUNTIME_SCALE_HEADER_TARGET_HEIGHT("Ldev/srwi/gboardmultiswipe/extension/thicctopbar/GboardThiccTopBarRuntime;->scaleHeaderTargetHeight(Ljava/lang/Object;I)I"),
    MULTISWIPE_RUNTIME_ON_KEYBOARD_VIEW_READY("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->onKeyboardViewReady(Ljava/lang/Object;)V"),
    MULTISWIPE_RUNTIME_ON_GESTURE_EVENT_ENTRY("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->onGestureEventEntry(Ljava/lang/Object;Landroid/view/MotionEvent;)V"),
    MULTISWIPE_RUNTIME_SHOULD_START_GESTURE("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->shouldStartGesture(Ljava/lang/Object;Z)Z"),
    MULTISWIPE_RUNTIME_SHOULD_INTERCEPT_GESTURE_DISPATCH("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->shouldInterceptGestureDispatch(Ljava/lang/Object;)Z"),
    MULTISWIPE_RUNTIME_SHOULD_INTERCEPT_GESTURE_RESET("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->shouldInterceptGestureReset(Ljava/lang/Object;)Z"),
    MULTISWIPE_RUNTIME_RESOLVE_LETTER_STARTED("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->resolveLetterStarted(Ljava/lang/Object;Z)Z"),
    MULTISWIPE_RUNTIME_SHOULD_RECEIVE_EVENTS_DURING_OTHER_OWNER("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->shouldReceiveEventsDuringOtherOwner(Ljava/lang/Object;)Z"),
    MULTISWIPE_RUNTIME_SHOULD_SUPPRESS_BASIC_HANDLER("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->shouldSuppressBasicMotionEventHandler(Ljava/lang/Object;Landroid/view/MotionEvent;)Z"),
    MULTISWIPE_RUNTIME_SHOULD_SUPPRESS_ROLLOVER_COMMIT("Ldev/srwi/gboardmultiswipe/extension/multiswipe/GboardMultiSwipeRuntime;->shouldSuppressRolloverCommitOnPointerDown(Landroid/view/MotionEvent;)Z"),
}

internal data class RuntimeAbi(
    val owner: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val isStatic: Boolean,
) {
    val descriptor: String
        get() = parameters.joinToString(prefix = "(", postfix = ")$returnType", separator = "")

    val reference: String
        get() = "$owner->$name$descriptor"

    companion object {
        fun decode(encoded: String): RuntimeAbi {
            val owner = encoded.substringBefore("->")
            val method = encoded.substringAfter("->")
            val name = method.substringBefore('(')
            val parametersDescriptor = method.substringAfter('(').substringBefore(')')
            return RuntimeAbi(
                owner = owner,
                name = name,
                parameters = decodeTypes(parametersDescriptor),
                returnType = method.substringAfter(')'),
                isStatic = true,
            )
        }

        private fun decodeTypes(descriptor: String): List<String> = buildList {
            var index = 0
            while (index < descriptor.length) {
                val start = index
                while (descriptor[index] == '[') index++
                if (descriptor[index] == 'L') {
                    index = descriptor.indexOf(';', index) + 1
                    require(index > 0) { "Malformed object type in runtime ABI: $descriptor" }
                } else {
                    index++
                }
                add(descriptor.substring(start, index))
            }
        }
    }
}

internal object RuntimeAbiCatalog {
    private val abiByCall = RuntimeCallId.entries.associateWith { call ->
        RuntimeAbi.decode(call.encodedAbi)
    }

    fun abi(call: RuntimeCallId): RuntimeAbi = abiByCall.getValue(call)
}
