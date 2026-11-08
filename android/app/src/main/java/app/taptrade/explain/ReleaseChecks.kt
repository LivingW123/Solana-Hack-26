package app.taptrade.explain

import app.taptrade.data.formatUnits
import app.taptrade.data.shortAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One explainer rule result. These are what actually protect the buyer; the LLM only writes a sentence. */
data class Check(val rule: String, val ok: Boolean, val detail: String)

/** What the buyer's phone knows independently of the tapped URL and of the backend's transaction. */
data class ReleaseContext(
    val programId: String,
    /** Our backend (build config): the only host allowed to build the release. */
    val trustedBackendUrl: String,
    /** The buyer's connected wallet. */
    val me: String,
    /** The listing the buyer opened. */
    val listing: String,
    val listingTitle: String,
    /** Base units, as shown to the buyer. */
    val listingPrice: Long,
    val listingEscrow: String?,
    /** From the tapped `solana:` URL. */
    val link: String?,
    val urlEscrow: String?,
)

/** Fetched straight from RPC by the phone. */
data class ChainState(val escrowOwner: String?, val escrowData: ByteArray?, val vaultBalance: Long?)

object ReleaseChecks {
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"

    /** Priority-fee ceiling a release may set (micro-lamports per compute unit). */
    const val MAX_CU_PRICE = 10_000L

    /** Stage 1, before our address is sent anywhere: is the tapped link ours and for this listing? */
    fun checkLink(ctx: ReleaseContext): List<Check> {
        val host = ctx.link?.let(::authority)
        val trusted = authority(ctx.trustedBackendUrl)
        return listOf(
            Check(
                "Link comes from TapTrade",
                ctx.link != null && host == trusted,
                when {
                    ctx.link == null -> "Not a Solana Pay transaction request"
                    host == trusted -> "Host $host"
                    else -> "Host $host is not TapTrade ($trusted)"
                },
            ),
            Check(
                "Tap is for this listing's escrow",
                ctx.urlEscrow != null && ctx.urlEscrow == ctx.listingEscrow,
                if (ctx.urlEscrow == ctx.listingEscrow) "Escrow ${short(ctx.urlEscrow)}"
                else "URL escrow ${short(ctx.urlEscrow)} ≠ listing escrow ${short(ctx.listingEscrow)}",
            ),
        )
    }

