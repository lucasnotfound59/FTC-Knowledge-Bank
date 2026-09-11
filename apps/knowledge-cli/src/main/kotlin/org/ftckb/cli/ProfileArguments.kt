package org.ftckb.cli

import org.ftckb.domain.RuleProfiles

data class ProfileSelection(val remaining:List<String>,val profiles:Set<String>?) {
    fun requiredProfiles():Set<String> =RuleProfiles.normalize(profiles)
}

object ProfileArguments {
    fun extract(args:List<String>):ProfileSelection {
        val remaining=mutableListOf<String>()
        val profiles=linkedSetOf<String>()
        var generic=false
        var index=0
        while (index<args.size) when (val value=args[index++]) {
            "--generic-profile" -> {
                require(!generic) { "duplicate --generic-profile" }
                generic=true
            }
            "--profile" -> {
                require(index<args.size && args[index].isNotBlank() && !args[index].startsWith("--")) {
                    "--profile requires a value"
                }
                profiles+=args[index++]
            }
            else -> remaining+=value
        }
        require(!generic || profiles.isEmpty()) { "--profile and --generic-profile are mutually exclusive" }
        return ProfileSelection(remaining,if (generic || profiles.isNotEmpty()) profiles else null)
    }
}
