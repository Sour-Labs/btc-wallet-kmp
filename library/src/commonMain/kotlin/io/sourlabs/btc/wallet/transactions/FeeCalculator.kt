package io.sourlabs.btc.wallet.transactions

import io.sourlabs.btc.wallet.models.ScriptType

/**
 * Per-script-type virtual size and fee estimation.
 *
 * Centralizes the byte accounting that was previously hardcoded inside
 * [TransactionBuilder] (with full per-script-type detail) and
 * [io.sourlabs.btc.wallet.utxo.UnspentOutputSelector] /
 * [io.sourlabs.btc.wallet.utxo.UnspentOutputProvider] (where every input was
 * assumed to be P2WPKH at 68 vbytes — under-counting BIP-44 by ~50% and
 * over-counting BIP-86 slightly).
 *
 * Sizes are computed exactly the way the original [TransactionBuilder] did so
 * pre-existing transactions remain reproducible:
 *   header = 4 (version) + 4 (locktime) + 1 (input count) + 1 (output count)
 *   segwit marker + flag adds 2 base bytes when any input carries witness data
 *   each input contributes its `base` bytes plus a `witness` chunk that's
 *     divided by 4 in the final vsize calculation
 *   each output is its raw bytes (no witness discount)
 */
internal object FeeCalculator {

    /**
     * Virtual size in vbytes of a single output paying to the given script type.
     * Each output is `8 (amount) + 1 (script length) + scriptPubKey bytes`.
     *
     * P2SH and P2SH_P2WPKH share an output size: from the outside both produce
     * `OP_HASH160 <20> OP_EQUAL` (23 bytes).
     */
    fun outputVSize(scriptType: ScriptType): Int = when (scriptType) {
        ScriptType.P2PKH -> 34            // 8 + 1 + 25 (OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG)
        ScriptType.P2SH,
        ScriptType.P2SH_P2WPKH -> 32      // 8 + 1 + 23 (OP_HASH160 <20> OP_EQUAL)
        ScriptType.P2WPKH -> 31           // 8 + 1 + 22 (OP_0 <20>)
        ScriptType.P2TR -> 43             // 8 + 1 + 34 (OP_1 <32>)
    }

    /**
     * Virtual size in vbytes of the full transaction with the given input/output
     * script types. The vsize formula is `base + (witness + 3) / 4` (integer
     * division rounds witness chunks up to the next vbyte).
     */
    fun estimateVSize(inputs: List<ScriptType>, outputs: List<ScriptType>): Int {
        // 4 (version) + 4 (locktime) + 1 (input count) + 1 (output count) = 10 base bytes
        var base = 10
        var witness = 0
        for (script in inputs) {
            when (script) {
                ScriptType.P2PKH -> {
                    // 32 (prev txid) + 4 (prev vout) + 1 (script length) + ~107 (sig + pubkey scriptSig) + 4 (sequence)
                    base += 148
                }
                ScriptType.P2SH_P2WPKH -> {
                    // Non-witness: 32 + 4 + 1 + 23 (scriptSig pushes redeem) + 4 = 64
                    // Witness:     1 (count) + 73 (sig) + 34 (pubkey w/ length) = 108
                    base += 64
                    witness += 108
                }
                ScriptType.P2WPKH -> {
                    // Non-witness: 32 + 4 + 1 + 0 + 4 = 41
                    // Witness:     108
                    base += 41
                    witness += 108
                }
                ScriptType.P2TR -> {
                    // Non-witness: 32 + 4 + 1 + 0 + 4 = 41
                    // Witness:     1 + 65 (Schnorr sig, SIGHASH_DEFAULT) = 66
                    base += 41
                    witness += 66
                }
                ScriptType.P2SH -> error(
                    "Cannot estimate vsize for generic P2SH input — wallet keys must be tagged P2SH_P2WPKH"
                )
            }
        }
        if (witness > 0) base += 2 // segwit marker (1) + flag (1)
        base += outputs.sumOf { outputVSize(it) }
        return base + (witness + 3) / 4
    }

    /** Estimate the transaction fee in satoshis. */
    fun estimateFee(
        inputs: List<ScriptType>,
        outputs: List<ScriptType>,
        feeRateSatPerVb: Long,
    ): Long = estimateVSize(inputs, outputs) * feeRateSatPerVb

    /**
     * Minimum non-dust output value for the given script type, computed via
     * Bitcoin Core's `GetDustThreshold` (`src/policy/policy.cpp`):
     *
     *   dust = (outputSize + spendingInputSize) × dustRelayFee
     *
     * Bitcoin Core uses two canonical spending-input sizes regardless of the
     * actual witness program: 148 vbytes for non-witness outputs (P2PKH, P2SH),
     * and 67 vbytes for any witness output (P2WPKH, P2TR, future versions).
     * P2SH-wrapping-P2WPKH counts as non-witness here because its scriptPubKey
     * looks like plain P2SH from the outside — that's the more conservative
     * assumption (larger dust threshold), and it's what the network enforces.
     *
     * Per-type values at the default dustRelayFee = 3 sat/vB (Bitcoin Core's
     * `DEFAULT_DUST_RELAY_FEE = 3000 sat/kvB`):
     *   P2PKH:                546 sats   (34 + 148) × 3
     *   P2SH / P2SH_P2WPKH:   540 sats   (32 + 148) × 3
     *   P2WPKH:               294 sats   (31 +  67) × 3
     *   P2TR:                 330 sats   (43 +  67) × 3
     *
     * The previous (PR-08 first commit) formula omitted the output size and
     * used minRelayFee = 1 sat/vB, producing thresholds 30–40% lower than the
     * network's. Amounts between those numbers and these would have passed
     * validation but been rejected on broadcast as dust — exactly the failure
     * the validation gate is meant to catch.
     */
    fun dustThreshold(scriptType: ScriptType, dustRelayFeeSatPerVb: Long = 3): Long {
        val spendingInputVSize = when (scriptType) {
            // Non-witness outputs: a P2PKH input at 148 vbytes is the canonical worst case.
            ScriptType.P2PKH,
            ScriptType.P2SH,
            ScriptType.P2SH_P2WPKH -> 148
            // Witness outputs: Bitcoin Core uses a uniform 67-vbyte spending input.
            ScriptType.P2WPKH,
            ScriptType.P2TR -> 67
        }
        return (outputVSize(scriptType) + spendingInputVSize) * dustRelayFeeSatPerVb
    }
}
