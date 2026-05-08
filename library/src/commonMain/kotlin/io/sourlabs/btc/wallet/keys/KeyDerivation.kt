package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.KeyPath

/**
 * Derives extended private keys at arbitrary BIP-32 paths from a raw seed.
 *
 * [HDWalletManager] is the right entry point for BIP-44/49/84/86 chain/index
 * derivation within a fixed account. This object is for the case where the
 * caller wants to walk a non-BIP-44 path — application-level identity keys
 * (e.g. `m/2026'/0'/0'`), test vectors, or recovery of keys at non-standard
 * paths.
 */
object KeyDerivation {

    /**
     * Derive an extended private key at the given BIP-32 path.
     *
     * @param seed 64-byte BIP-39 seed (e.g. produced by `SeedManager.toSeed(...)`).
     * @param path BIP-32 path string. Hardened segments use `'` or `h`
     *   (e.g. `"m/2026'/0'/0'"` or `"m/84h/0h/0h/0/0"`). The leading `m/`
     *   is optional. A bare `"m"` returns the master key unchanged.
     */
    fun derivePrivateKeyAtPath(seed: ByteArray, path: String): ExtendedPrivateKey {
        val masterKey = DeterministicWallet.generate(ByteVector(seed))
        return DeterministicWallet.derivePrivateKey(masterKey, path)
    }

    /**
     * Derive an extended private key at the given pre-parsed BIP-32 path.
     */
    fun derivePrivateKeyAtPath(seed: ByteArray, path: KeyPath): ExtendedPrivateKey {
        val masterKey = DeterministicWallet.generate(ByteVector(seed))
        return DeterministicWallet.derivePrivateKey(masterKey, path)
    }
}