    /** Stage 2: decode the unsigned transaction and compare it with the Escrow fetched from RPC. */
    fun checkTx(ctx: ReleaseContext, txBytes: ByteArray, chain: ChainState): List<Check> {
        val tx = try {
            TxDecoder.decode(txBytes)
        } catch (e: TxDecodeException) {
            return listOf(Check("Transaction can be read", false, e.message ?: "Malformed transaction"))
        }
        val checks = mutableListOf<Check>()
        if (tx.usesLookupTables) {
            checks += Check("Transaction can be read", false, "Uses address lookup tables; accounts can't be verified")
        }

        // --- the on-chain escrow (our source of truth)
        val escrow = chain.escrowData?.let(EscrowAccount::decode)
        val escrowOk = chain.escrowOwner == ctx.programId && escrow != null && escrow.listing == ctx.listing
        checks += Check(
            "Escrow is live on Solana",
            escrowOk,
            when {
                chain.escrowData == null -> "Escrow account not found (already settled?)"
                chain.escrowOwner != ctx.programId -> "Account isn't owned by the TapTrade program"
                escrow == null -> "Account isn't a TapTrade escrow"
                escrow.listing != ctx.listing -> "Escrow belongs to a different listing"
                else -> "Holds ${formatUnits(escrow.amount)} tUSDC for this listing"
            },
        )
        checks += Check(
            "You funded this escrow",
            escrow != null && escrow.buyer == ctx.me,
            if (escrow == null) "Escrow unavailable" else "Buyer on-chain: ${short(escrow.buyer)}",
        )

        // --- signer / fee payer
        checks += Check(
            "Only your wallet signs",
            tx.numRequiredSignatures == 1 && tx.feePayer == ctx.me,
            "${tx.numRequiredSignatures} signer(s); fee payer ${short(tx.feePayer)}",
        )

        // --- programs: TapTrade only (+ a capped compute-budget tweak). No direct token/SOL moves.
        val unexpected = tx.instructions.mapNotNull { ix ->
            when (ix.programId) {
                ctx.programId -> null
                COMPUTE_BUDGET -> computeBudgetProblem(ix)
                else -> describeForeign(ix)
            }
        }
        checks += Check(
            "Calls only the TapTrade program",
            unexpected.isEmpty(),
            if (unexpected.isEmpty()) "${tx.instructions.size} instruction(s), no token approvals or transfers"
            else unexpected.joinToString("; "),
        )

        // --- exactly one TapTrade instruction, and it's `release`
        val ours = tx.instructions.filter { it.programId == ctx.programId }
        val release = ours.singleOrNull()?.takeIf { it.data.contentEquals(Anchor.RELEASE_IX) }
        checks += Check(
            "Action is release",
            release != null,
            when {
                ours.isEmpty() -> "No TapTrade instruction"
                ours.size > 1 -> "${ours.size} TapTrade instructions"
                else -> if (release != null) "release()" else "Not a release instruction"
            },
        )

        // --- accounts of the release match the on-chain escrow (IDL order)
        val mismatches = mutableListOf<String>()
        if (release == null || escrow == null) {
            mismatches += "nothing to compare"
        } else {
            val a = release.accounts
            val expected = listOf(
                "buyer" to escrow.buyer, "seller" to escrow.seller, "listing" to escrow.listing,
                "escrow" to ctx.listingEscrow, "mint" to escrow.mint, "vault" to escrow.vault,
            )
            if (a.size != 12) mismatches += "${a.size} accounts (expected 12)"
            expected.forEachIndexed { i, (name, want) -> if (a.getOrNull(i) != want) mismatches += "$name ${short(a.getOrNull(i))} ≠ ${short(want)}" }
            listOf(9 to TOKEN_PROGRAM, 10 to ATA_PROGRAM, 11 to SYSTEM_PROGRAM).forEach { (i, want) ->
                if (a.getOrNull(i) != want) mismatches += "account #$i is ${short(a.getOrNull(i))}"
            }
        }
        checks += Check(
            "Escrow, vault, seller and mint match the chain",
            mismatches.isEmpty(),
            if (mismatches.isEmpty()) "Pays seller ${short(escrow?.seller)} from vault ${short(escrow?.vault)}" else mismatches.joinToString("; "),
        )

        // --- amount
        val amountOk = escrow != null && escrow.amount == ctx.listingPrice && chain.vaultBalance == escrow.amount
        checks += Check(
            "Amount is ${formatUnits(ctx.listingPrice)} tUSDC",
            amountOk,
            when {
                escrow == null -> "Escrow unavailable"
                escrow.amount != ctx.listingPrice -> "Escrow holds ${formatUnits(escrow.amount)}, listing says ${formatUnits(ctx.listingPrice)}"
                chain.vaultBalance != escrow.amount -> "Vault holds ${chain.vaultBalance?.let(::formatUnits) ?: "?"}, escrow says ${formatUnits(escrow.amount)}"
                else -> "Escrow and vault both hold ${formatUnits(escrow.amount)} tUSDC"
            },
        )
        return checks
    }

    /** Plain sentence from verified facts; shown when the LLM is unavailable. */
    fun fallbackSummary(ctx: ReleaseContext, chain: ChainState): String {
        val e = chain.escrowData?.let(EscrowAccount::decode)
        val amount = e?.amount ?: ctx.listingPrice
        return "Releases ${formatUnits(amount)} tUSDC to ${short(e?.seller)} for \"${ctx.listingTitle}\"."
    }

    private fun computeBudgetProblem(ix: DecodedIx): String? {
        // SetComputeUnitPrice = tag 3 + u64 micro-lamports; SetComputeUnitLimit = tag 2 (harmless).
        if (ix.data.isNotEmpty() && ix.data[0].toInt() == 3 && ix.data.size >= 9) {
            val price = ByteBuffer.wrap(ix.data, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (price > MAX_CU_PRICE) return "Compute Budget: priority fee $price µlamports/CU is too high"
        }
        return null
    }

    private fun describeForeign(ix: DecodedIx): String {
        val tag = ix.data.firstOrNull()?.toInt()?.and(0xff)
        return when (ix.programId) {
            TOKEN_PROGRAM, TOKEN_2022 -> "SPL Token: " + when (tag) {
                3, 12 -> "Transfer (moves tokens directly)"
                4, 13 -> "Approve (lets someone else spend your tokens)"
                6 -> "SetAuthority (changes who controls a token account)"
                9 -> "CloseAccount"
                else -> "instruction #$tag"
            }
            SYSTEM_PROGRAM -> if (tag == 2) "System: SOL transfer" else "System program instruction"
            ATA_PROGRAM -> "Associated Token program instruction"
            else -> "Unknown program ${short(ix.programId)}"
        }
    }

    private fun authority(url: String): String? = runCatching { URI(url).rawAuthority }.getOrNull()

    private fun short(a: String?): String = a?.let(::shortAddress) ?: "?"
}
