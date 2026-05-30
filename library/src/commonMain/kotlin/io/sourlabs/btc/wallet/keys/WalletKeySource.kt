package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.Crypto
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
        // Delegate sorting + redeem-script + scriptPubKey construction to the
        // descriptor module so the two code paths can't drift. We only need
        // to wrap the result into a WalletPublicKey record here.
        val derived = MultisigAddressDeriver.deriveScripts(
            descriptor = descriptor,
            change = !isExternal,
            index = index,
        )
        val chainStep = if (isExternal) 0L else 1L
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
            publicKey = derived.cosignerPubkeysInDescriptorOrder.first(),
            publicKeyHash = derived.witnessProgram,
            scriptPubKey = derived.scriptPubKey,
            scriptTypeOverride = ScriptType.P2WSH,
            isUsed = false,
        )
    }
}
