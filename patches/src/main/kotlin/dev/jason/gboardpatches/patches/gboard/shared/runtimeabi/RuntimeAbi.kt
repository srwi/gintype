package dev.jason.gboardpatches.patches.gboard.shared.runtimeabi

internal enum class RuntimeCallId(internal val encodedAbi: String) {
    MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_UPDATED("Ldev/jason/gboardpatches/extension/matrixsuggestions/GboardMatrixSuggestionsRuntime;->onCandidatesUpdated(Ljava/lang/Object;Ljava/lang/Object;)V"),
    MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED("Ldev/jason/gboardpatches/extension/matrixsuggestions/GboardMatrixSuggestionsRuntime;->onCandidatesCleared(Ljava/lang/Object;)V"),
    NINTYPE_RUNTIME_ON_GESTURE_EVENT_ENTRY("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->onGestureEventEntry(Ljava/lang/Object;Landroid/view/MotionEvent;)V"),
    NINTYPE_RUNTIME_SHOULD_START_GESTURE("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->shouldStartGesture(Ljava/lang/Object;Z)Z"),
    NINTYPE_RUNTIME_SHOULD_INTERCEPT_GESTURE_DISPATCH("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->shouldInterceptGestureDispatch(Ljava/lang/Object;)Z"),
    NINTYPE_RUNTIME_SHOULD_INTERCEPT_GESTURE_RESET("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->shouldInterceptGestureReset(Ljava/lang/Object;)Z"),
    NINTYPE_RUNTIME_RESOLVE_LETTER_STARTED("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->resolveLetterStarted(Ljava/lang/Object;Z)Z"),
    NINTYPE_RUNTIME_SHOULD_RECEIVE_EVENTS_DURING_OTHER_OWNER("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->shouldReceiveEventsDuringOtherOwner(Ljava/lang/Object;)Z"),
    NINTYPE_RUNTIME_SHOULD_SUPPRESS_BASIC_HANDLER("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->shouldSuppressBasicMotionEventHandler(Ljava/lang/Object;Landroid/view/MotionEvent;)Z"),
    NINTYPE_RUNTIME_SHOULD_SUPPRESS_ROLLOVER_COMMIT("Ldev/jason/gboardpatches/extension/nintype/GboardNintypeRuntime;->shouldSuppressRolloverCommitOnPointerDown(Landroid/view/MotionEvent;)Z"),
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
