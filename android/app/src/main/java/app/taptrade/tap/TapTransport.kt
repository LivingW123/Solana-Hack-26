package app.taptrade.tap

/**
 * How a Solana Pay URL gets from the seller's phone to the buyer's phone at the
 * handoff. The app depends only on this interface, never on NFC directly, so
 * debug builds can swap in a fake tap (no hardware needed).
 */
interface TapTransport {
    /** Seller side: offer `url` to the next device that taps. */
    fun publish(url: String)

    /** Buyer side: `onTap` is called with the URL when a tap arrives. Replaces any previous listener. */
    fun listen(onTap: (url: String) -> Unit)

    /** Buyer side: the screen that registered `onTap` went away (no-op if a newer listener replaced it). */
    fun stopListening(onTap: (url: String) -> Unit) {}
}
