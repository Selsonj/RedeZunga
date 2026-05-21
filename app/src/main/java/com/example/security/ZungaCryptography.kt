package com.example.security

import android.util.Base64
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ZungaCryptography {

    private const val RSA_ALGORITHM = "RSA"
    private const val AES_PADDING = "AES/CBC/PKCS5Padding"
    private const val RSA_PADDING = "RSA/ECB/PKCS1Padding"

    /**
     * Generates a 2048-bit KeyPair for P2P identity.
     */
    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(RSA_ALGORITHM)
        keyPairGenerator.initialize(2048)
        return keyPairGenerator.genKeyPair()
    }

    /**
     * Converts a PublicKey to standard Base64 string for P2P discovery.
     */
    fun publicKeyToString(publicKey: PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Converts a PrivateKey to Base64 string.
     */
    fun privateKeyToString(privateKey: PrivateKey): String {
        return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Parses a Base64 string back to a PublicKey.
     */
    fun stringToPublicKey(keyStr: String): PublicKey {
        val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
        return keyFactory.generatePublic(spec)
    }

    /**
     * Parses a Base64 string back to a PrivateKey.
     */
    fun stringToPrivateKey(keyStr: String): PrivateKey {
        val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
        return keyFactory.generatePrivate(spec)
    }

    /**
     * Sign an arbitrary data string with a PrivateKey.
     */
    fun sign(data: String, privateKey: PrivateKey): String {
        val privateSignature = Signature.getInstance("SHA256withRSA")
        privateSignature.initSign(privateKey)
        privateSignature.update(data.toByteArray(Charsets.UTF_8))
        val signature = privateSignature.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    /**
     * Verify a signed string against a corresponding PublicKey string.
     */
    fun verify(data: String, signatureStr: String, publicKeyStr: String): Boolean {
        return try {
            val publicKey = stringToPublicKey(publicKeyStr)
            val publicSignature = Signature.getInstance("SHA256withRSA")
            publicSignature.initVerify(publicKey)
            publicSignature.update(data.toByteArray(Charsets.UTF_8))
            val signatureBytes = Base64.decode(signatureStr, Base64.NO_WRAP)
            publicSignature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generates a temporary symmetric AES key.
     */
    fun generateAESKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    /**
     * Encrypt content with standard AES-256-CBC, returning a Base64-encoded string comprising IV + Ciphertext.
     */
    fun encryptAES(plainText: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(AES_PADDING)
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypt AES-encrypted content.
     */
    fun decryptAES(combinedBase64: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(AES_PADDING)
        val combined = Base64.decode(combinedBase64, Base64.NO_WRAP)
        
        val iv = ByteArray(16)
        System.arraycopy(combined, 0, iv, 0, iv.size)
        val encryptedBytes = ByteArray(combined.size - iv.size)
        System.arraycopy(combined, iv.size, encryptedBytes, 0, encryptedBytes.size)
        
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Encrypt an AES key (symmetric) using RSA Pulic Key (asymmetric hybrid encryption).
     */
    fun encryptKeyRSA(secretKey: SecretKey, publicKey: PublicKey): String {
        val cipher = Cipher.getInstance(RSA_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedKey = cipher.doFinal(secretKey.encoded)
        return Base64.encodeToString(encryptedKey, Base64.NO_WRAP)
    }

    /**
     * Decrypt an AES key (asymmetric decryption).
     */
    fun decryptKeyRSA(encryptedKeyBase64: String, privateKey: PrivateKey): SecretKey {
        val cipher = Cipher.getInstance(RSA_PADDING)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val encryptedKeyBytes = Base64.decode(encryptedKeyBase64, Base64.NO_WRAP)
        val decryptedKeyBytes = cipher.doFinal(encryptedKeyBytes)
        return SecretKeySpec(decryptedKeyBytes, 0, decryptedKeyBytes.size, "AES")
    }
}
