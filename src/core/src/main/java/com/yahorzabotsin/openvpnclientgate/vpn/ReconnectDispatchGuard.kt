package com.yahorzabotsin.openvpnclientgate.vpn

import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the reconnect-dispatch state that used to be two separately-declared fields on
 * [OpenVpnService] -- `connectionAttemptGeneration` and `reconnectDispatchPendingGeneration` --
 * plus the ~14 call sites across that file that read or wrote them directly. Introduced by
 * ClickUp [86cb5y61z](https://app.clickup.com/t/86cb5y61z), filed by the quality gate for
 * [86cb35fbt](https://app.clickup.com/t/86cb35fbt) (finding QG8-3) after nine review rounds
 * (R7-1, R9-1, R14-1, R16-1, R18-1, R19-1, R20-1, R21-1, plus two bot-found variants) each
 * discovered a different reachable interleaving of the same two implicit flags. The state is now
 * represented as one explicit [State] with unit-testable transitions instead.
 *
 * ## What this guards against
 *
 * `OpenVpnService` defers the ENGINE-facing part of a reconnect (`startIcsOpenVpn()`, which
 * ultimately calls `Context.startForegroundService()` against the *engine's own* service) by
 * `ENGINE_RECONNECT_DISPATCH_BUFFER_MS` after a reconnect `ACTION_START`, to give the just-stopped
 * previous engine process more real time to tear down before the next foreground-service
 * obligation is armed (the original crash this whole subsystem exists to prevent). During that
 * buffer window no new engine process has been asked to start yet, so *any* engine level received
 * right now is necessarily stray output from the just-stopped previous engine. Forwarding it to
 * `ServerAutoSwitcher` would let it skip the newly selected server without the new engine ever
 * trying it -- the recurring defect shape across every fix cycle in this family.
 *
 * ## Why one class instead of two fields
 *
 * The two values are a *pair* that must be read and written together for one logical connection
 * attempt, but they used to be independent fields updated by separate statements. Every one of
 * R18-1/R19-1/R20-1/R21-1 was a different way for that pair to observe a torn (inconsistent)
 * snapshot across a thread boundary -- a capture-time-only check, a non-atomic `+= 1` racing a
 * binder-thread writer, and a compound `.get()` pair racing a bump between the two reads. Wrapping
 * both values in one class with methods that always operate on the SAME captured generation (never
 * two independent live reads for what must be one logical attempt) makes the R21-1 class of bug a
 * type error to reintroduce, not merely a convention to remember.
 *
 * ## The LEVEL_VPNPAUSED decoupling exception (QG8-1)
 *
 * [armPending] is called only from a reconnect `ACTION_START`, and [State.BUFFER_PENDING] is
 * therefore always released by one of three paired bump sites: a fresh `ACTION_START`, the
 * `preserveReconnect ACTION_STOP` branch, or `finishStopFlowConfirmed()` -- all three bump
 * [beginNewAttempt] in the same code path that clears `OpenVpnService.userInitiatedStop`, so the
 * two stay coupled by construction at those three sites.
 *
 * `OpenVpnService.startUserStopTeardown()` is a FOURTH site that removes the still-pending
 * deferred Runnable (via `reconnectEngineDispatchToken`) WITHOUT calling [beginNewAttempt] --
 * intentionally, since a user-initiated stop should not itself count as a superseding "attempt".
 * This leaves [state] latched at [State.BUFFER_PENDING] for the live generation even though the
 * Runnable that would eventually call [clearPendingIfOwnedBy] has been swept away and will never
 * run. On its own this is benign: `startUserStopTeardown()` sets `userInitiatedStop = true` in the
 * same block, and `OpenVpnService.shouldIgnoreLevelAfterUserStop()` independently discards any
 * level reaching `updateState()`/`syncEngineState()` while that flag is set -- so the latch changes
 * no outcome as long as `userInitiatedStop` stays true.
 *
 * It does NOT stay true unconditionally, and this is the one path where the "benign because
 * userInitiatedStop is true throughout" argument needs its exception named explicitly: a stale
 * `LEVEL_VPNPAUSED` from the just-stopped engine, arriving after the teardown sweep, clears
 * `OpenVpnService.ignoreConnectedUntilNotConnected` as a side effect of being ignored itself
 * (`shouldIgnoreLevelAfterUserStop()`'s `LEVEL_VPNPAUSED` branch) -- without bumping this guard's
 * generation. If a stale `LEVEL_CONNECTED` then arrives from the same just-stopped engine, it is no
 * longer suppressed by that now-cleared flag, reaches `updateState()`'s `LEVEL_CONNECTED` branch,
 * and clears `userInitiatedStop` back to `false` -- again with NO paired [beginNewAttempt] call.
 * From that point the latch has no independent justification: [state] stays
 * [State.BUFFER_PENDING] and every subsequent AIDL/VpnStatus level is suppressed, silently, until
 * the next real attempt (`ACTION_START`) or confirmed stop (`finishStopFlowConfirmed()`) bumps the
 * generation again.
 *
 * This requires a `PAUSED` -> `CONNECTED` pair emitted by an engine that has already been told to
 * stop, arriving while a stop teardown is in flight -- not a normal engine sequence. The worst
 * outcome is a bounded, self-healing missed auto-switch (the latch cannot outlive the next
 * generation bump), never a stuck foreground notification (governed by the independent
 * `userInitiatedStart` flag) or a crash. See gate-8's QG8-1
 * (`docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-8.md`) for the full trace.
 *
 * ## The enqueue-point window (QG8-4) -- CLOSED, not merely narrowed
 *
 * Gate-8 also flagged the gap between `OpenVpnService`'s generation bump and this guard's marker
 * being armed for that same generation: at the time of that gate, ~34 lines of synchronous
 * `SharedPreferences` I/O sat between the two statements, and a stray level landing in that window
 * captured the already-bumped generation while the marker still held the previous one -- evading
 * suppression. Fix-cycles 18-22 (R18-1, R19-1, R20-1, R21-1; see
 * `docs/guides/troubleshooting.md`'s "Addendum (fix-cycles 18-22)") closed this CLASS of window
 * structurally rather than by repeatedly narrowing one instance's position:
 *
 * - [isBufferPendingForCurrentGeneration] is re-evaluated a SECOND time, at execution time, inside
 *   the deferred Runnable immediately before it would forward a level to `ServerAutoSwitcher`
 *   (R19-1). By execution time the marker has necessarily been armed for the live generation if an
 *   attempt is genuinely in flight, so a stray level that slipped past the capture-time check is
 *   still caught here.
 * - [beginNewAttempt] returns the new generation from one atomic `incrementAndGet()`
 *   (`AtomicInteger`, not a plain `@Volatile Int` -- R20-1, since one of the three bump call sites
 *   runs on the AIDL binder thread via `finishStopFlowConfirmed()`, and `+= 1` is not atomic under
 *   a genuine cross-thread race).
 * - Callers MUST thread that single returned value through to both [armPending] and their own
 *   locally-captured `dispatchGeneration` -- never re-read [currentGeneration] a second time for
 *   what must be the same logical attempt (R21-1: two independent `.get()` calls can observe
 *   different values if a binder-thread bump lands between them, even though each individual call
 *   is atomic).
 *
 * `OpenVpnService`'s own enqueue call site now arms the marker immediately after the generation
 * bump (separated only by the mandatory `config.isNullOrBlank() -> stopSelf()` early return, which
 * intentionally sits BEFORE the arm -- arming before that check would latch the marker with no
 * buffer ever pending on the early-return path, a worse, permanently-latching defect gate-9 proved
 * by mutation; see `reconnectStartWithBlankConfig_doesNotLatchDispatchMarkerToNewGeneration` in
 * `OpenVpnServiceReconnectEngineDispatchTest.kt`). Between the bump and the arm there is no longer
 * any interceptable window at all on that path, and the execution-time re-check above independently
 * closes the class for any window that could exist anywhere else in the sequence. Quality gate 11
 * (`docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-11.md`) assessed this specific
 * mechanism closed at high confidence (~85%) -- moderate confidence (~60%) only on the broader,
 * unbounded claim that no interleaving anywhere in the six-guard family (including the
 * `ServerAutoSwitcher`-side guards this class does not own) can defeat suppression. That residual
 * risk is accepted, not chased further here: quality gate 11 independently verified the outcome is
 * bounded to an availability/UX risk (a skipped or delayed auto-switch) in every case, never a
 * false-safe "connected" state, because `ConnectionState.CONNECTED` has exactly two direct writers
 * outside the engine's own state-update path and both require an actual engine `LEVEL_CONNECTED`.
 */
internal class ReconnectDispatchGuard {

    enum class State {
        /** No reconnect engine-dispatch buffer is pending for the current attempt generation. */
        IDLE,

        /**
         * A reconnect engine-dispatch buffer is pending for the CURRENT attempt generation -- no
         * new engine process has been asked to start yet, so any level observed right now is
         * necessarily stray output from the just-stopped previous engine and must be suppressed
         * before it reaches `ServerAutoSwitcher`.
         */
        BUFFER_PENDING
    }

    private val attemptGeneration = AtomicInteger(0)

    @Volatile
    private var pendingGeneration: Int = NO_PENDING_GENERATION

    /** The live attempt generation: monotonically increasing, never reset or decremented. */
    val currentGeneration: Int
        get() = attemptGeneration.get()

    /**
     * [State.BUFFER_PENDING] iff a buffer is armed for the generation live AT THE MOMENT OF THIS
     * READ. Always a fresh comparison against the current [currentGeneration], never cached --
     * callers that need to check this twice for the same stray level (once at capture time, once
     * at execution time; see [isBufferPendingForCurrentGeneration]) must call it twice, not reuse
     * an earlier result, or the execution-time re-check (R19-1) that closes the enqueue-point
     * window class loses its value.
     */
    val state: State
        get() = if (pendingGeneration == attemptGeneration.get()) State.BUFFER_PENDING else State.IDLE

    /**
     * Begins a new connection attempt (a fresh `ACTION_START`, a reconnect retry, or a stop that
     * supersedes an in-flight attempt) and returns the new generation. Every call implicitly
     * supersedes whatever this guard was previously tracking: any Runnable holding an older
     * captured generation will find `currentGeneration != itsCapturedGeneration` from this point
     * on. Callers that also need to [armPending] for this SAME attempt must reuse the single
     * returned value -- see this class's KDoc, "The enqueue-point window (QG8-4)", for why a
     * second independent [currentGeneration] read is unsafe here (R21-1).
     */
    fun beginNewAttempt(): Int = attemptGeneration.incrementAndGet()

    /**
     * Arms the pending marker for [generation], moving into [State.BUFFER_PENDING] for that
     * generation. [generation] must be the exact value [beginNewAttempt] returned for this
     * attempt.
     */
    fun armPending(generation: Int) {
        pendingGeneration = generation
    }

    /**
     * Clears the pending marker, but ONLY if it still belongs to [generation] -- i.e. only this
     * Runnable's own buffer, never a newer buffer's still-live marker. Fixes R16-1: an earlier,
     * superseded buffer's Runnable resolving first must not be able to disable suppression for a
     * still-pending newer buffer by clearing a marker it does not own.
     */
    fun clearPendingIfOwnedBy(generation: Int) {
        if (pendingGeneration == generation) {
            pendingGeneration = NO_PENDING_GENERATION
        }
    }

    /**
     * True while a reconnect engine-dispatch buffer is pending for the generation live AT THE
     * MOMENT OF THIS CALL -- equivalent to `state == State.BUFFER_PENDING`, provided as a named
     * predicate for call sites that only need the boolean. Deliberately NOT memoized: this is
     * meant to be called twice per stray level (once when the level is captured, once again at
     * execution time inside the deferred Runnable) so the two calls can observe different answers
     * if a bump landed in between -- that is what closes the enqueue-point window class (R19-1).
     */
    fun isBufferPendingForCurrentGeneration(): Boolean = state == State.BUFFER_PENDING

    private companion object {
        private const val NO_PENDING_GENERATION = -1
    }
}
