"""Explicit installation preparation and CLI acceptance; optional human connection verification."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

import dashboard_connection
from dashboard_connection import ConnectionProblem
import project
from integration_contract import IntegrationError,versions
from project_doctor import ProbeError,git,launcher_path

FILE_LIMIT=512
BYTE_LIMIT=256*1024*1024
MESSAGES={
    "usage":"Prepare explicitly, then use --accept with the returned --prepared-context-sha256. Optional human verification requires both --connection-file and --browser-confirmed after observing the page.",
    "setup_required":"The pinned CLI distribution is incomplete or its build receipt does not match; review and rerun preparation after repairing the build.",
    "preparation_changed":"Prepared inputs changed; review the changes and explicitly prepare again before accepting.",
    "connection_failed":"The selected existing Dashboard did not match; repair its connection or audit environment before accepting.",
    "integration_error":"Installation inputs or a kernel stage could not be verified; no complete acceptance can be claimed.",
    "build_failed":"The pinned CLI build failed; installed files are retained. Repair the toolchain/cache/network and rerun the same preparation.",
    "audit_incomplete":"A kernel stage reported unavailable audit storage; its actual kernel result is retained, but acceptance is incomplete."
}


class WorkflowError(Exception):
    def __init__(self,code):self.code=code


class Parser(argparse.ArgumentParser):
    def error(self,message):raise WorkflowError("usage")


def require(condition,code="setup_required"):
    if not condition:raise WorkflowError(code)


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode("utf-8")


def sha(data):return hashlib.sha256(data).hexdigest()


def envelope(command):
    return {"installationWorkflowSchemaVersion":1,"command":command,"ok":False,"stage":"parameters",
            "context":None,"preparedContextSha256":None,"kernelStatus":"not_performed",
            "acceptanceStatus":"not_performed","checks":{},"nextSteps":[],"error":None}


def failure(command,code,stage="preflight"):
    out=envelope(command);out.update(stage=stage,error={"code":code,"message":MESSAGES[code]})
    if command=="accept":out["installationOk"]=False
    return 64 if code=="usage" else 2,out


def planned(root,plan):
    out=envelope("prepare")
    out.update(ok=True,stage="planned",plan=plan,context={"projectRoot":str(root),"team":plan["team"],"season":plan["season"],"sourceCommit":plan["commit"]})
    out["nextSteps"]=[{"code":"prepare","description":"Review the plan and rerun the same explicit inputs without --dry-run; this output has no usable prepared digest."}]
    return 0,out


def preparation_snapshot(root):
    observed=dashboard_connection.capture(root)
    context=observed["context"];root=Path(context["projectRoot"])
    config=project.load_config(root);source=project.safe_path(root,project.SOURCE)
    gitdir=Path(git(root,"rev-parse","--absolute-git-dir")).resolve(strict=True)
    skill=project.safe_path(root,project.SKILL).read_bytes()
    block=project.agents_block(project.safe_path(root,"AGENTS.md").read_bytes())
    require(block is not None,"integration_error")
    distribution={};total=0;count=0
    def fingerprint(path,label):
        nonlocal total,count
        require(not path.is_symlink() and path.is_file())
        fd=os.open(path,os.O_RDONLY|getattr(os,"O_NOFOLLOW",0)|getattr(os,"O_NONBLOCK",0))
        try:
            info=os.fstat(fd);require(stat.S_ISREG(info.st_mode))
            count+=1;require(count<=FILE_LIMIT and info.st_size<=BYTE_LIMIT-total)
            checksum=hashlib.sha256()
            while True:
                chunk=os.read(fd,65536)
                if not chunk:break
                total+=len(chunk);require(total<=BYTE_LIMIT)
                checksum.update(chunk)
            distribution[label]=checksum.hexdigest()
        finally:os.close(fd)
    launcher=launcher_path(source)
    main=project.safe_path(source,"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar")
    stamp=project.safe_path(source,"apps/knowledge-cli/build/ftckb-integration-commit")
    require(main.is_file() and stamp.is_file())
    require(stamp.read_text(encoding="utf-8").strip()==config["source"]["commit"])
    fingerprint(launcher,"bin/"+launcher.name)
    fingerprint(stamp,"integration-build-receipt")
    lib=project.safe_path(source,"apps/knowledge-cli/build/install/ftckb/lib")
    def visit(directory):
        with os.scandir(directory) as iterator:entries=sorted(iterator,key=lambda entry:entry.name)
        for entry in entries:
            require(not entry.is_symlink())
            path=Path(entry.path)
            if entry.is_dir(follow_symlinks=False):visit(path)
            else:fingerprint(path,"lib/"+path.relative_to(lib).as_posix())
    visit(lib)
    payload={"version":1,"projectRoot":str(root),"gitDir":str(gitdir),"configSha256":observed["configSha256"],
             "protocol":versions(config),"team":config["team"],"season":config["season"],
             "profiles":config.get("profiles"),"source":{"repository":config["source"]["repository"],
                 "commit":config["source"]["commit"],"path":str(source),"gitlink":git(root,"ls-files","--stage","--",project.SOURCE)},
             "managedFiles":{project.SKILL:sha(skill),"AGENTS.md#ftckb":sha(block)},
             "knowledgePath":context["knowledgePath"],"knowledgeFiles":observed["fileDigests"],"distribution":distribution,
             "auditRoot":context["expectedAuditRoot"],"auditPartition":context["expectedAuditPartition"]}
    public=context|{"protocol":versions(config),"team":config["team"],"season":config["season"],"profiles":config.get("profiles")}
    return sha(canonical(payload)),public,config,source


def prepare(root):
    out=envelope("prepare");out["stage"]="installed"
    try:
        wiring=dashboard_connection.capture(root);root=Path(wiring["context"]["projectRoot"])
        config=project.load_config(root);source=project.safe_path(root,project.SOURCE)
        # Reject distribution path indirection before the explicit authorized build.
        launcher_path(source)
        for relative in ("apps/knowledge-cli/build/ftckb-integration-commit","apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar"):
            existing=project.safe_path(source,relative)
            require(not existing.exists() or existing.is_file())
        lib=project.safe_path(source,"apps/knowledge-cli/build/install/ftckb/lib")
        def existing_entries(directory):
            if not directory.exists():return
            require(directory.is_dir())
            with os.scandir(directory) as iterator:entries=list(iterator)
            for entry in entries:
                require(not entry.is_symlink())
                if entry.is_dir(follow_symlinks=False):existing_entries(Path(entry.path))
                else:require(entry.is_file(follow_symlinks=False))
        existing_entries(lib)
        out["stage"]="building"
        try:
            with tempfile.TemporaryFile(mode="w+") as private_log,contextlib.redirect_stderr(private_log):
                project.build_cli(source,config["source"]["commit"])
        except (IntegrationError,OSError,ValueError):raise WorkflowError("build_failed") from None
        digest,context,_,_=preparation_snapshot(root)
        out.update(ok=True,stage="prepared",context=context,preparedContextSha256=digest)
        env={"FTCKB_AUDIT_DIR":context["expectedAuditRoot"]}
        out["nextSteps"]=prepare_steps(root,source,context,digest,env)
        return 0,out
    except (WorkflowError,ConnectionProblem,IntegrationError,ProbeError,OSError,ValueError,TypeError,RecursionError) as error:
        code=error.code if isinstance(error,WorkflowError) else "integration_error"
        out["error"]={"code":code,"message":MESSAGES[code]}
        out["nextSteps"]=[{"code":"repair-preparation","description":MESSAGES[code]}]
        return 2,out


def prepare_steps(root,source,context,digest,env):
    entrypoint=str(Path(__file__).with_name("verify.py").resolve())
    return [{"code":"workflow-env","description":"Use this explicit audit environment for CLI acceptance and later rule work; humans may later view the same audit directory.","env":env},
            {"code":"accept","description":"Accept the prepared installation through the CLI. Dashboard is optional and is not started or queried.",
             "entrypoint":entrypoint,"preparedContextSha256":digest,"cwd":str(root),
             "argv":[sys.executable,entrypoint,"--project",str(root),"--accept","--prepared-context-sha256",digest],"env":env}]


def classify_diagnostics(text):
    text=text or "";items=[];audit="not_reported"
    if "ftckb audit: storage write failed; invocation audit is incomplete" in text or "ftckb audit: storage unavailable; invocation audit is incomplete" in text:
        items.append({"code":"audit-incomplete","message":"Kernel audit storage was reported unavailable; its audit is incomplete."});audit="incomplete"
    if "ftckb audit: checked input version unavailable" in text:
        items.append({"code":"version-unknown","message":"The checked input version was unavailable; this alone does not prove audit storage failed."})
    remaining=text
    for known in ("ftckb audit: storage write failed; invocation audit is incomplete","ftckb audit: storage unavailable; invocation audit is incomplete","ftckb audit: checked input version unavailable"):
        remaining=remaining.replace(known,"")
    if remaining.strip():items.append({"code":"diagnostic-unknown","message":"The kernel emitted another diagnostic; its raw text is not exposed."})
    if text.strip() and audit!="incomplete":audit="unknown"
    return {"auditStatus":audit,"items":items[:3],"truncated":len(text)>4096}


def accept(root,prepared_digest,connection_file=None,browser_confirmed=False):
    out=envelope("accept");out.update(installationOk=False,dashboardVerification="requested" if connection_file is not None or browser_confirmed else "not_requested",browserConfirmation="declared" if browser_confirmed else "not_declared",serviceBinaryPin="unknown",auditStatus="not_reported")
    try:
        require(isinstance(prepared_digest,str) and re.fullmatch(r"[0-9a-f]{64}",prepared_digest),"usage")
        require((connection_file is not None)==bool(browser_confirmed) and (connection_file is None or bool(connection_file)),"usage")
        out["stage"]="prepared-context"
        try:actual,context,config,source=preparation_snapshot(root)
        except (WorkflowError,ConnectionProblem,IntegrationError,ProbeError,OSError,ValueError,TypeError,RecursionError):raise WorkflowError("setup_required") from None
        out.update(context=context,preparedContextSha256=actual)
        require(actual==prepared_digest,"preparation_changed")
        if connection_file is not None:
            out["stage"]="connection"
            connection_code,connection_result=dashboard_connection.verify_connection(root,connection_file)
            out["connection"]=connection_result
            require(connection_code==0,"connection_failed")
            out["dashboardVerification"]="performed"
        out["acceptanceStatus"]="running"
        env={"FTCKB_AUDIT_DIR":context["expectedAuditRoot"]}
        for command in ("validate","resolve","check"):
            out["stage"]=command
            try:before,_,_,_=preparation_snapshot(root)
            except (WorkflowError,ConnectionProblem,IntegrationError,ProbeError,OSError,ValueError,TypeError,RecursionError):raise WorkflowError("preparation_changed") from None
            require(before==prepared_digest,"preparation_changed")
            stage={"attempted":True,"executed":False,"kernel":None,"diagnostics":classify_diagnostics("")}
            out["checks"][command]=stage
            def diagnostics(text):stage["diagnostics"]=classify_diagnostics(text)
            def observation(fact):
                stage["executed"]=fact["completed"]
                out["kernelStatus"]="partially_performed"
                stage["kernel"]={"exitCode":fact["exitCode"],"output":None,"validation":"unverified"}
            kernel_error=None
            try:
                code,payload=project.kernel(source,config,command,Path(context["projectRoot"]),env=env,diagnostic_sink=diagnostics,observation_sink=observation)
                stage["executed"]=True
                out["kernelStatus"]="partially_performed"
                stage["kernel"]={"exitCode":code,"output":payload}
            except (IntegrationError,OSError,ValueError,TypeError,RecursionError) as error:
                kernel_error=error
                stage["responseError"]="kernel-response-unverified" if stage["executed"] else "kernel-process-not-completed"
            if stage["diagnostics"]["auditStatus"]=="incomplete":out["auditStatus"]="incomplete"
            elif stage["diagnostics"]["auditStatus"]=="unknown" and out["auditStatus"]!="incomplete":out["auditStatus"]="unknown"
            try:after,_,_,_=preparation_snapshot(root)
            except (WorkflowError,ConnectionProblem,IntegrationError,ProbeError,OSError,ValueError,TypeError,RecursionError):raise WorkflowError("preparation_changed") from None
            require(after==prepared_digest,"preparation_changed")
            if kernel_error is not None:raise WorkflowError("integration_error") from None
            require(stage["diagnostics"]["auditStatus"]!="incomplete","audit_incomplete")
            require(code in ((0,1) if command=="check" else (0,)),"integration_error")
        checked=out["checks"]["check"]["kernel"]
        out.update(ok=checked["exitCode"]==0,stage="accepted",installationOk=True,kernelStatus="performed",acceptanceStatus="performed",
                   projectCheck={"ok":checked["output"]["ok"],"exitCode":checked["exitCode"],"violations":checked["output"]["violations"],"soft":checked["output"]["soft"]},robotValidation="not-performed")
        return checked["exitCode"],out
    except (WorkflowError,ConnectionProblem,IntegrationError,ProbeError,OSError,ValueError,TypeError,RecursionError) as error:
        code=error.code if isinstance(error,WorkflowError) else "integration_error"
        out["acceptanceStatus"]="incomplete" if out["checks"] else "not_performed"
        out["error"]={"code":code,"message":MESSAGES[code]}
        out["nextSteps"]=[{"code":"recover","description":MESSAGES[code]}]
        return 64 if code=="usage" else 2,out
