package app.termx.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoctorProbeTest {
    @Test fun parsesRealUidFromProcStatus() {
        assertEquals(10234, DoctorProbe.parseUid("Name:\ttermx\nUid:\t10234\t10234\t10234\t10234\n"))
    }

    @Test fun missingOrMalformedUidIsUnavailable() {
        assertNull(DoctorProbe.parseUid("Name:\ttermx\n"))
        assertNull(DoctorProbe.parseUid("Uid:\tnot-a-number\n"))
    }
}
