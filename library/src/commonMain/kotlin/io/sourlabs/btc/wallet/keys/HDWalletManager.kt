package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PublicKey
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.random.Random

/**
 * Manages HD wallet key derivation using BIP32/BIP44/BIP49/BIP84/BIP86.
 * Wraps ACINQ bitcoin-kmp DeterministicWallet.
 */
class HDWalletManager private constructor(
    private val masterPrivateKey: ExtendedPrivateKey?,
    private val accountPublicKey: ExtendedPublicKey,
    val purpose: Purpose,
    val network: Network,
    val account: Int
) {
    /**
     * Whether this is a watch-only wallet (no private key available).
     */
    val isWatchOnly: Boolean
        get() = masterPrivateKey == null

    /**
     * The account-level extended public key.
     */
    val accountXpub: ExtendedPublicKey
        get() = accountPublicKey

    /**
     * Derive a public key at the given chain and index.
     * @param isExternal true for external (receive) chain, false for internal (change) chain
     * @param index the key index
     */
    fun derivePublicKey(isExternal: Boolean, index: Int): PublicKey {
        val chain = if (isExternal) 0L else 1L
        val chainKey = DeterministicWallet.derivePublicKey(accountPublicKey, chain)
        val derivedKey = DeterministicWallet.derivePublicKey(chainKey, index.toLong())
        return derivedKey.publicKey
    }

    /**
     * Derive a private key at the given chain and index.
     * @throws IllegalStateException if this is a watch-only wallet
     */
    fun derivePrivateKey(isExternal: Boolean, index: Int): DeterministicWallet.ExtendedPrivateKey {
        masterPrivateKey ?: throw IllegalStateException("Cannot derive private key from watch-only wallet")

        val accountPrivateKey = getAccountPrivateKey()
        val chainKey = DeterministicWallet.derivePrivateKey(accountPrivateKey, if (isExternal) 0L else 1L)
        return DeterministicWallet.derivePrivateKey(chainKey, index.toLong())
    }

    /**
     * Get the full derivation path for a key.
     */
    fun getDerivationPath(isExternal: Boolean, index: Int): String {
        val chain = if (isExternal) 0 else 1
        return "m/${purpose.value}'/${network.coinType}'/$account'/$chain/$index"
    }

    private fun getAccountPrivateKey(): ExtendedPrivateKey {
        val privateKey = masterPrivateKey
            ?: throw IllegalStateException("Cannot get private key from watch-only wallet")

        val purposeKey = DeterministicWallet.derivePrivateKey(privateKey, DeterministicWallet.hardened(purpose.value.toLong()))
        val coinKey = DeterministicWallet.derivePrivateKey(purposeKey, DeterministicWallet.hardened(network.coinType.toLong()))
        return DeterministicWallet.derivePrivateKey(coinKey, DeterministicWallet.hardened(account.toLong()))
    }

    companion object {
        /**
         * Create an HDWalletManager from wallet configuration.
         */
        fun fromConfig(config: WalletConfig): HDWalletManager {
            return when (config) {
                is WalletConfig.FromMnemonic -> fromMnemonic(
                    mnemonic = config.mnemonic,
                    passphrase = config.passphrase,
                    purpose = config.purpose,
                    network = config.network,
                    account = config.account
                )
                is WalletConfig.FromSeed -> fromSeed(
                    seed = config.seed,
                    purpose = config.purpose,
                    network = config.network,
                    account = config.account
                )
                is WalletConfig.FromExtendedPrivateKey -> fromExtendedPrivateKey(
                    extendedKey = config.extendedKey,
                    purpose = config.purpose,
                    network = config.network,
                    account = config.account
                )
                is WalletConfig.WatchOnly -> fromExtendedPublicKey(
                    extendedPublicKey = config.extendedPublicKey,
                    purpose = config.purpose,
                    network = config.network,
                    account = config.account
                )
            }
        }

        /**
         * Create from BIP39 mnemonic.
         */
        fun fromMnemonic(
            mnemonic: List<String>,
            passphrase: String = "",
            purpose: Purpose = Purpose.BIP84,
            network: Network = Network.MAINNET,
            account: Int = 0
        ): HDWalletManager {
            val seed = MnemonicCode.toSeed(mnemonic, passphrase)
            return fromSeed(seed, purpose, network, account)
        }

        /**
         * Create from raw seed bytes.
         */
        fun fromSeed(
            seed: ByteArray,
            purpose: Purpose = Purpose.BIP84,
            network: Network = Network.MAINNET,
            account: Int = 0
        ): HDWalletManager {
            val masterKey = DeterministicWallet.generate(ByteVector(seed))

            // Derive account key: m/purpose'/coin'/account'
            val purposeKey = DeterministicWallet.derivePrivateKey(masterKey, DeterministicWallet.hardened(purpose.value.toLong()))
            val coinKey = DeterministicWallet.derivePrivateKey(purposeKey, DeterministicWallet.hardened(network.coinType.toLong()))
            val accountKey = DeterministicWallet.derivePrivateKey(coinKey, DeterministicWallet.hardened(account.toLong()))

            return HDWalletManager(
                masterPrivateKey = masterKey,
                accountPublicKey = DeterministicWallet.publicKey(accountKey),
                purpose = purpose,
                network = network,
                account = account
            )
        }

        /**
         * Create from extended private key string.
         */
        fun fromExtendedPrivateKey(
            extendedKey: String,
            purpose: Purpose = Purpose.BIP84,
            network: Network = Network.MAINNET,
            account: Int = 0
        ): HDWalletManager {
            val decoded = DeterministicWallet.ExtendedPrivateKey.decode(extendedKey)
            val privateKey = decoded.second

            // Derive account key from the master
            val purposeKey = DeterministicWallet.derivePrivateKey(privateKey, DeterministicWallet.hardened(purpose.value.toLong()))
            val coinKey = DeterministicWallet.derivePrivateKey(purposeKey, DeterministicWallet.hardened(network.coinType.toLong()))
            val accountKey = DeterministicWallet.derivePrivateKey(coinKey, DeterministicWallet.hardened(account.toLong()))

            return HDWalletManager(
                masterPrivateKey = privateKey,
                accountPublicKey = DeterministicWallet.publicKey(accountKey),
                purpose = purpose,
                network = network,
                account = account
            )
        }

        /**
         * Create a watch-only wallet from extended public key.
         */
        fun fromExtendedPublicKey(
            extendedPublicKey: String,
            purpose: Purpose = Purpose.BIP84,
            network: Network = Network.MAINNET,
            account: Int = 0
        ): HDWalletManager {
            val decoded = DeterministicWallet.ExtendedPublicKey.decode(extendedPublicKey)
            val publicKey = decoded.second

            return HDWalletManager(
                masterPrivateKey = null,
                accountPublicKey = publicKey,
                purpose = purpose,
                network = network,
                account = account
            )
        }

        /**
         * Validate a mnemonic phrase.
         */
        fun validateMnemonic(mnemonic: List<String>): Boolean {
            return try {
                MnemonicCode.validate(mnemonic)
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Generate a new mnemonic phrase.
         */
        fun generateMnemonic(wordCount: Int = 24): List<String> {
            val entropyBytes = when (wordCount) {
                12 -> 16
                15 -> 20
                18 -> 24
                21 -> 28
                24 -> 32
                else -> throw IllegalArgumentException("Word count must be 12, 15, 18, 21, or 24")
            }
            val entropy = Random.nextBytes(entropyBytes)
            return MnemonicCode.toMnemonics(entropy)
        }
    }
}
