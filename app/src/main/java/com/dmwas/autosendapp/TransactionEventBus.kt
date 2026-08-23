package com.dmwas.autosendapp

import kotlinx.coroutines.flow.MutableSharedFlow

data class TransactionResult(
    val success: Boolean,
    val message: String,
    val amount: Double? = null,
    val contactName: String? = null
)

object TransactionEventBus {
    val events = MutableSharedFlow<TransactionResult>(extraBufferCapacity = 1)
    
    fun emit(result: TransactionResult) {
        events.tryEmit(result)
    }
}
