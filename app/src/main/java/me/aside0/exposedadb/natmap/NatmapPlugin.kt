package me.aside0.exposedadb.natmap

object NatmapPlugin {
    external fun nativeExecuteNatmap(args: Array<String>, callback: NatmapCallback)
    external fun nativeStopExecution()
}

data class NatmapResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val logs: String = ""
)