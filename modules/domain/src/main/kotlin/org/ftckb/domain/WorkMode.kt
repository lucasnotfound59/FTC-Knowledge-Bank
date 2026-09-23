package org.ftckb.domain

/**
 * Ephemeral, per-invocation work mode. It is never stored in project configuration,
 * knowledge rules or the resolver context of another invocation: callers must obtain it
 * from an explicit user designation, never from file names, paths or dependencies.
 */
enum class WorkMode(val id:String) {
    NORMAL("normal"),
    TEST("test"),
    DEV("dev");

    companion object {
        /** Reason recorded in excludedRules when TEST exempts an architecture-mandate rule. */
        const val TEST_EXCLUSION_REASON="work-mode-test"

        /**
         * Exactly the rules that force formal command architecture. TEST keeps every other
         * rule (command safety, live input, requirements cleanup and test layout/JUnit).
         */
        val ARCHITECTURE_MANDATE_RULE_IDS=setOf(
            "global.command-responsibilities",
            "shared.ftclib-command-candidate"
        )

        fun parse(value:String):WorkMode?=entries.firstOrNull { it.id==value }
    }
}
