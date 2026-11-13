package com.devdayton.onecodeforai.core

data class TreeOverrides(
    val includedPaths: List<String> = emptyList(),
    val stubPaths: List<String> = emptyList(),
    val excludedPaths: List<String> = emptyList()
) {

    fun isEmpty(): Boolean =
        includedPaths.isEmpty() && stubPaths.isEmpty() && excludedPaths.isEmpty()

    fun stateFor(relativePath: String): NodeState? = when {
        relativePath in includedPaths -> NodeState.INCLUDE
        relativePath in stubPaths -> NodeState.STUB
        relativePath in excludedPaths -> NodeState.EXCLUDE
        else -> null
    }
}