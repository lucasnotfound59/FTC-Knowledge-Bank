package org.ftckb.intellij

import com.intellij.openapi.project.Project

/** Project service that will own the SessionRuntime, the serial executor,
 * configuration state, and credential lookups (M3). */
class FtckbService(private val project:Project) {
    companion object {
        fun of(project:Project):FtckbService=project.getService(FtckbService::class.java)
    }
}
