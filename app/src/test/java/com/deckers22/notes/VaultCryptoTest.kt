package com.deckers22.notes

import com.deckers22.notes.security.VaultCrypto
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultCryptoTest {
    private val salt = VaultCrypto.randomBytes(16)

    @Test
    fun stringRoundTrip() {
        val key = VaultCrypto.newDataKey()
        val text = "Geheime notitie ✓ with\nnew lines"
        val sealed = VaultCrypto.encryptString(key, text)
        assertFalse(sealed.contains("Geheime"))
        assertEquals(text, VaultCrypto.decryptString(key, sealed))
    }

    @Test
    fun emptyStringRoundTrip() {
        val key = VaultCrypto.newDataKey()
        assertEquals("", VaultCrypto.decryptString(key, VaultCrypto.encryptString(key, "")))
    }

    @Test
    fun sameTextEncryptsDifferentlyEachTime() {
        val key = VaultCrypto.newDataKey()
        assertNotEquals(VaultCrypto.encryptString(key, "a"), VaultCrypto.encryptString(key, "a"))
    }

    @Test
    fun correctPinUnwrapsDataKey() {
        val dataKey = VaultCrypto.newDataKey()
        val wrapped = VaultCrypto.encrypt(VaultCrypto.deriveKey("1234", salt, 1_000), dataKey.encoded)
        val unwrapped = VaultCrypto.decrypt(VaultCrypto.deriveKey("1234", salt, 1_000), wrapped)
        assertEquals(dataKey.encoded.toList(), unwrapped.toList())
    }

    @Test
    fun wrongPinIsRejected() {
        val wrapped = VaultCrypto.encrypt(VaultCrypto.deriveKey("1234", salt, 1_000), ByteArray(32))
        assertThrows(GeneralSecurityException::class.java) {
            VaultCrypto.decrypt(VaultCrypto.deriveKey("1235", salt, 1_000), wrapped)
        }
    }

    @Test
    fun tamperedDataIsRejected() {
        val key = VaultCrypto.newDataKey()
        val blob = VaultCrypto.encrypt(key, "note".toByteArray())
        blob[blob.lastIndex] = (blob[blob.lastIndex] + 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { VaultCrypto.decrypt(key, blob) }
    }
}
