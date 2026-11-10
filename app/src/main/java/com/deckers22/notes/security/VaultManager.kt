package com.deckers22.notes.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class VaultLockedException : IllegalStateException("The vault is locked")

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class WrongPin(val attemptsLeft: Int) : UnlockResult
    data class LockedOut(val secondsLeft: Long) : UnlockResult
}

/**
 * Guards the hidden notes.
 *
 * Hidden notes are encrypted with a random data key. That key is stored twice-wrapped: first
 * with a key derived from the PIN (PBKDF2), then with a hardware-backed Android Keystore key.
 * The Keystore layer means a copy of the app's files is useless on another device, so a short
 * PIN cannot be brute-forced offline. Optionally a second copy of the data key is wrapped with
 * a Keystore key that only works after a fingerprint/face check.
 *
 * The data key lives in memory only while the vault is unlocked.
 */
class VaultManager(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    @Volatile
    private var dataKey: SecretKey? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    private val _hasPin = MutableStateFlow(prefs.contains(KEY_WRAPPED))
    val hasPin: StateFlow<Boolean> = _hasPin

    private val _biometricEnabled = MutableStateFlow(prefs.contains(KEY_BIO_WRAPPED))
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled

    val isUnlocked: Boolean get() = dataKey != null

    // ---- PIN ----

    /** Creates the vault with a first PIN and leaves it unlocked. */
    suspend fun setupPin(pin: String) = withContext(Dispatchers.Default) {
        check(!prefs.contains(KEY_WRAPPED)) { "A PIN is already set" }
        val key = VaultCrypto.newDataKey()
        storePinWrappedKey(key, pin)
        setUnlocked(key)
        _hasPin.value = true
    }

    suspend fun unlock(pin: String): UnlockResult = withContext(Dispatchers.Default) {
        val wait = prefs.getLong(KEY_LOCKOUT_UNTIL, 0) - System.currentTimeMillis()
        if (wait > 0) return@withContext UnlockResult.LockedOut((wait + 999) / 1000)

        val key = unwrapWithPin(pin)
        if (key != null) {
            prefs.edit().remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).apply()
            setUnlocked(key)
            return@withContext UnlockResult.Success
        }

        val failed = prefs.getInt(KEY_FAILED, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED, failed)
        if (failed >= MAX_FREE_ATTEMPTS) {
            // 30 s, then doubling for every further wrong PIN, capped at one hour.
            val doublings = (failed - MAX_FREE_ATTEMPTS).coerceAtMost(7)
            val lockoutMs = (30_000L shl doublings).coerceAtMost(3_600_000L)
            editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + lockoutMs).apply()
            UnlockResult.LockedOut(lockoutMs / 1000)
        } else {
            editor.apply()
            UnlockResult.WrongPin(MAX_FREE_ATTEMPTS - failed)
        }
    }

    /** Replaces the PIN. The vault must be unlocked; notes do not need re-encrypting. */
    suspend fun changePin(newPin: String) = withContext(Dispatchers.Default) {
        val key = dataKey ?: throw VaultLockedException()
        storePinWrappedKey(key, newPin)
    }

    fun lock() {
        dataKey = null
        _unlocked.value = false
    }

    /** Forgets PIN and keys. Existing hidden notes become unreadable; the caller deletes them. */
    fun reset() {
        lock()
        prefs.edit().clear().apply()
        runCatching {
            val keyStore = keyStore()
            keyStore.deleteEntry(DEVICE_KEY_ALIAS)
            keyStore.deleteEntry(BIO_KEY_ALIAS)
        }
        _hasPin.value = false
        _biometricEnabled.value = false
    }

    // ---- Note content ----

    fun encrypt(text: String): String {
        val key = dataKey ?: throw VaultLockedException()
        return VaultCrypto.encryptString(key, text)
    }

    fun decryptOrNull(encoded: String): String? {
        val key = dataKey ?: return null
        return try {
            VaultCrypto.decryptString(key, encoded)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // ---- Fingerprint / face ----

    /**
     * First step of enabling biometric unlock: returns a cipher that must be authorised with a
     * BiometricPrompt and then passed to [finishEnableBiometric]. Throws if no biometrics are
     * enrolled on the device.
     */
    fun newBiometricEnrollCipher(): Cipher {
        if (dataKey == null) throw VaultLockedException()
        val builder = KeyGenParameterSpec.Builder(
            BIO_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        val key = generator.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun finishEnableBiometric(authorisedCipher: Cipher) {
        val key = dataKey ?: throw VaultLockedException()
        val blob = authorisedCipher.iv + authorisedCipher.doFinal(key.encoded)
        prefs.edit().putString(KEY_BIO_WRAPPED, VaultCrypto.encode(blob)).apply()
        _biometricEnabled.value = true
    }

    fun disableBiometric() {
        prefs.edit().remove(KEY_BIO_WRAPPED).apply()
        runCatching { keyStore().deleteEntry(BIO_KEY_ALIAS) }
        _biometricEnabled.value = false
    }

    /**
     * Returns a cipher to authorise with a BiometricPrompt and pass to [unlockWithBiometric],
     * or null when biometric unlock is not usable any more (for example because a new
     * fingerprint was enrolled, which invalidates the key). In that case it is switched off.
     */
    fun biometricUnlockCipher(): Cipher? {
        val blob = prefs.getString(KEY_BIO_WRAPPED, null)?.let(VaultCrypto::decode) ?: return null
        return try {
            val key = keyStore().getKey(BIO_KEY_ALIAS, null) as? SecretKey
                ?: throw GeneralSecurityException("Biometric key is missing")
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(VaultCrypto.TAG_BITS, blob, 0, VaultCrypto.IV_BYTES))
            }
        } catch (e: GeneralSecurityException) {
            // Includes KeyPermanentlyInvalidatedException.
            disableBiometric()
            null
        }
    }

    fun unlockWithBiometric(authorisedCipher: Cipher): Boolean {
        val blob = prefs.getString(KEY_BIO_WRAPPED, null)?.let(VaultCrypto::decode) ?: return false
        return try {
            val bytes = authorisedCipher.doFinal(blob, VaultCrypto.IV_BYTES, blob.size - VaultCrypto.IV_BYTES)
            setUnlocked(SecretKeySpec(bytes, "AES"))
            true
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    // ---- Internals ----

    private fun setUnlocked(key: SecretKey) {
        dataKey = key
        _unlocked.value = true
    }

    private fun storePinWrappedKey(key: SecretKey, pin: String) {
        val salt = VaultCrypto.randomBytes(16)
        val pinWrapped = VaultCrypto.encrypt(VaultCrypto.deriveKey(pin, salt), key.encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, deviceKey()) }
        val deviceWrapped = cipher.iv + cipher.doFinal(pinWrapped)
        prefs.edit()
            .putString(KEY_SALT, VaultCrypto.encode(salt))
            .putInt(KEY_ITERATIONS, VaultCrypto.PBKDF2_ITERATIONS)
            .putString(KEY_WRAPPED, VaultCrypto.encode(deviceWrapped))
            .remove(KEY_FAILED)
            .remove(KEY_LOCKOUT_UNTIL)
            .commit()
    }

    private fun unwrapWithPin(pin: String): SecretKey? {
        val salt = prefs.getString(KEY_SALT, null)?.let(VaultCrypto::decode) ?: return null
        val deviceWrapped = prefs.getString(KEY_WRAPPED, null)?.let(VaultCrypto::decode) ?: return null
        val iterations = prefs.getInt(KEY_ITERATIONS, VaultCrypto.PBKDF2_ITERATIONS)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    deviceKey(),
                    GCMParameterSpec(VaultCrypto.TAG_BITS, deviceWrapped, 0, VaultCrypto.IV_BYTES),
                )
            }
            val pinWrapped = cipher.doFinal(
                deviceWrapped,
                VaultCrypto.IV_BYTES,
                deviceWrapped.size - VaultCrypto.IV_BYTES,
            )
            val bytes = VaultCrypto.decrypt(VaultCrypto.deriveKey(pin, salt, iterations), pinWrapped)
            SecretKeySpec(bytes, "AES")
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun deviceKey(): SecretKey {
        (keyStore().getKey(DEVICE_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            DEVICE_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEVICE_KEY_ALIAS = "notes_vault_device_key"
        const val BIO_KEY_ALIAS = "notes_vault_biometric_key"
        const val KEY_SALT = "salt"
        const val KEY_ITERATIONS = "iterations"
        const val KEY_WRAPPED = "wrapped_key"
        const val KEY_BIO_WRAPPED = "bio_wrapped_key"
        const val KEY_FAILED = "failed_attempts"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"
        const val MAX_FREE_ATTEMPTS = 5
    }
}
