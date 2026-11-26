/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.os.RemoteException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AxPowerVoteTest {
    private static final String HEAVY_HINT = "AX_DRAGONITE_HEAVY";
    private static final String LIGHT_HINT = "AX_DRAGONITE_LIGHT";
    private static final String GPU_HINT = "AX_DRAGONITE_GPU_RENDERING";
    private final List<Call> mCalls = new ArrayList<>();
    private long mNow = 1000;
    private final AxPowerVote mVote = new AxPowerVote(() -> mNow);
    private final AxPowerVote.Boost mBoost = (hint, duration) -> mCalls.add(new Call(hint, duration));

    private record Call(String hint, int duration) {}

    @Test
    public void shorterSameStrengthLeaseCancelsBeforeReissuing() throws RemoteException {
        mVote.apply(mBoost, HEAVY_HINT, 1500, false);
        mNow = 1100;
        mVote.apply(mBoost, HEAVY_HINT, 1300, false);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(HEAVY_HINT, -1),
                new Call(HEAVY_HINT, 200)), mCalls);
        assertEquals(1300, mVote.until());
    }

    @Test
    public void extensionAndDuplicateDoNotCancel() throws RemoteException {
        mVote.apply(mBoost, LIGHT_HINT, 1300, false);
        mNow = 1100;
        mVote.apply(mBoost, LIGHT_HINT, 1300, false);
        mVote.apply(mBoost, LIGHT_HINT, 1500, false);
        assertEquals(List.of(new Call(LIGHT_HINT, 300), new Call(LIGHT_HINT, 400)), mCalls);
    }

    @Test
    public void downgradeStartsLightBeforeCancelAndFinalReleaseStopsVote() throws RemoteException {
        mVote.apply(mBoost, HEAVY_HINT, 1500, false);
        mNow = 1150;
        mVote.apply(mBoost, LIGHT_HINT, 1350, false);
        mVote.apply(mBoost, null, 0, false);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(LIGHT_HINT, 200),
                new Call(HEAVY_HINT, -1), new Call(LIGHT_HINT, -1)), mCalls);
        assertNull(mVote.hint());
        assertEquals(0, mVote.until());
    }

    @Test
    public void expiredLeaseIsNeverReplayed() throws RemoteException {
        mVote.apply(mBoost, HEAVY_HINT, 1200, false);
        mNow = 1200;
        mVote.apply(mBoost, HEAVY_HINT, 1200, true);
        assertEquals(List.of(new Call(HEAVY_HINT, 200), new Call(HEAVY_HINT, -1)), mCalls);
    }

    @Test
    public void leaseCanExpireDuringShorteningCancel() throws RemoteException {
        mVote.apply(mBoost, HEAVY_HINT, 1500, false);
        mNow = 1199;
        mVote.apply((hint, duration) -> {
            mBoost.setBoost(hint, duration);
            mNow = 1200;
        }, HEAVY_HINT, 1200, false);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(HEAVY_HINT, -1)), mCalls);
        assertNull(mVote.hint());
    }

    @Test
    public void remoteFailureDoesNotForgetVoteThatMustBeCancelled() throws RemoteException {
        mVote.apply(mBoost, HEAVY_HINT, 1500, false);
        try {
            mVote.apply((hint, duration) -> { throw new RemoteException(); }, null, 0, false);
            fail("Expected RemoteException");
        } catch (RemoteException expected) {
            assertEquals(HEAVY_HINT, mVote.hint());
        }
        mVote.apply(mBoost, null, 0, true);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(HEAVY_HINT, -1)), mCalls);
    }

    @Test
    public void renderingShortenAndExpiryLeaveCpuVoteAlone() throws RemoteException {
        AxPowerVote gpu = new AxPowerVote(() -> mNow);
        mVote.apply(mBoost, HEAVY_HINT, 1700, false);
        gpu.apply(mBoost, GPU_HINT, 1480, false);

        // A longer rendering lease ended, but another rendering lease remains.
        mNow = 1100;
        mVote.apply(mBoost, HEAVY_HINT, 1700, false);
        gpu.apply(mBoost, GPU_HINT, 1320, false);

        mNow = 1320;
        mVote.apply(mBoost, HEAVY_HINT, 1700, false);
        gpu.apply(mBoost, null, 0, false);
        assertEquals(List.of(new Call(HEAVY_HINT, 700), new Call(GPU_HINT, 480),
                new Call(GPU_HINT, -1), new Call(GPU_HINT, 220), new Call(GPU_HINT, -1)), mCalls);
        assertEquals(1700, mVote.until());
        assertNull(gpu.hint());
    }
}
