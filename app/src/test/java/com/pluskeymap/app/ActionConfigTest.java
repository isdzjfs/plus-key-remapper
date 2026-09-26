package com.pluskeymap.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActionConfigTest {
    @Test public void onlyProtectedQuickDoorAutoOpenBypassesUnlockWait() {
        assertFalse(ActionConfig.shouldWaitForUnlock(ActionConfig.QUICKDOOR_AUTO_INTENT));
        assertTrue(ActionConfig.shouldWaitForUnlock(ActionConfig.QUICKDOOR_MAIN_INTENT));
        assertTrue(ActionConfig.shouldWaitForUnlock(
                "|com.sen.quickdoor|com.sen.quickdoor.OtherActivity|"));
        assertTrue(ActionConfig.shouldWaitForUnlock(null));
    }
}
