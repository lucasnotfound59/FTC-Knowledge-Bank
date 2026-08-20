package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.ftckb.agent.AgentMode
import org.ftckb.agent.AskResult
import org.ftckb.agent.CitationValidationException
import org.ftckb.agent.EditResult
import org.ftckb.agent.HistoryAppliedResult
import org.ftckb.agent.RejectedResult
import org.ftckb.model.ModelProviderException
import org.ftckb.session.AskChatSessionException
import org.ftckb.session.SessionAssemblyException
import org.ftckb.session.SessionRuntime

/**
 * Single-session HTTP API for the local web session. All API endpoints require
 * the one-time token (query param `token` or header `X-FTCKB-Token`). Model calls
 * run on a single background thread so state mutations stay serialized.
 */
class ServeApi(
    private val runtime:SessionRuntime,
    private val token:String,
    private val indexHtml:()->String={ loadWebIndex() },
    private val agentThread:ExecutorService=Executors.newSingleThreadExecutor()
):HttpHandler {
    private val mapper=JsonMapper.builder().build()
    val shutdownLatch=CountDownLatch(1)

    fun shutdownAgentThread() {
        agentThread.shutdownNow()
    }

    override fun handle(exchange:HttpExchange) {
        try {
            val path=exchange.requestURI.path ?: "/"
            if (path=="/") {
                respond(exchange,200,"text/html; charset=utf-8",indexHtml())
                return
            }
            if (!path.startsWith("/api/")) {
                respond(exchange,404,jsonError("usage","unknown path"))
                return
            }
            if (!authorized(exchange)) {
                respond(exchange,401,jsonError("unauthorized","missing or invalid token"))
                return
            }
            if (path=="/api/shutdown") {
                respond(exchange,200,jsonOk().toString())
                shutdownLatch.countDown()
                return
            }
            val future=agentThread.submit<String> { dispatch(exchange,path) }
            respond(exchange,200,future.get())
        } catch (_:Throwable) {
            runCatching {
                respond(exchange,500,jsonError("internal","unexpected server error"))
            }
        }
    }

    private fun dispatch(exchange:HttpExchange,path:String):String {
        return when (path) {
            "/api/status" -> status()
            "/api/ask" -> ask(readBody(exchange))
            "/api/submit" -> submit(readBody(exchange))
            "/api/mode" -> mode(readBody(exchange))
            "/api/undo" -> historyCommand(undo=true)
            "/api/discard" -> historyCommand(undo=false)
            "/api/diff" -> diff()
            "/api/save" -> save(readBody(exchange))
            "/api/clear" -> clear()
            "/api/configure" -> configure(readBody(exchange))
            else -> jsonError("usage","unknown path")
        }
    }

    private fun status():String {
        val session=runtime.session()
        val chatStatus=session.status()
        val root=jsonOk()
        root.put("mode",runtime.currentMode().name.lowercase())
        root.put("repository",chatStatus.repository.toString())
        root.put("team",chatStatus.team)
        root.put("season",chatStatus.season)
        root.put("provider",chatStatus.provider)
        root.put("model",chatStatus.model)
        root.put("hasChanges",runtime.hasEditChanges())
        root.put("apiKeySet",true)
        return root.toString()
    }

    private fun ask(body:String):String {
        val node=parseBody(body) ?: return jsonError("usage","invalid JSON body")
        val question=node["question"]?.asText()?.trim().orEmpty()
        if (question.isEmpty()) return jsonError("usage","question must not be blank")
        return try {
            answerJson(runtime.session().ask(question))
        } catch (_:ModelProviderException) {
            jsonError("provider","model provider error: request failed")
        } catch (_:CitationValidationException) {
            jsonError("citation","citation validation error: response citations are invalid")
        } catch (_:AskChatSessionException.RepositoryRead) {
            jsonError("repository","repository error: local repository is unavailable")
        } catch (_:AskChatSessionException.KnowledgeRead) {
            jsonError("knowledge","knowledge error: local knowledge is unavailable")
        }
    }

    private fun submit(body:String):String {
        val node=parseBody(body) ?: return jsonError("usage","invalid JSON body")
        val message=node["message"]?.asText()?.trim().orEmpty()
        if (message.isEmpty()) return jsonError("usage","message must not be blank")
        return try {
            when (val result=runtime.controller().submit(message)) {
                is AskResult -> answerJson(result.answer)
                is EditResult -> editJson(result.report)
                is RejectedResult -> jsonError("refused",result.message)
            }
        } catch (_:ModelProviderException) {
            jsonError("provider","model provider error: request failed")
        } catch (_:Exception) {
            jsonError("edit","edit error: request did not complete")
        }
    }

    private fun mode(body:String):String {
        val node=parseBody(body) ?: return jsonError("usage","invalid JSON body")
        val requested=node["mode"]?.asText()
        val target=when (requested) {
            "ask" -> AgentMode.ASK
            "edit" -> AgentMode.EDIT
            else -> return jsonError("usage","mode must be ask or edit")
        }
        val controller=runtime.controller()
        return try {
            val rejection=controller.setMode(target)
            if (rejection!=null) {
                jsonError("refused",rejection.message)
            } else {
                val root=jsonOk()
                root.put("mode",target.name.lowercase())
                root.toString()
            }
        } catch (_:Exception) {
            runCatching { controller.setMode(AgentMode.ASK) }
            jsonError("refused","Edit requires a readable Git worktree with a named current branch")
        }
    }

    private fun historyCommand(undo:Boolean):String {
        val controller=runtime.controller()
        return try {
            when (val result=if (undo) controller.undo() else controller.discard()) {
                is RejectedResult -> jsonError("refused",result.message)
                is HistoryAppliedResult -> {
                    val root=jsonOk()
                    root.put("succeeded",result.result.succeeded)
                    root.put("conflicts",result.result.conflicts.size>0)
                    putArray(root,"changedPaths",result.result.changedPaths.sorted())
                    putArray(root,"conflictPaths",result.result.conflicts.sorted())
                    putArray(root,"warnings",result.warnings)
                    root.toString()
                }
            }
        } catch (_:Exception) {
            jsonError("edit","${if (undo) "undo" else "discard"} error: request did not complete")
        }
    }

    private fun diff():String {
        val root=jsonOk()
        root.put("diff",try { runtime.controller().diff() } catch (_:Exception) { "" })
        return root.toString()
    }

    private fun save(body:String):String {
        val node=parseBody(body) ?: return jsonError("usage","invalid JSON body")
        val pathText=node["path"]?.asText()?.takeIf(String::isNotBlank)
        return try {
            val saved=runtime.session().save(pathText?.let(java.nio.file.Path::of))
            val root=jsonOk()
            root.put("savedPath",saved.toString())
            root.toString()
        } catch (_:Exception) {
            jsonError("save","save error: unable to save session")
        }
    }

    private fun clear():String {
        runtime.clearConversation()
        val root=jsonOk()
        root.put("mode",runtime.currentMode().name.lowercase())
        return root.toString()
    }

    private fun configure(body:String):String {
        val node=parseBody(body) ?: return jsonError("usage","invalid JSON body")
        val team=node["team"]?.asText()?.takeIf(String::isNotBlank)
        val season=node["season"]?.asText()?.takeIf(String::isNotBlank)
        val repo=node["repo"]?.asText()?.takeIf(String::isNotBlank)
        val knowledge=node["knowledge"]?.asText()?.takeIf(String::isNotBlank)
        val provider=node["provider"]?.asText()?.takeIf(String::isNotBlank)
        if ((repo!=null || knowledge!=null) &&
            (runtime.currentMode()==AgentMode.EDIT || runtime.hasEditChanges())
        ) {
            return jsonError("refused","configure refused: Edit mode with outstanding Agent changes")
        }
        if (team!=null && !org.ftckb.domain.RuleIdentity.isCanonicalTeam(team)) {
            return jsonError("usage","invalid value for team: expected digits only")
        }
        if (season!=null && !org.ftckb.domain.RuleIdentity.isCanonicalSeason(season)) {
            return jsonError("usage","invalid value for season: expected YYYY-YYYY")
        }
        return try {
            if (provider!=null) runtime.reconfigureProvider(provider)
            if (team!=null || season!=null || knowledge!=null) {
                runtime.reconfigureKnowledge(
                    knowledge?.let(java.nio.file.Path::of) ?: runtime.currentKnowledgeRoot(),
                    team ?: runtime.team,
                    season ?: runtime.season
                )
            }
            if (repo!=null) runtime.reconfigureRepository(java.nio.file.Path.of(repo))
            status()
        } catch (failure:SessionAssemblyException) {
            jsonError("configure",failure.message ?: "configure failed")
        }
    }

    private fun answerJson(answer:org.ftckb.agent.AgentAnswer):String {
        val root=jsonOk()
        val claims=root.putArray("claims")
        answer.claims.forEach { claim ->
            claims.addObject().apply {
                put("kind",claim.kind.name)
                put("text",claim.text)
                putArray("citations").apply { claim.citations.forEach { add(it) } }
            }
        }
        answer.usage?.let { usage ->
            root.putObject("usage").apply {
                usage.inputTokens?.let { put("inputTokens",it) }
                usage.outputTokens?.let { put("outputTokens",it) }
            }
        }
        return root.toString()
    }

    private fun editJson(report:org.ftckb.agent.edit.EditReport):String {
        val root=jsonOk()
        root.put("summary",report.summary)
        putArray(root,"changedPaths",report.changedPaths.sorted())
        putArray(root,"reasons",report.reasons)
        putArray(root,"citations",report.citations.sorted())
        putArray(root,"warnings",report.warnings)
        putArray(root,"projectLevelPaths",report.projectLevelPaths.sorted())
        root.put("diff",report.diff)
        return root.toString()
    }

    private fun authorized(exchange:HttpExchange):Boolean {
        val query=exchange.requestURI.rawQuery
        val queryToken=query?.split("&")
            ?.mapNotNull { part -> part.split("=",limit=2).takeIf { it.size==2 && it[0]=="token" }?.get(1) }
            ?.firstOrNull()
        val headerToken=exchange.requestHeaders.getFirst("X-FTCKB-Token")
        val supplied=queryToken ?: headerToken ?: return false
        return constantTimeEquals(supplied,token)
    }

    private fun constantTimeEquals(supplied:String,expected:String):Boolean {
        val a=supplied.toByteArray(StandardCharsets.UTF_8)
        val b=expected.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(a,b)
    }

    private fun readBody(exchange:HttpExchange):String=
        exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    private fun parseBody(body:String):com.fasterxml.jackson.databind.JsonNode?=
        try { mapper.readTree(body) } catch (_:Exception) { null }

    private fun jsonOk()=mapper.createObjectNode().apply { put("ok",true) }

    private fun jsonError(code:String,message:String):String {
        val root=mapper.createObjectNode()
        root.put("ok",false)
        root.putObject("error").apply {
            put("code",code)
            put("message",message)
        }
        return root.toString()
    }

    private fun putArray(root:com.fasterxml.jackson.databind.node.ObjectNode,field:String,values:Collection<String>) {
        root.putArray(field).apply { values.forEach { add(it) } }
    }

    private fun respond(exchange:HttpExchange,status:Int,body:String) {
        val bytes=body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type","application/json; charset=utf-8")
        exchange.sendResponseHeaders(status,bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respond(exchange:HttpExchange,status:Int,contentType:String,body:String) {
        val bytes=body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type",contentType)
        exchange.sendResponseHeaders(status,bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private fun loadWebIndex():String {
            val stream=ServeApi::class.java.getResourceAsStream("/web/index.html")
                ?:return "<html><body>web/index.html missing from resources</body></html>"
            return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        }
    }
}
