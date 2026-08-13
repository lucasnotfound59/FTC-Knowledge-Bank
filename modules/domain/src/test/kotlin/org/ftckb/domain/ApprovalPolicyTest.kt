package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalPolicyTest {
    @Test
    fun `overall lead approves shared rule`() {
        assertTrue(ApprovalPolicy.authorize(RuleAuthority.SHARED,setOf(),Approver("lucas",ApproverRole.OVERALL_SOFTWARE_LEAD)))
    }

    @Test
    fun `matching team lead approves only own team rule`() {
        val lead=Approver("lead-20827",ApproverRole.TEAM_SOFTWARE_LEAD,"20827")
        assertTrue(ApprovalPolicy.authorize(RuleAuthority.TEAM,setOf("20827"),lead))
        assertFalse(ApprovalPolicy.authorize(RuleAuthority.TEAM,setOf("16093"),lead))
        assertFalse(ApprovalPolicy.authorize(RuleAuthority.SHARED,setOf(),lead))
    }
}
