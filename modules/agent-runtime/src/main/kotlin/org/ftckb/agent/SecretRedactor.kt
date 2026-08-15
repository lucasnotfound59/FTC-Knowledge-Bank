package org.ftckb.agent

typealias RedactionResult=org.ftckb.model.RedactionResult

object SecretRedactor {
    fun redact(text:String,exactSecrets:Set<String> =emptySet()):RedactionResult=
        org.ftckb.model.SecretRedactor.redact(text,exactSecrets)
}
