package org.ftckb.repository

import java.nio.file.Path

enum class ProjectMarkerKind { GRADLE_SETTINGS,TEAMCODE_MODULE,FTC_DEPENDENCY,OPMODE_ANNOTATION }

data class ProjectMarker(val kind:ProjectMarkerKind,val path:String,val detail:String)

data class FtcProjectProfile(
    val supported:Boolean,
    val sourceModules:Set<String>,
    val markers:List<ProjectMarker>
)

data class IndexedDocument(
    val path:String,
    val sha256:String,
    val text:String,
    val lines:List<String>,
    val terms:Set<String>
)

data class RepositorySnapshot(
    val root:Path,
    val profile:FtcProjectProfile,
    val documents:Map<String,IndexedDocument>
)

data class LocalQuery(
    val terms:Set<String>,
    val symbols:Set<String> =emptySet(),
    val pathGlobs:Set<String> =emptySet()
)

data class SourceFragment(
    val path:String,
    val startLine:Int,
    val endLine:Int,
    val sha256:String,
    val text:String,
    val score:Int
)
