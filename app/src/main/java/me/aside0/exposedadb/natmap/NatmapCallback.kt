package me.aside0.exposedadb.natmap

interface NatmapCallback {
    fun onResult(success: Boolean, output: String)
    fun onLogUpdate(log: String)
}