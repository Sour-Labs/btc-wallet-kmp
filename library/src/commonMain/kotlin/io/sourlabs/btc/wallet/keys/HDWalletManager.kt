package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.bitcoin.PublicKey
import io.sourlabs.btc.wallet.api.WalletInitializationException
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.descriptors.Descriptor
import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose

// BIP-32 depth at which the account key sits in a BIP-44/49/84/86 wallet:
//   m / purpose' / coin' / account'
// (master is depth 0, purpose is 1, coin is 2, account is 3).
private const val ACCOUNT_KEY_DEPTH = 3

/**
 * Manages HD wallet key derivation using BIP32/BIP44/BIP49/BIP84/BIP86.
 *
 * Internally holds the account-level extended keys at `m/purpose'/coin'/account'`
 * — never the master key. Mnemonic/seed factory methods derive the account key
 * from the master once at construction; xprv/xpub factories expect the input to
 * already be at account depth (BIP-32 depth 3) and reject anything else with
 * [WalletInitializationException] so callers can't silently fall through into
 * deriving the wrong key.
 */
class HDWalletManager private constructor(
    private val accountPrivateKey: ExtendedPrivateKey?,
    private val accountPublicKey: ExtendedPublicKey,
    val purpose: Purpose,
    val network: Network,
    val account: Int
) {
    /**
     * Whether this is a watch-only wallet (no private key available).
     */
    val isWatchOnly: Boolean
        get() = accountPrivateKey == null

    /**
     * The account-level extended public key.
     */
    val accountXpub: ExtendedPublicKey
        get() = accountPublicKey

    /**
     * Derive a public key at the given chain and index.
     * @param isExternal true for external (receive) chain, false for internal (change) chain
     */
    fun derivePublicKey(isExternal: Boolean, index: Int): PublicKey {
        val chain = if (isExternal) 0L else 1L
        val chainKey = DeterministicWallet.derivePublicKey(accountPublicKey, chain)
        val derivedKey = DeterministicWallet.derivePublicKey(chainKey, index.toLong())
        return derivedKey.publicKey
    }

    /**
     * Derive a private key at the given chain and index.
     *
     * Uses the cached account-level extended private key, so each call costs
     * exactly two derivations (chain + index) — not the five (purpose, coin,
     * account, chain, index) the master-keyed version did.
     *
     * @throws IllegalStateException if this is a watch-only wallet
     */
    fun derivePrivateKey(isExternal: Boolean, index: Int): ExtendedPrivateKey {
        val accountKey = accountPrivateKey
            ?: throw IllegalStateException("Cannot derive private key from watch-only wallet")
        val chainKey = DeterministicWallet.derivePrivateKey(accountKey, if (isExternal) 0L else 1L)
        return DeterministicWallet.derivePrivateKey(chainKey, index.toLong())
    }

    /**
     * Get the full derivation path for a key.
     */
    fun getDerivationPath(isExternal: Boolean, index: Int): String {
        val chain = if (isExternal) 0 else 1
        return "m/${purpose.value}'/${network.coinType}'/$account'/$chain/$index"
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
                    accountExtendedPrivateKey = config.accountExtendedPrivateKey,
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
                is WalletConfig.WatchOnlyDescriptor -> fromDescriptor(config.parsedDescriptor)
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
            val seed = mnemonic.toSeed(passphrase)
            return fromSeed(seed, purpose, network, account)
        }

        /**
         * Create from raw seed bytes. Derives the account key from the master once;
         * subsequent private-key derivations work from the cached account key.
         */
        fun fromSeed(
            seed: ByteArray,
            purpose: Purpose = Purpose.BIP84,
            network: Network = Network.MAINNET,
            account: Int = 0
        ): HDWalletManager {
            val masterKey = DeterministicWallet.generate(ByteVector(seed))
            val accountKey = deriveAccountKey(masterKey, purpose, network, account)
            return HDWalletManager(
                accountPrivateKey = accountKey,
                accountPublicKey = DeterministicWallet.publicKey(accountKey),
                purpose = purpose,
                network = network,
                account = account
            )
        }

        /**
         * Create from an *account-level* extended private key (BIP-32 depth 3, e.g.
         * a key exported at `m/84'/0'/0'`). The input is not derived further — it
         * is the account key.
         *
         * If you have a master xprv (depth 0), derive the account key first:
         * ```
         * val seed = mnemonic.toSeed()
         * val master = DeterministicWallet.generate(seed)
         * val accountXprv = DeterministicWallet.derivePrivateKey(master,
         *     "m/${'$'}{purpose.value}'/${'$'}{network.coinType}'/${'$'}{account}'").encode(testnet)
         * ```
         *
         * @throws WalletInitializationException if the key is not at account depth.
         */
        fun fromExtendedPrivateKey(
            accountExtendedPrivateKey: String,
            purpose: Purpose = Purpose.BIP84,
            network: Network = Network.MAINNET,
            account: Int = 0
        ): HDWalletManager {
            val decoded = ExtendedPrivateKey.decode(accountExtendedPrivateKey)
            val accountKey = decoded.second
            requireAccountDepth(accountKey.depth, isPrivate = true, purpose, network, account)
            return HDWalletManager(
                accountPrivateKey = accountKey,
                accountPublicKey = DeterministicWallet.publicKey(accountKey),
                purpose = purpose,
                network = network,
                account = account
            )
        }

        /**
         * Create a watch-only wallet from an account-level extended public key
         * (BIP-32 depth 3). This matches the typical hardware wallet export
         * (xpub at `m/purpose'/coin'/account'`).
         *
         * @throws WalletInitializationException if the key is not at account depth.
         */
        fun fromExtendedPublicKey(
            extendedPublicKey: String,
            purpose: Purpose = Purpose.BIP84,
            network: Network = Network.MAINNET,
            account: Int = 0
        ): HDWalletManager {
            val decoded = ExtendedPublicKey.decode(extendedPublicKey)
            val publicKey = decoded.second
            requireAccountDepth(publicKey.depth, isPrivate = false, purpose, network, account)
            return HDWalletManager(
                accountPrivateKey = null,
                accountPublicKey = publicKey,
                purpose = purpose,
                network = network,
                account = account
            )
        }

        /**
         * Create a watch-only wallet from a parsed BIP-380 output descriptor.
         * The descriptor's wrapper picks the BIP purpose, the embedded extended
         * key picks the network, and the key origin (if present) picks the
         * account index.
         */
        fun fromDescriptor(descriptor: Descriptor): HDWalletManager =
            fromExtendedPublicKey(
                extendedPublicKey = descriptor.extendedPublicKey,
                purpose = descriptor.purpose,
                network = descriptor.network,
                account = descriptor.account,
            )

        /**
         * Convenience: parse a descriptor string and build the corresponding
         * watch-only wallet manager. Throws [io.sourlabs.btc.wallet.api.DescriptorException]
         * if the descriptor is invalid or unsupported.
         */
        fun fromDescriptor(descriptor: String): HDWalletManager =
            fromDescriptor(Descriptor.parse(descriptor))

        private fun deriveAccountKey(
            master: ExtendedPrivateKey,
            purpose: Purpose,
            network: Network,
            account: Int,
        ): ExtendedPrivateKey {
            val purposeKey = DeterministicWallet.derivePrivateKey(
                master, DeterministicWallet.hardened(purpose.value.toLong())
            )
            val coinKey = DeterministicWallet.derivePrivateKey(
                purposeKey, DeterministicWallet.hardened(network.coinType.toLong())
            )
            return DeterministicWallet.derivePrivateKey(
                coinKey, DeterministicWallet.hardened(account.toLong())
            )
        }

        private fun requireAccountDepth(
            actualDepth: Int,
            isPrivate: Boolean,
            purpose: Purpose,
            network: Network,
            account: Int,
        ) {
            if (actualDepth != ACCOUNT_KEY_DEPTH) {
                val keyKind = if (isPrivate) "extended private key" else "extended public key"
                throw WalletInitializationException(
                    "Expected an account-level $keyKind (BIP-32 depth $ACCOUNT_KEY_DEPTH, " +
                        "e.g. m/${purpose.value}'/${network.coinType}'/$account'). " +
                        "Got depth $actualDepth. Derive the account key from the master before passing it in."
                )
            }
        }
    }
}
