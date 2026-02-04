package com.circle.modularwallets.core.utils.signature

import com.circle.modularwallets.core.errors.BaseError
import com.circle.modularwallets.core.models.EIP712Domain
import com.circle.modularwallets.core.models.EIP712Message
import com.circle.modularwallets.core.models.Entry
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredDataEncoderTest {

    /**
     * Builds a minimal EIP712Message with a single field in the "Test" struct.
     * EIP712Domain is included so that hashDomain() can encode it correctly.
     */
    private fun buildMessage(fieldName: String, fieldType: String, fieldValue: Any): EIP712Message =
        EIP712Message(
            types = mutableMapOf(
                "EIP712Domain" to mutableListOf(Entry("name", "string")),
                "Test" to mutableListOf(Entry(fieldName, fieldType))
            ),
            primaryType = "Test",
            domain = EIP712Domain(name = "Test"),
            message = mutableMapOf(fieldName to fieldValue)
        )

    // --- Happy path: valid input must continue to produce a hash -----------

    @Test
    fun hashStructuredData_validUint256Array_returnsNonEmptyHash() {
        val encoder = StructuredDataEncoder(
            buildMessage("values", "uint256[]", listOf("1", "2", "3"))
        )
        val hash = encoder.hashStructuredData()
        assertTrue("Expected non-empty hash for valid uint256 array", hash.isNotEmpty())
    }

    // --- Bug-trigger: convertToEncodedItem swallows the error here ----------
    //
    // convertToEncodedItem() catches every Exception and returns ByteArray(0).
    // The empty array is written with length 0, so the item is silently dropped
    // from the EIP-712 encoding.  The final hash is computed over incomplete data
    // and no error reaches the caller.
    //
    // Correct behavior: propagate as BaseError so the caller knows the encoding
    // failed at the exact value that could not be parsed.

    @Test
    fun hashStructuredData_invalidValueInUint256Array_throwsBaseError() {
        val encoder = StructuredDataEncoder(
            buildMessage("values", "uint256[]", listOf("1", "not_a_number", "3"))
        )
        assertThrows(BaseError::class.java) {
            encoder.hashStructuredData()
        }
    }
}
