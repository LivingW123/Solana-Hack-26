package app.taptrade.tap

/**
 * Real NFC tap (seller = Host Card Emulation, buyer = reader mode).
 * Out of scope for the hackathon: no hardware, emulators only.
 */
class NfcTapTransport : TapTransport {
    override fun publish(url: String) {
        TODO("NFC HCE: serve `url` as an NDEF URI record")
    }

    override fun listen(onTap: (url: String) -> Unit) {
        TODO("NFC reader mode: read the NDEF URI record and call onTap")
    }
}
