package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.*
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.WalletPublicKey

/**
 * Converts public keys to addresses for all supported script types.
 *
 * Open so that focused tests (e.g. counting scriptPubKey derivations during
 * sync) can subclass with overridden methods rather than introducing a wrapper.
 */
open class AddressConverter(
    private val network: Network
) {
    private val chain: Chain = network.toChain()
    private val chainHash: BlockHash = chain.chainHash

    /**
     * Convert a wallet public key to its corresponding address.
     *
     * When [WalletPublicKey.scriptPubKey] is non-null (multisig P2WSH entries),
     * the address is recovered from the stored scriptPubKey directly. Otherwise
     * the address is derived from the embedded public key and script type the
     * way it always has been for single-key wallets.
     */
    fun toAddress(walletPublicKey: WalletPublicKey): String {
        val overrideScript = walletPublicKey.scriptPubKey
        if (overrideScript != null) {
            return addressToScriptPubKeyReverse(overrideScript)
                ?: throw IllegalStateException(
                    "Failed to recover address from stored scriptPubKey for path ${walletPublicKey.path}"
                )
        }
        return toAddress(walletPublicKey.publicKey, walletPublicKey.scriptType)
    }

    /**
     * Convert a public key to an address for the given script type.
     *
     * P2WSH is intentionally not supported here — a P2WSH address depends on a
     * full redeem script (multisig, miniscript, …), not a single public key.
     * The multisig watch-only path goes through [toAddress] with a
     * [WalletPublicKey] that carries its own [WalletPublicKey.scriptPubKey].
     */
    fun toAddress(publicKey: PublicKey, scriptType: ScriptType): String {
        return when (scriptType) {
            ScriptType.P2PKH -> toP2PKHAddress(publicKey)
            ScriptType.P2SH_P2WPKH -> toP2SHP2WPKHAddress(publicKey)
            ScriptType.P2WPKH -> toP2WPKHAddress(publicKey)
            ScriptType.P2TR -> toP2TRAddress(publicKey)
            ScriptType.P2SH -> throw IllegalArgumentException(
                "Cannot derive an address for generic P2SH from a public key alone — a P2SH " +
                    "address requires a redeem script. Use P2SH_P2WPKH for nested-SegWit keys."
            )
            ScriptType.P2WSH -> throw IllegalArgumentException(
                "Cannot derive an address for P2WSH from a public key alone — pass a WalletPublicKey " +
                    "with a non-null scriptPubKey (set by MultisigKeySource) to toAddress(WalletPublicKey) instead."
            )
        }
    }

    private fun addressToScriptPubKeyReverse(scriptPubKey: ByteArray): String? {
        val parsed = Script.parse(scriptPubKey)
        val result = Bitcoin.addressFromPublicKeyScript(chainHash, parsed)
        return if (result.isRight) {
            @Suppress("UNCHECKED_CAST")
            (result as fr.acinq.bitcoin.utils.Either.Right<String>).value
        } else {
            null
        }
    }

    /**
     * Convert a public key to a P2PKH (legacy) address.
     */
    fun toP2PKHAddress(publicKey: PublicKey): String {
        val script = Script.pay2pkh(publicKey)
        return addressFromScript(script)
            ?: throw IllegalStateException("Failed to create P2PKH address")
    }

    /**
     * Convert a public key to a P2SH-P2WPKH (nested SegWit) address.
     */
    fun toP2SHP2WPKHAddress(publicKey: PublicKey): String {
        val witnessScript = Script.pay2wpkh(publicKey)
        val p2shScript = Script.pay2sh(witnessScript)
        return addressFromScript(p2shScript)
            ?: throw IllegalStateException("Failed to create P2SH-P2WPKH address")
    }

    /**
     * Convert a public key to a P2WPKH (native SegWit v0) address.
     */
    fun toP2WPKHAddress(publicKey: PublicKey): String {
        val script = Script.pay2wpkh(publicKey)
        return addressFromScript(script)
            ?: throw IllegalStateException("Failed to create P2WPKH address")
    }

    /**
     * Convert a public key to a P2TR (Taproot) address.
     */
    fun toP2TRAddress(publicKey: PublicKey): String {
        val xOnlyKey = publicKey.xOnly()
        // For BIP86 key-path only spending, use KeyPathTweak
        val script = Script.pay2tr(xOnlyKey, Crypto.TaprootTweak.KeyPathTweak)
        return addressFromScript(script)
            ?: throw IllegalStateException("Failed to create P2TR address")
    }

    /**
     * Parse an address and return its script type if valid.
     */
    fun parseAddress(address: String): AddressInfo? {
        return try {
            val result = Bitcoin.addressToPublicKeyScript(chainHash, address)
            if (result.isLeft) return null

            @Suppress("UNCHECKED_CAST")
            val script = (result as fr.acinq.bitcoin.utils.Either.Right<List<ScriptElt>>).value
            val scriptBytes = Script.write(script)

            val scriptType = when {
                Script.isPay2pkh(scriptBytes) -> ScriptType.P2PKH
                // Generic P2SH — the scriptPubKey (`OP_HASH160 <h> OP_EQUAL`) doesn't
                // expose what the redeem script wraps, so we can't tell apart a
                // multisig, a nested SegWit, or any other P2SH variant. Wallet-owned
                // P2SH_P2WPKH keys are matched separately via scriptPubKey comparison
                // in TransactionProcessor.findByScriptPubKey.
                Script.isPay2sh(scriptBytes) -> ScriptType.P2SH
                Script.isPay2wpkh(scriptBytes) -> ScriptType.P2WPKH
                Script.isPay2wsh(scriptBytes) -> ScriptType.P2WSH
                Script.isPay2tr(scriptBytes) -> ScriptType.P2TR
                else -> return null
            }

            AddressInfo(
                address = address,
                scriptType = scriptType,
                scriptPubKey = scriptBytes
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validate if an address is valid for the configured network.
     */
    fun isValidAddress(address: String): Boolean {
        return parseAddress(address) != null
    }

    /**
     * Create a scriptPubKey for the given address.
     */
    fun addressToScriptPubKey(address: String): ByteArray? {
        return try {
            val result = Bitcoin.addressToPublicKeyScript(chainHash, address)
            if (result.isLeft) return null

            @Suppress("UNCHECKED_CAST")
            val script = (result as fr.acinq.bitcoin.utils.Either.Right<List<ScriptElt>>).value
            Script.write(script)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create the scriptPubKey for a given public key and script type.
     */
    open fun createScriptPubKey(publicKey: PublicKey, scriptType: ScriptType): ByteArray {
        val script = when (scriptType) {
            ScriptType.P2PKH -> Script.pay2pkh(publicKey)
            ScriptType.P2SH_P2WPKH -> {
                val witnessScript = Script.pay2wpkh(publicKey)
                Script.pay2sh(witnessScript)
            }
            ScriptType.P2WPKH -> Script.pay2wpkh(publicKey)
            ScriptType.P2TR -> {
                val xOnlyKey = publicKey.xOnly()
                Script.pay2tr(xOnlyKey, Crypto.TaprootTweak.KeyPathTweak)
            }
            ScriptType.P2SH -> throw IllegalArgumentException(
                "Cannot derive a scriptPubKey for generic P2SH from a public key alone — " +
                    "wallet-owned nested-SegWit keys must be tagged P2SH_P2WPKH."
            )
            ScriptType.P2WSH -> throw IllegalArgumentException(
                "Cannot derive a scriptPubKey for P2WSH from a public key alone — " +
                    "use createScriptPubKey(walletPublicKey) on a WalletPublicKey whose " +
                    "scriptPubKey field was populated by MultisigKeySource."
            )
        }
        return Script.write(script)
    }

    /**
     * Get the scriptPubKey for a [WalletPublicKey] — short-circuits to the
     * stored override when present (multisig), falls back to deriving from
     * [WalletPublicKey.publicKey] + [WalletPublicKey.scriptType] otherwise.
     */
    open fun createScriptPubKey(walletPublicKey: WalletPublicKey): ByteArray =
        walletPublicKey.scriptPubKey
            ?: createScriptPubKey(walletPublicKey.publicKey, walletPublicKey.scriptType)

    /**
     * Get the address prefix for the given purpose and network.
     */
    fun getAddressPrefix(purpose: Purpose): String {
        return when (purpose) {
            Purpose.BIP44 -> if (network == Network.MAINNET) "1" else "m/n"
            Purpose.BIP49 -> if (network == Network.MAINNET) "3" else "2"
            Purpose.BIP84 -> if (network == Network.MAINNET) "bc1q" else "tb1q"
            Purpose.BIP86 -> if (network == Network.MAINNET) "bc1p" else "tb1p"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addressFromScript(script: List<ScriptElt>): String? {
        val result = Bitcoin.addressFromPublicKeyScript(chainHash, script)
        return if (result.isRight) {
            (result as fr.acinq.bitcoin.utils.Either.Right<String>).value
        } else {
            null
        }
    }

    /**
     * Information about a parsed address.
     */
    data class AddressInfo(
        val address: String,
        val scriptType: ScriptType,
        val scriptPubKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as AddressInfo
            return address == other.address && scriptType == other.scriptType && scriptPubKey.contentEquals(other.scriptPubKey)
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + scriptType.hashCode()
            result = 31 * result + scriptPubKey.contentHashCode()
            return result
        }
    }
}
