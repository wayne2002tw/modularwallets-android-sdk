package com.circle.modularwallets.core.utils

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class NonceManagerTest {

    private fun fixedSource(nonce: BigInteger): NonceManagerSource =
        object : NonceManagerSource {
            override fun get(parameters: FunctionParameters): BigInteger = nonce
            override fun set(parameters: FunctionParameters, nonce: BigInteger) {}
        }

    // --- nonceState should be empty before any consume ---

    @Test
    fun nonceState_initiallyEmpty() {
        val manager = NonceManager(fixedSource(BigInteger.valueOf(100)))
        assertEquals(emptyMap<String, BigInteger>(), manager.nonceState.value)
    }

    // --- nonceState should reflect the nonce written by consume ---
    //
    // With a fixed source returning 100:
    //   increment() sets delta = 1
    //   internalGet() fetches 100, previousNonce is 0 → returns 100, then resets delta
    //   get() returns delta(1) + 100 = 101
    //   nonceMap["0x1234.1"] = 101

    @Test
    fun nonceState_emitsUpdatedNonceAfterConsume() {
        val manager = NonceManager(fixedSource(BigInteger.valueOf(100)))
        val params = FunctionParameters("0x1234", 1L)

        manager.consume(params)

        assertEquals(
            mapOf("0x1234.1" to BigInteger.valueOf(101)),
            manager.nonceState.value
        )
    }

    // --- nonceState should accumulate entries across different keys ---

    @Test
    fun nonceState_accumulatesAcrossDifferentKeys() {
        val manager = NonceManager(fixedSource(BigInteger.valueOf(100)))
        val params1 = FunctionParameters("0x1234", 1L)
        val params2 = FunctionParameters("0x5678", 2L)

        manager.consume(params1)
        manager.consume(params2)

        assertEquals(
            mapOf(
                "0x1234.1" to BigInteger.valueOf(101),
                "0x5678.2" to BigInteger.valueOf(101)
            ),
            manager.nonceState.value
        )
    }
}
