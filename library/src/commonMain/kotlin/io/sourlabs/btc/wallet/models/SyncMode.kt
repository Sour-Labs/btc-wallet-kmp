package io.sourlabs.btc.wallet.models

/**
 * Controls what happens when a [io.sourlabs.btc.wallet.api.BitcoinKit] is started.
 */
sealed interface SyncMode {
    /**
     * Run one full sync, then stop. No polling loop.
     *
     * Useful for background refreshes of wallets that are not currently in the
     * foreground (e.g. aggregating balances across all wallets at app launch).
     */
    data object OneShot : SyncMode

    /**
     * Run one full sync, then poll incrementally. This is the historical default
     * behavior and is appropriate for the wallet the user is actively looking at.
     */
    data object Continuous : SyncMode

    /**
     * Skip the initial full sync; poll incrementally only.
     *
     * The caller is asserting that local storage is already up to date (e.g. the
     * wallet was synced less than a few minutes ago in the same process or
     * against persistent storage). `syncState` flips to
     * [io.sourlabs.btc.wallet.models.SyncState.Synced] immediately so callers
     * can treat the kit as ready without waiting on a full scan.
     *
     * If polling later detects a new block, the library still falls back to a
     * full sync to catch up.
     *
     * **Precondition:** local [io.sourlabs.btc.wallet.storage.WalletStorage] has
     * been populated by a previous [Continuous] or [OneShot] sync. Calling this
     * with empty storage will leave the wallet with no keys, UTXOs, or
     * transactions until a new block arrives.
     */
    data object IncrementalOnly : SyncMode
}
