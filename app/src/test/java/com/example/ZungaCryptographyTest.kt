package com.example

import com.example.security.ZungaCryptography
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZungaCryptographyTest {

    @Test
    fun testKeyPairGenerationAndSerialization() {
        // 1. Generate RSA Key Pair
        val keyPair = ZungaCryptography.generateKeyPair()
        assertNotNull(keyPair)
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)

        // 2. Serialize to base64 string
        val pubStr = ZungaCryptography.publicKeyToString(keyPair.public)
        val privStr = ZungaCryptography.privateKeyToString(keyPair.private)
        assertFalse(pubStr.isEmpty())
        assertFalse(privStr.isEmpty())

        // 3. Deserialize back to Key objects
        val recoveredPub = ZungaCryptography.stringToPublicKey(pubStr)
        val recoveredPriv = ZungaCryptography.stringToPrivateKey(privStr)
        assertNotNull(recoveredPub)
        assertNotNull(recoveredPriv)

        assertEquals(keyPair.public, recoveredPub)
        assertEquals(keyPair.private, recoveredPriv)
    }

    @Test
    fun testRSASignatureAndVerification() {
        val keyPair = ZungaCryptography.generateKeyPair()
        val data = "Mensagem ultra secreta da rede mesh Zunga"

        // Sign data using private key
        val signature = ZungaCryptography.sign(data, keyPair.private)
        assertFalse(signature.isEmpty())

        // Verify using public key
        val pubStr = ZungaCryptography.publicKeyToString(keyPair.public)
        val isVerified = ZungaCryptography.verify(data, signature, pubStr)
        assertTrue(isVerified)

        // Verify with tampered data
        val isTamperedVerified = ZungaCryptography.verify(data + " tampered", signature, pubStr)
        assertFalse(isTamperedVerified)
    }

    @Test
    fun testAESEncryptionAndDecryptionWithIv() {
        val aesKey = ZungaCryptography.generateAESKey()
        assertNotNull(aesKey)

        val data = "Mensagem confidencial enviada por Wi-Fi Direct"

        // Encrypt with explicit IV
        val (cipherText, iv) = ZungaCryptography.encryptAESWithIv(data, aesKey)
        assertFalse(cipherText.isEmpty())
        assertFalse(iv.isEmpty())

        // Decrypt
        val decrypted = ZungaCryptography.decryptAESWithIv(cipherText, iv, aesKey)
        assertEquals(data, decrypted)
    }

    @Test
    fun testRSAKeyWrappingAndUnwrapping() {
        val keyPair = ZungaCryptography.generateKeyPair()
        val aesKey = ZungaCryptography.generateAESKey()

        // Encrypt (Wrap) AES key with RSA Public Key
        val encryptedAESKeyStr = ZungaCryptography.encryptKeyRSA(aesKey, keyPair.public)
        assertFalse(encryptedAESKeyStr.isEmpty())

        // Decrypt (Unwrap) AES key with RSA Private Key
        val decryptedAESKey = ZungaCryptography.decryptKeyRSA(encryptedAESKeyStr, keyPair.private)
        assertNotNull(decryptedAESKey)

        // Verify the keys are identical by encrypting and decrypting with them
        val testData = "Teste de embrulho de chave"
        val (cipher, iv) = ZungaCryptography.encryptAESWithIv(testData, aesKey)
        val decrypted = ZungaCryptography.decryptAESWithIv(cipher, iv, decryptedAESKey)
        assertEquals(testData, decrypted)
    }

    @Test
    fun testEndToEndSecureMessageFlow() {
        // Alice generates key pair
        val aliceKeyPair = ZungaCryptography.generateKeyPair()
        val alicePubKeyStr = ZungaCryptography.publicKeyToString(aliceKeyPair.public)

        // Bob generates key pair
        val bobKeyPair = ZungaCryptography.generateKeyPair()
        val bobPubKeyStr = ZungaCryptography.publicKeyToString(bobKeyPair.public)

        // Alice's original plaintext message for Bob
        val plainText = "Olá Bob, este é um segredo criptografado de ponta a ponta!"

        // --- SENDER (Alice) ---
        // 1. Generate random 256-bit AES symmetric key
        val aesKey = ZungaCryptography.generateAESKey()

        // 2. Encrypt plaintext message with AES
        val (cipherText, iv) = ZungaCryptography.encryptAESWithIv(plainText, aesKey)

        // 3. Encrypt AES key using Bob's RSA Public Key (retrieved from handshake)
        val bobPubKey = ZungaCryptography.stringToPublicKey(bobPubKeyStr)
        val encryptedAESKey = ZungaCryptography.encryptKeyRSA(aesKey, bobPubKey)

        // 4. Sign the ciphertext using Alice's RSA Private Key
        val signature = ZungaCryptography.sign(cipherText, aliceKeyPair.private)

        // --- RECEIVER (Bob) ---
        // Bob receives: cipherText, encryptedAESKey, iv, signature, alicePubKeyStr

        // 1. Validate Alice's digital signature on the ciphertext
        val isSignatureValid = ZungaCryptography.verify(cipherText, signature, alicePubKeyStr)
        assertTrue("Assinatura digital inválida!", isSignatureValid)

        // 2. Decrypt AES key using Bob's RSA Private Key
        val decryptedAESKey = ZungaCryptography.decryptKeyRSA(encryptedAESKey, bobKeyPair.private)

        // 3. Decrypt the ciphertext content using the decrypted AES key and IV
        val decryptedContent = ZungaCryptography.decryptAESWithIv(cipherText, iv, decryptedAESKey)

        // Assert message received matches original plaintext
        assertEquals(plainText, decryptedContent)
    }
}
