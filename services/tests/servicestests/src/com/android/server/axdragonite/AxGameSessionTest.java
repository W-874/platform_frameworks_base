/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AxGameSessionTest {
    private static final String GAME = "example.game";
    private static final int UID = 10001;
    private static final int PID = 42;
    private static final long START = 100;
    private final AxGameSession mSession = new AxGameSession();

    @Test
    public void placementOutlivesLoadingButRequiresEveryAdmissionGate() {
        update(true, true, true);
        mSession.loading(GAME, UID, PID, START, true, 640);
        assertEquals(640, mSession.loadingUntil(639));
        mSession.loading(GAME, UID, PID, START, true, 900);
        assertEquals(0, mSession.loadingUntil(640));
        assertNotNull(mSession.game());

        update(true, false, true);
        assertNull(mSession.game());
        update(true, true, true);
        update(true, true, false);
        assertNull(mSession.game());
        update(true, true, true);
        update(false, true, true);
        assertNull(mSession.game());
    }

    @Test
    public void loadingRejectsWrongPackageProcessAndReusedPid() {
        update(true, true, true);
        mSession.loading("shared.uid.sibling", UID, PID, START, true, 640);
        mSession.loading(GAME, UID + 1, PID, START, true, 640);
        mSession.loading(GAME, UID, PID + 1, START, true, 640);
        mSession.loading(GAME, UID, PID, START + 1, true, 640);
        assertEquals(0, mSession.loadingUntil(0));
        mSession.loading(GAME, UID, PID, START, true, 640);
        mSession.loading(GAME, UID, PID, START, false, 641);
        assertEquals(0, mSession.loadingUntil(1));
        mSession.loading(GAME, UID, PID, START, true, 900);
        mSession.update(GAME, UID, PID, START + 1, true, true, true);
        assertEquals(0, mSession.loadingUntil(2));
        mSession.clear();
        assertNull(mSession.game());
    }

    @Test
    public void isolationYieldsWithoutExtendingItsOriginalDeadline() {
        assertEquals(400, AxGameSession.topAppIsolationUntil(400, false, false));
        assertEquals(0, AxGameSession.topAppIsolationUntil(400, true, false));
        assertEquals(0, AxGameSession.topAppIsolationUntil(400, false, true));
        assertEquals(400, AxGameSession.topAppIsolationUntil(400, false, false));
    }

    private void update(boolean currentUser, boolean topUid, boolean performance) {
        mSession.update(GAME, UID, PID, START, currentUser, topUid, performance);
    }
}
