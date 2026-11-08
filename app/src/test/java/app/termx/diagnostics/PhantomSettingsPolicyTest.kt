package app.termx.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomSettingsPolicyTest {
    @Test fun onlyAllowsDocumentedPhantomSettingValues() {
        assertTrue(PhantomSettingsUserService.isAllowedWrite(PhantomSettingsUserService.MONITOR_KEY, "false"))
        assertTrue(PhantomSettingsUserService.isAllowedWrite(PhantomSettingsUserService.MONITOR_KEY, "true"))
        assertTrue(PhantomSettingsUserService.isAllowedWrite(PhantomSettingsUserService.PROCESS_LIMIT_KEY, "2147483647"))
        assertTrue(PhantomSettingsUserService.isAllowedWrite(PhantomSettingsUserService.PROCESS_LIMIT_KEY, "100"))
        assertFalse(PhantomSettingsUserService.isAllowedWrite("other_global_setting", "false"))
        assertFalse(PhantomSettingsUserService.isAllowedWrite(PhantomSettingsUserService.MONITOR_KEY, "false; reboot"))
        assertFalse(PhantomSettingsUserService.isAllowedWrite(PhantomSettingsUserService.PROCESS_LIMIT_KEY, "0"))
        assertFalse(PhantomSettingsUserService.isAllowedWrite(PhantomSettingsUserService.PROCESS_LIMIT_KEY, "999999999999"))
    }
}
