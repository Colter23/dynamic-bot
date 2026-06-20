package top.colter.dynamic.repository

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.SubscriberState
import top.colter.dynamic.core.data.TargetAddress

public object SubscriberStateCache {
    private val nonActiveStates: ConcurrentHashMap<String, SubscriberState> = ConcurrentHashMap()

    public fun reload() {
        nonActiveStates.clear()
        nonActiveStates.putAll(SubscriberRepository.findNonActiveStates())
    }

    public fun stateOf(address: TargetAddress): SubscriberState {
        return nonActiveStates[address.stableValue()] ?: SubscriberState.ACTIVE
    }

    public fun update(address: TargetAddress, state: SubscriberState) {
        val key = address.stableValue()
        if (state == SubscriberState.ACTIVE) {
            nonActiveStates.remove(key)
        } else {
            nonActiveStates[key] = state
        }
    }

    public fun remove(address: TargetAddress) {
        nonActiveStates.remove(address.stableValue())
    }
}
