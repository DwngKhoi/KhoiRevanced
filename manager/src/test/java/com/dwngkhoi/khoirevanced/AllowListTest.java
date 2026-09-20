package com.dwngkhoi.khoirevanced;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AllowListTest {
    @Test
    public void actionsIncludeScriptAndCoreOps() {
        assertTrue(RootRuntime.ACTIONS.contains("doctor"));
        assertTrue(RootRuntime.ACTIONS.contains("script"));
        assertTrue(RootRuntime.ACTIONS.contains("launch"));
        assertFalse(RootRuntime.ACTIONS.contains("rm"));
        assertFalse(RootRuntime.ACTIONS.contains("su"));
    }

    @Test
    public void scriptsAreAllowListedOnly() {
        assertTrue(RootRuntime.SCRIPTS.contains("hello"));
        assertTrue(RootRuntime.SCRIPTS.contains("device-info"));
        assertFalse(RootRuntime.SCRIPTS.contains("arbitrary"));
        assertFalse(RootRuntime.SCRIPTS.contains("../escape"));
    }

    @Test
    public void profilesStayDebuggableTemplateOnly() {
        assertTrue(RootRuntime.PROFILES.contains("example-debug"));
        assertFalse(RootRuntime.PROFILES.contains("youtube"));
    }
}
