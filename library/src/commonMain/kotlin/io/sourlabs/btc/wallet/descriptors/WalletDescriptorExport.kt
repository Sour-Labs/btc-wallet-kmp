package io.sourlabs.btc.wallet.descriptors

import fr.acinq.bitcoin.DeterministicWallet
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose

/**
 * Builds a canonical BIP-380 output descriptor (with checksum) that captures a
 * wallet as **watch-only**, ready to re-import into another wallet or another
 * instance of this app.
 *
 * The descriptor covers both the receive and change branches via the BIP-389
 * multipath `<0;1>` suffix, and carries a `[fingerprint/path]` key origin
 * whenever the wallet's master key is derivable (mnemonic- or seed-backed
 * wallets). Bare-xpub watch-only wallets have no recoverable master
 * fingerprint, so their descriptor is emitted without an origin — still a
 * valid, importable watch-only descriptor.
 *
 * Already-descriptor wallets ([WalletConfig.WatchOnlyDescriptor], including
 * multisig) are returned verbatim: the stored descriptor *is* the canonical
 * export.
 */
object WalletDescriptorExport {

    /**
     * @return a checksummed BIP-380 output descriptor for [config] — a single-key
     *   `wrapper([origin]xpub + multipath)` form, or the stored descriptor
     *   verbatim for descriptor-backed (incl. multisig) watch-only wallets.
     */
    fun outputDescriptor(config: WalletConfig): String = when (config) {
        is WalletConfig.WatchOnlyDescriptor -> config.descriptor
        else -> {
            val manager = HDWalletManager.fromConfig(config)
            val xpub = manager.accountXpub.encode(testnet = manager.network != Network.MAINNET)
            val origin = masterFingerprintHex(config)?.let { fingerprint ->
                "[$fingerprint/${manager.purpose.value}h/${manager.network.coinType}h/${manager.account}h]"
            }.orEmpty()
            val body = wrap(manager.purpose, "$origin$xpub/<0;1>/*")
            val checksum = DescriptorChecksum.compute(body)
                ?: error("Failed to compute descriptor checksum for '$body'")
            "$body#$checksum"
        }
    }

    /** Wrap a key expression in the script function dictated by [purpose]. */
    private fun wrap(purpose: Purpose, keyExpression: String): String = when (purpose) {
        Purpose.BIP44 -> "pkh($keyExpression)"
        Purpose.BIP49 -> "sh(wpkh($keyExpression))"
        Purpose.BIP84 -> "wpkh($keyExpression)"
        Purpose.BIP86 -> "tr($keyExpression)"
    }

    /**
     * The 8-hex-character master key fingerprint, when it can be derived from
     * [config]. Mnemonic and raw-seed configs carry the master key; account-level
     * xprv and bare-xpub configs do not, so their descriptors omit the origin.
     */
    private fun masterFingerprintHex(config: WalletConfig): String? {
        val seed = when (config) {
            is WalletConfig.FromMnemonic -> config.mnemonic.toSeed(config.passphrase)
            is WalletConfig.FromSeed -> config.seed
            else -> return null
        }
        val master = DeterministicWallet.generate(seed)
        val fingerprint = DeterministicWallet.publicKey(master).fingerprint() and 0xffffffffL
        return fingerprint.toString(16).padStart(8, '0')
    }
}
