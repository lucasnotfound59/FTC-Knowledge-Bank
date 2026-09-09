package org.ftckb.domain

import java.util.Collections

class RuleContextException(val code:String,message:String):IllegalArgumentException(message)

object RuleProfiles {
    val supported:Set<String> =setOf("rookiebot","simple-opmode","ftclib-command")

    fun normalize(profiles:Set<String>?):Set<String> {
        if (profiles==null) throw RuleContextException("context-required","Select a project profile or explicitly select generic")
        val unknown=profiles-supported
        if (unknown.isNotEmpty()) throw RuleContextException("invalid-context","Unknown profiles: ${unknown.sorted().joinToString()}")
        val normalized=profiles.toSortedSet()
        if ("rookiebot" in normalized) normalized+="simple-opmode"
        if (setOf("simple-opmode","ftclib-command").all { it in normalized }) {
            throw RuleContextException("invalid-context","simple-opmode and ftclib-command are mutually exclusive")
        }
        return Collections.unmodifiableSet(normalized)
    }
}
