package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import io.sourlabs.btc.wallet.descriptors.MultisigAddressDeriver
import io.sourlabs.btc.wallet.descriptors.MultisigDescriptor
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.WalletPublicKey

/**
 * Strategy for producing a [WalletPublicKey] at a given `(chain, index)` pair.
 *
 * Two concrete implementations:
 *  - [HdWalletKeySource] — single-key BIP-44/49/84/86 wallets. Derives one
 *    public key per index from an [HDWalletManager] and lets the rest of the
 *    pipeline derive the script from `(publicKey, scriptType)` at use time.
 *  - [MultisigKeySource] — `wsh(sortedmulti)` watch-only wallets. Derives N
 *    cosigner pubkeys per index, computes the P2WSH scriptPubKey up front via
 *    [MultisigAddressDeriver], and writes it onto the [WalletPublicKey] so
 *    downstream sync and address resolution use the script directly without
 *    re-deriving it from a single pubkey (which is impossible for multisig).
 *
 * [PublicKeyManager] depends on this interface rather than [HDWalletManager]
 * directly, so swapping derivation strategies (single-key ↔ multisig) doesn't
 * require touching the gap-limit scanner.
 */
interface WalletKeySource {
    val network: Network
    val purpose: Purpose
    val account: Int

    /**
     * Build the [WalletPublicKey] entry for the gap-limit slot at
     * `(isExternal, index)`. Implementations populate the appropriate fields:
     *  - single-key: [WalletPublicKey.publicKey] + [WalletPublicKey.publicKeyHash]
     *    (hash160); [WalletPublicKey.scriptPubKey] left null so the address
     *    converter derives the script on the fly.
     *  - multisig: [WalletPublicKey.publicKey] is the first cosigner's child
     *    key (a representative pubkey, kept non-null for backwards compat);
     *    [WalletPublicKey.publicKeyHash] is the 32-byte SHA-256 witness
     *    program; [WalletPublicKey.scriptPubKey] holds the full P2WSH script
     *    so the address converter can short-circuit.
     */
    fun deriveKey(isExternal: Boolean, index: Int): WalletPublicKey
}

/**
 * [WalletKeySource] backed by an [HDWalletManager] — the existing single-key
 * derivation path. Preserves the legacy [PublicKeyManager.deriveKey] behaviour
 * exactly so single-key wallets see no change.
 */
class HdWalletKeySource(
    private val hdWalletManager: HDWalletManager,
) : WalletKeySource {
    override val network: Network get() = hdWalletManager.network
    override val purpose: Purpose get() = hdWalletManager.purpose
    override val account: Int get() = hdWalletManager.account

    override fun deriveKey(isExternal: Boolean, index: Int): WalletPublicKey {
        val publicKey = hdWalletManager.derivePublicKey(isExternal, index)
        val path = hdWalletManager.getDerivationPath(isExternal, index)
        val hash = Crypto.hash160(publicKey.value)
        return WalletPublicKey(
            path = path,
            purpose = hdWalletManager.purpose,
            account = hdWalletManager.account,
            isExternal = isExternal,
            index = index,
            publicKey = publicKey,
            publicKeyHash = hash,
            isUsed = false,
        )
    }
}

/**
 * [WalletKeySource] for a `wsh(sortedmulti(M, KEY1, ..., KEYN))` watch-only
 * wallet. Per-index it derives one child pubkey from each cosigner, applies
 * BIP-67 sorting, assembles the redeem script, and writes the P2WSH
 * scriptPubKey onto the resulting [WalletPublicKey] so the downstream pipeline
 * doesn't need to re-derive it.
 *
 * The synthetic derivation `path` is `wsh-sortedmulti/<M>-of-<N>/<chain>/<index>`
 * — purely a storage key, not a real BIP-32 path. The address itself is
 * cosigner-set-derived, not single-xpub-derived, so a BIP-32 path would be
 * misleading.
 */
class MultisigKeySource(
    private val descriptor: MultisigDescriptor,
) : WalletKeySource {
    override val network: Network get() = descriptor.network
    override val purpose: Purpose get() = descriptor.purpose
    override val account: Int get() = descriptor.account

    private val pathPrefix: String by lazy {
        when (descriptor) {
            is MultisigDescriptor.WshSortedMulti ->
                "wsh-sortedmulti/${descriptor.threshold}-of-${descriptor.keys.size}"
        }
    }

    override fun deriveKey(isExternal: Boolean, index: Int): WalletPublicKey {
        val chainStep = if (isExternal) 0L else 1L
        // Derive once, both for the scriptPubKey computation below (we need
        // every cosigner pubkey to build the redeem script) and to surface
        // the first cosigner as the representative `publicKey` on the entry.
        val childPubkeys: List<PublicKey> = descriptor.keys.map { key ->
            val parent = DeterministicWallet.ExtendedPublicKey.decode(key.extendedPublicKey).second
            val chainKey = DeterministicWallet.derivePublicKey(parent, chainStep)
            DeterministicWallet.derivePublicKey(chainKey, index.toLong()).publicKey
        }

        val ordered: List<PublicKey> = if (descriptor.sorted) {
            childPubkeys.sortedWith(BitcoinPubKeyLex)
        } else {
            childPubkeys
        }
        val redeemScript = Script.write(Script.createMultiSigMofN(descriptor.threshold, ordered))
        val witnessProgram = Crypto.sha256(redeemScript)
        val scriptPubKey = Script.write(Script.pay2wsh(redeemScript))

        return WalletPublicKey(
            path = "$pathPrefix/$chainStep/$index",
            purpose = descriptor.purpose,
            account = descriptor.account,
            isExternal = isExternal,
            index = index,
            // Representative pubkey kept non-null for downstream code that
            // reads it without special-casing multisig. Never used for signing
            // (the wallet is watch-only) or for script derivation (overridden
            // by scriptPubKey below).
            publicKey = childPubkeys.first(),
            publicKeyHash = witnessProgram,
            scriptPubKey = scriptPubKey,
            scriptTypeOverride = ScriptType.P2WSH,
            isUsed = false,
        )
    }

    private companion object {
        /**
         * BIP-67 lexicographic comparator for compressed pubkeys. Same as the
         * one in [MultisigAddressDeriver]; kept local to avoid making it a
         * public surface of the descriptor module.
         */
        private val BitcoinPubKeyLex: Comparator<PublicKey> = Comparator { a, b ->
            val aBytes = a.value.toByteArray()
            val bBytes = b.value.toByteArray()
            val minLen = minOf(aBytes.size, bBytes.size)
            for (i in 0 until minLen) {
                val cmp = (aBytes[i].toInt() and 0xff) - (bBytes[i].toInt() and 0xff)
                if (cmp != 0) return@Comparator cmp
            }
            aBytes.size - bBytes.size
        }
    }
}
