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
     * Bitcoin Core's standard rule: an output is "dust" if it costs more in fees
     * to spend than a third of its own value would yield at the minimum relay
     * fee. So the dust floor is `3 × inputVSize × minRelayFeeSatPerVb`.
     *
     * Per-type defaults at minRelayFee = 1 sat/vB:
     *   P2PKH:        444 sats  (3 × 148)
     *   P2SH / P2SH_P2WPKH: 273 sats  (3 × 91)
     *   P2WPKH:       204 sats  (3 × 68)
     *   P2TR:         174 sats  (3 × 58)
     *
     * For generic [ScriptType.P2SH], the worst-case-typical assumption is
     * P2SH-wrapping-P2WPKH (91 vbytes) — the only kind of P2SH this wallet
     * is likely to send to in practice.
     */
    fun dustThreshold(scriptType: ScriptType, minRelayFeeSatPerVb: Long = 1): Long {
        val spendingInputVSize = when (scriptType) {
            ScriptType.P2PKH -> 148
            ScriptType.P2SH,
            ScriptType.P2SH_P2WPKH -> 91
            ScriptType.P2WPKH -> 68
            ScriptType.P2TR -> 58
        }
        return 3L * spendingInputVSize * minRelayFeeSatPerVb
    }
}
