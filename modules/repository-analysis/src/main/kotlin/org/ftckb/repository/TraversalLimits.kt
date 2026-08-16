package org.ftckb.repository

class RepositoryTraversalLimits(
    val maxFiles:Int=20_000,
    val maxTotalBytes:Long=256L*1_048_576L,
    val maxDepth:Int=64
) {
    init {
        require(maxFiles>0) { "maxFiles must be positive" }
        require(maxTotalBytes>0) { "maxTotalBytes must be positive" }
        require(maxDepth>=0) { "maxDepth must not be negative" }
    }
}

class RepositoryTraversalException(message:String):RuntimeException(message)

class RepositoryAccessException:RuntimeException("repository files are unavailable")
