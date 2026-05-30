package io.sourlabs.btc.wallet.descriptors

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script

/**
 * Derive a receive / change address for a [MultisigDescriptor] at a specific
 * gap-limit index.
 *
 * The algorithm follows BIP-67 + BIP-141:
 *  1. For each cosigner, derive the child public key at the soft path
 *     `chain/index` from its account-level xpub.
 *  2. Sort the resulting 33-byte compressed pubkeys lexicographically (BIP-67),
 *     but only when the descriptor is a `sortedmulti` variant. For a plain
 *     `multi` the input order is preserved.
 *  3. Build the bare multisig redeem script:
 *     `OP_M <pk1> <pk2> ... <pkN> OP_N OP_CHECKMULTISIG`
 *     using ACINQ's [Script.createMultiSigMofN].
 *  4. Wrap into a P2WSH scriptPubKey via [Script.pay2wsh] (which sha-256's the
 *     redeem script and emits `OP_0 <32-byte hash>`).
 *  5. Encode as bech32 (HRP `bc` on mainnet, `tb` on testnet) via
 *     [Bitcoin.addressFromPublicKeyScript].
 *
 * Kept as a stateless top-level object so it can be unit-tested in isolation
 * from the rest of `BitcoinKit` (no storage, no sync, no public-key manager).
 */
object MultisigAddressDeriver {

    /**
     * @param descriptor parsed multisig descriptor
     * @param change `false` for the external (receive) chain, `true` for the
     *   internal (change) chain. Maps to BIP-44 `chain` step 0 vs 1.
     * @param index gap-limit index, must be ≥ 0.
     * @return the bech32-encoded P2WSH address for this cosigner set at
     *   `chain/index`.
     */
    fun derive(descriptor: MultisigDescriptor, change: Boolean, index: Int): String {
        val derived = deriveScripts(descriptor, change, index)
        val chainHash = descriptor.network.toChain().chainHash
        val result = Bitcoin.addressFromPublicKeyScript(chainHash, Script.parse(derived.scriptPubKey))
        if (result.isLeft) {
            throw IllegalStateException(
                "Failed to encode P2WSH address for ${descriptor.network} at " +
                    "chain=${if (change) 1 else 0} index=$index"
            )
        }
        @Suppress("UNCHECKED_CAST")
        return (result as fr.acinq.bitcoin.utils.Either.Right<String>).value
    }

    /**
     * Core derivation: walk the cosigner xpubs to `chain/index`, apply BIP-67
     * sorting when the descriptor is sortedmulti, and assemble the redeem and
     * P2WSH scripts. Surfaces all the intermediate byte arrays the kit needs
     * to wire onto a `WalletPublicKey` (witness program for index lookups,
     * scriptPubKey for the on-chain match, ordered pubkeys for the
     * representative-key field on the record).
     *
     * Internal so both [derive] and [io.sourlabs.btc.wallet.keys.MultisigKeySource]
     * share the same sort + script-construction codepath — Cursor Bugbot
     * flagged the earlier duplication as a drift risk.
     */
    internal fun deriveScripts(
        descriptor: MultisigDescriptor,
        change: Boolean,
        index: Int,
    ): DerivedMultisigScripts {
        require(index >= 0) { "index must be non-negative, got $index" }

        val chainStep = if (change) 1L else 0L
        val derivedPubkeys = descriptor.keys.map { key ->
            val parent = DeterministicWallet.ExtendedPublicKey.decode(key.extendedPublicKey).second
            val chainKey = DeterministicWallet.derivePublicKey(parent, chainStep)
            val leafKey = DeterministicWallet.derivePublicKey(chainKey, index.toLong())
            leafKey.publicKey
        }

        val ordered = if (descriptor.sorted) {
            derivedPubkeys.sortedWith(PublicKeyLexicographicComparator)
        } else {
            derivedPubkeys
        }

        val redeemScript = Script.write(Script.createMultiSigMofN(descriptor.threshold, ordered))
        val witnessProgram = Crypto.sha256(redeemScript)
        val scriptPubKey = Script.write(Script.pay2wsh(redeemScript))

        return DerivedMultisigScripts(
            cosignerPubkeysInDescriptorOrder = derivedPubkeys,
            sortedPubkeys = ordered,
            redeemScript = redeemScript,
            witnessProgram = witnessProgram,
            scriptPubKey = scriptPubKey,
        )
    }

    /**
     * BIP-67 lexicographic comparator for compressed public keys. Compares the
     * 33-byte values byte-by-byte as unsigned integers — the same ordering
     * that Bitcoin Core's `sortedmulti` descriptor produces. Exposed as
     * `internal` so the kit's `MultisigKeySource` can reuse it without
     * reimplementing the byte-by-byte comparison.
     */
    internal val PublicKeyLexicographicComparator: Comparator<PublicKey> = Comparator { a, b ->
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

/**
 * Intermediate values produced by [MultisigAddressDeriver.deriveScripts] —
 * everything needed to encode the bech32 address *and* to wire a
 * `WalletPublicKey` record onto the gap-limit store.
 */
internal data class DerivedMultisigScripts(
    /** Cosigner child pubkeys at `chain/index`, in descriptor order. */
    val cosignerPubkeysInDescriptorOrder: List<PublicKey>,
    /**
     * Cosigner child pubkeys in the order they appear inside the redeem
     * script. For sortedmulti this is the BIP-67 lexicographic sort of
     * [cosignerPubkeysInDescriptorOrder]; for plain multi it equals the
     * descriptor order.
     */
    val sortedPubkeys: List<PublicKey>,
    /** Serialized bytes of `OP_M ... OP_N OP_CHECKMULTISIG`. */
    val redeemScript: ByteArray,
    /** 32-byte SHA-256 of the redeem script — the P2WSH witness program. */
    val witnessProgram: ByteArray,
    /** Serialized bytes of `OP_0 <32-byte witness program>` — the scriptPubKey. */
    val scriptPubKey: ByteArray,
)
