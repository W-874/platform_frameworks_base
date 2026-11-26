/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import static com.android.server.axdragonite.AxKernelManager.HEAVY;
import static com.android.server.axdragonite.AxKernelManager.LIGHT;
import static com.android.server.axdragonite.AxKernelManager.NONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.RemoteException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AxPowerVoteTest {
    private static final String HEAVY_HINT = "AX_DRAGONITE_HEAVY";
    private static final String LIGHT_HINT = "AX_DRAGONITE_LIGHT";
    private final List<Call> mCalls = new ArrayList<>();
    private long mNow = 1000;
    private final AxPowerVote mVote = new AxPowerVote(() -> mNow);
    private final AxPowerVote.Boost mBoost = (hint, duration) -> mCalls.add(new Call(hint, duration));

    private record Call(String hint, int duration) {}

    @Test
    public void shorterSameStrengthLeaseCancelsBeforeReissuing() throws RemoteException {
        mVote.apply(mBoost, HEAVY, 1500, false);
        mNow = 1100;
        mVote.apply(mBoost, HEAVY, 1300, false);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(HEAVY_HINT, -1),
                new Call(HEAVY_HINT, 200)), mCalls);
        assertEquals(1300, mVote.until());
    }

    @Test
    public void extensionAndDuplicateDoNotCancel() throws RemoteException {
        mVote.apply(mBoost, LIGHT, 1300, false);
        mNow = 1100;
        mVote.apply(mBoost, LIGHT, 1300, false);
        mVote.apply(mBoost, LIGHT, 1500, false);
        assertEquals(List.of(new Call(LIGHT_HINT, 300), new Call(LIGHT_HINT, 400)), mCalls);
    }

    @Test
    public void downgradeStartsLightBeforeCancelAndFinalReleaseStopsVote() throws RemoteException {
        mVote.apply(mBoost, HEAVY, 1500, false);
        mNow = 1150;
        mVote.apply(mBoost, LIGHT, 1350, false);
        mVote.apply(mBoost, NONE, 0, false);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(LIGHT_HINT, 200),
                new Call(HEAVY_HINT, -1), new Call(LIGHT_HINT, -1)), mCalls);
        assertEquals(NONE, mVote.level());
        assertEquals(0, mVote.until());
    }

    @Test
    public void expiredLeaseIsNeverReplayed() throws RemoteException {
        mVote.apply(mBoost, HEAVY, 1200, false);
        mNow = 1200;
        mVote.apply(mBoost, HEAVY, 1200, true);
        assertEquals(List.of(new Call(HEAVY_HINT, 200), new Call(HEAVY_HINT, -1)), mCalls);
    }

    @Test
    public void leaseCanExpireDuringShorteningCancel() throws RemoteException {
        mVote.apply(mBoost, HEAVY, 1500, false);
        mNow = 1199;
        mVote.apply((hint, duration) -> {
            mBoost.setBoost(hint, duration);
            mNow = 1200;
        }, HEAVY, 1200, false);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(HEAVY_HINT, -1)), mCalls);
        assertEquals(NONE, mVote.level());
    }

    @Test
    public void remoteFailureDoesNotForgetVoteThatMustBeCancelled() throws RemoteException {
        mVote.apply(mBoost, HEAVY, 1500, false);
        try {
            mVote.apply((hint, duration) -> { throw new RemoteException(); }, NONE, 0, false);
            fail("Expected RemoteException");
        } catch (RemoteException expected) {
            assertEquals(HEAVY, mVote.level());
        }
        mVote.apply(mBoost, NONE, 0, true);
        assertEquals(List.of(new Call(HEAVY_HINT, 500), new Call(HEAVY_HINT, -1)), mCalls);
    }
}
