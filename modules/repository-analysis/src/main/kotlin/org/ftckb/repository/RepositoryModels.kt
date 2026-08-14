package org.ftckb.repository

import java.nio.file.Path
import java.util.Collections

enum class ProjectMarkerKind { GRADLE_SETTINGS,TEAMCODE_MODULE,FTC_DEPENDENCY,OPMODE_ANNOTATION }

data class ProjectMarker(val kind:ProjectMarkerKind,val path:String,val detail:String)

class FtcProjectProfile(
    val supported:Boolean,
    sourceModules:Set<String>,
    markers:List<ProjectMarker>
) {
    val sourceModules:Set<String> =immutableSetCopy(sourceModules)
    val markers:List<ProjectMarker> =immutableListCopy(markers)

    operator fun component1():Boolean=supported

    operator fun component2():Set<String> =sourceModules

    operator fun component3():List<ProjectMarker> =markers

    fun copy(
        supported:Boolean=this.supported,
        sourceModules:Set<String> =this.sourceModules,
        markers:List<ProjectMarker> =this.markers
    ):FtcProjectProfile=FtcProjectProfile(supported,sourceModules,markers)

    override fun equals(other:Any?):Boolean=other is FtcProjectProfile &&
        supported==other.supported && sourceModules==other.sourceModules && markers==other.markers

    override fun hashCode():Int=31*(31*supported.hashCode()+sourceModules.hashCode())+markers.hashCode()

    override fun toString():String="FtcProjectProfile(supported=$supported, sourceModules=$sourceModules, markers=$markers)"
}

data class IndexedDocument(
    val path:String,
    val sha256:String,
    val text:String,
    val lines:List<String>,
    val terms:Set<String>
)

class RepositorySnapshot(
    val root:Path,
    val profile:FtcProjectProfile,
    documents:Map<String,IndexedDocument>
) {
    val documents:Map<String,IndexedDocument> =immutableMapCopy(documents)

    operator fun component1():Path=root

    operator fun component2():FtcProjectProfile=profile

    operator fun component3():Map<String,IndexedDocument> =documents

    fun copy(
        root:Path=this.root,
        profile:FtcProjectProfile=this.profile,
        documents:Map<String,IndexedDocument> =this.documents
    ):RepositorySnapshot=RepositorySnapshot(root,profile,documents)

    override fun equals(other:Any?):Boolean=other is RepositorySnapshot &&
        root==other.root && profile==other.profile && documents==other.documents

    override fun hashCode():Int=31*(31*root.hashCode()+profile.hashCode())+documents.hashCode()

    override fun toString():String="RepositorySnapshot(root=$root, profile=$profile, documents=$documents)"
}

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

internal fun <T> immutableListCopy(values:Collection<T>):List<T> =Collections.unmodifiableList(ArrayList(values))

internal fun <T> immutableSetCopy(values:Collection<T>):Set<T> =Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <K,V> immutableMapCopy(values:Map<K,V>):Map<K,V> =Collections.unmodifiableMap(LinkedHashMap(values))
