package io.github.madeye.meow.net

/** Tracks physical candidates without mistaking a VPN for its own upstream. */
internal class PhysicalNetworkSelection<T> {
    data class Change<T>(val network: T?)

    private val available = linkedSetOf<T>()
    private var selected: T? = null

    /** Android's best-matching callback replaces the previous best without
     * necessarily reporting it lost. Never retain that superseded candidate. */
    @Synchronized
    fun bestAvailable(network: T): Change<T>? {
        available.clear()
        available.add(network)
        return select(network)
    }

    @Synchronized
    fun capabilitiesChanged(network: T, physicalDefault: T?): Change<T>? {
        if (network !in available) return null
        return select(physicalDefault?.takeIf { it in available } ?: selected)
    }

    @Synchronized
    fun available(network: T, physicalDefault: T?): Change<T>? {
        available.add(network)
        return select(when {
            physicalDefault != null && physicalDefault in available -> physicalDefault
            selected != null -> selected
            else -> network
        })
    }

    @Synchronized
    fun lost(network: T, physicalDefault: T?): Change<T>? {
        available.remove(network)
        if (selected != network) return null
        return select(physicalDefault?.takeIf { it in available } ?: available.firstOrNull())
    }

    private fun select(network: T?): Change<T>? {
        if (network == selected) return null
        selected = network
        return Change(network)
    }
}
