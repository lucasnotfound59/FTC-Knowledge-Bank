"""Read-only project diagnostics. This contract is independent of kernel JSON."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from integration_contract import IntegrationError,normalize_profiles,versions
from project import CONFIG,SOURCE,SKILL,agents_block,digest,no_duplicate_keys,safe_path,source_versions

TIMEOUT=5


class ProbeError(Exception):
    pass


def executable(name,forbidden_root=None,java_home=None):
    suffix=".exe" if os.name=="nt" else ""
    candidate=str(Path(java_home)/"bin"/(name+suffix)) if java_home else shutil.which(name)
    if not candidate:
        raise ProbeError("tool-unavailable")
    try:
        path=Path(candidate).resolve(strict=True)
        if forbidden_root is not None and path.is_relative_to(Path(forbidden_root).resolve()):
            raise ProbeError("tool-unsafe")
        if not path.is_file() or not os.access(path,os.X_OK):
            raise ProbeError("tool-unavailable")
        return str(path)
    except OSError:
        raise ProbeError("tool-unavailable") from None


def launcher_path(source,windows=None):
    windows=os.name=="nt" if windows is None else windows
    return safe_path(source,"apps/knowledge-cli/build/install/ftckb/bin/"+("ftckb.bat" if windows else "ftckb"))


def git(root,*args,forbidden_root=None):
    # Only these internal, read-only operations are permitted. Never accept a
    # subcommand from configuration or feed Git a configured ref or URL.
    allowed={"rev-parse","ls-files","status","config"}
    if not args or args[0] not in allowed:
        raise ProbeError("git-operation")
    env={k:v for k,v in os.environ.items() if not k.startswith("GIT_")}
    env.update(GIT_OPTIONAL_LOCKS="0",GIT_TERMINAL_PROMPT="0",GIT_CONFIG_NOSYSTEM="1",
               GIT_CONFIG_GLOBAL=os.devnull,GIT_PAGER="cat",GIT_ALLOW_PROTOCOL="")
    tool=executable("git",root if forbidden_root is None else forbidden_root)
    try:
        result=subprocess.run([tool,"--no-optional-locks","-c","core.fsmonitor=false",
                               "-c","core.untrackedCache=false","-c","submodule.recurse=false",
                               "-c","core.hooksPath="+os.devnull,"-C",str(root),*args],
                              capture_output=True,text=True,encoding="utf-8",errors="replace",
                              timeout=TIMEOUT,env=env)
    except subprocess.TimeoutExpired:
        raise ProbeError("git-timeout") from None
    except OSError:
        raise ProbeError("git-unavailable") from None
    if result.returncode:
        raise ProbeError("git-query-failed")
    return result.stdout.strip()



def jdk_status(forbidden_root=None):
    """Query installed tools only; unresolved Gradle toolchains remain unknown."""
    env={k:v for k,v in os.environ.items() if k not in
         {"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","CLASSPATH"}}
    try:
        for tool in ("java","javac"):
            selected=executable(tool,forbidden_root,os.environ.get("JAVA_HOME") or None)
            result=subprocess.run([selected,"-version"],capture_output=True,text=True,
                                  timeout=TIMEOUT,env=env)
            text=result.stderr+result.stdout
            pattern=r'version "(?:1\.)?(\d+)' if tool=="java" else r'javac (?:1\.)?(\d+)'
            version=re.search(pattern,text)
            if result.returncode or not version or int(version[1])<21:
                return "unknown"
        return "passed"
    except (OSError,subprocess.TimeoutExpired,ProbeError):
        return "unknown"


def report():
    return {"doctorSchemaVersion":1,"command":"doctor","ok":False,"projectRoot":None,
            "context":{k:None for k in ("team","season","profiles","normalizedProfiles",
                       "protocol","commit","knowledgePath")},"checks":[],"nextSteps":[]}


def diagnose(project):
    out=report()
    states={}
    required=[]

    def add(code,status,message,blocked_by=(),mandatory=True):
        item={"code":code,"status":status,"message":message}
        if blocked_by:
            item["blockedBy"]=list(blocked_by)
        out["checks"].append(item); states[code]=status
        if mandatory:
            required.append(code)

    def probe(code,fn,deps=()):
        missing=[dep for dep in deps if states.get(dep)!="passed"]
        if missing:
            add(code,"blocked","Prerequisite checks must pass first.",missing)
            return None
        try:
            value=fn()
            add(code,"passed","Verified by read-only inspection.")
            return value
        except ProbeError as error:
            add(code,"failed",{"git-timeout":"Git query timed out.","git-unavailable":"Git is unavailable.","tool-unavailable":"A required tool is unavailable.","tool-unsafe":"Refusing a tool located inside the target project."}.get(str(error),"Read-only inspection found a problem."))
        except (OSError,ValueError,TypeError,IntegrationError):
            add(code,"failed","Read-only inspection found a problem.")
        return None

    def require(condition):
        if not condition:
            raise ProbeError("invalid")
        return condition

    def step(code,message,cwd=None,argv=None):
        item={"code":code,"message":message}
        if cwd is not None and argv is not None:
            item.update(cwd=str(cwd),argv=argv)
        out["nextSteps"].append(item)

    def root_check():
        root=Path(project).resolve(strict=True)
        require(root.is_dir())
        require(Path(git(root,"rev-parse","--show-toplevel")).resolve()==root)
        require(re.fullmatch(r"[0-9a-f]{40,64}",git(root,"rev-parse","--verify","HEAD")))
        return root

    root=probe("project.git-root",root_check)
    if root:
        out["projectRoot"]=str(root)
    config=None
    if not root:
        add("config.read","blocked","A valid Git root is required.",["project.git-root"])
    else:
        try:
            path=safe_path(root,CONFIG)
            if not path.exists():
                add("config.read","failed","Project configuration is missing.")
            else:
                config=json.loads(path.read_text(encoding="utf-8"),object_pairs_hook=no_duplicate_keys)
                require(isinstance(config,dict))
                add("config.read","passed","Project configuration is a JSON object (YAML 1.2 subset).")
        except (OSError,ValueError,IntegrationError,ProbeError):
            config=None
            add("config.read","failed","Project configuration is malformed or unsafe; use unique JSON object keys.")
    cfg=config or {}
    protocol=probe("config.protocol",lambda:versions(cfg),("config.read",))
    if protocol:
        out["context"]["protocol"]=protocol
    team=probe("config.team",lambda:cfg["team"] if require(isinstance(cfg.get("team"),str) and re.fullmatch(r"[1-9][0-9]*",cfg["team"])) else None,("config.read",))
    season=cfg.get("season")
    def season_check():
        require(isinstance(season,str) and re.fullmatch(r"[0-9]{4}-[0-9]{4}",season))
        a,b=map(int,season.split("-")); require(b==a+1)
        return season
    selected=probe("config.season",season_check,("config.read",))
    out["context"].update(team=team,season=selected)
    def profiles_check():
        if protocol["schemaVersion"]==1:
            require("profiles" not in cfg)
            return None
        return normalize_profiles(cfg.get("profiles"))
    normalized=probe("config.profiles",profiles_check,("config.protocol",))
    if states["config.profiles"]=="passed" and protocol["schemaVersion"]==2:
        out["context"].update(profiles=cfg["profiles"],normalizedProfiles=normalized)
    source_cfg=cfg.get("source") if isinstance(cfg.get("source"),dict) else {}
    probe("config.source-path",lambda:require(source_cfg.get("path")==SOURCE),("config.read",))
    pin=probe("config.commit",lambda:source_cfg["commit"] if require(isinstance(source_cfg.get("commit"),str) and re.fullmatch(r"[0-9a-f]{40}",source_cfg["commit"])) else None,("config.read",))
    out["context"]["commit"]=pin
    for key in ("repository","ref"):
        probe("config."+key,lambda key=key:require(isinstance(source_cfg.get(key),str) and bool(source_cfg[key].strip())),("config.read",))
    def receipts_check():
        values=cfg.get("managedFiles")
        require(isinstance(values,dict) and set(values)=={SKILL,"AGENTS.md#ftckb"})
        require(all(isinstance(v,str) and re.fullmatch(r"[0-9a-f]{64}",v) for v in values.values()))
        return values
    receipts=probe("config.receipts",receipts_check,("config.read",))
    def metadata():
        safe_path(root,".gitmodules")
        entries=git(root,"config","--no-includes","--file",".gitmodules","--get-regexp",r"^submodule\..*\.path$")
        matches=[line.rsplit(" ",1)[0][:-5] for line in entries.splitlines() if line.endswith(" "+SOURCE)]
        require(len(matches)==1)
        require(git(root,"config","--no-includes","--file",".gitmodules","--get",matches[0]+".url")==source_cfg["repository"])
        return True
    probe("source.metadata",metadata,("project.git-root","config.source-path","config.repository"))
    def gitlink():
        rows=git(root,"ls-files","--stage","--",SOURCE).splitlines()
        require(len(rows)==1 and rows[0].split()[:3]==["160000",pin,"0"])
    probe("source.gitlink",gitlink,("project.git-root","config.source-path","config.commit"))
    def initialized():
        source=safe_path(root,SOURCE)
        require((source/".git").exists() and not (source/".git").is_symlink())
        require(Path(git(source,"rev-parse","--show-toplevel",forbidden_root=root)).resolve()==source)
        return source
    source=probe("source.initialized",initialized,("project.git-root","config.source-path"))
    probe("source.pin",lambda:require(git(source,"rev-parse","HEAD",forbidden_root=root)==pin),("source.initialized","config.commit"))
    probe("source.clean",lambda:require(not git(source,"status","--porcelain","--untracked-files=all","--ignore-submodules=none",forbidden_root=root)),("source.initialized",))
    def knowledge():
        path=safe_path(source,"knowledge"); require(path.is_dir()); return path
    knowledge_path=probe("source.knowledge",knowledge,("source.initialized",))
    if knowledge_path:
        out["context"]["knowledgePath"]=str(knowledge_path)
    probe("source.protocol",lambda:require(source_versions(source)==protocol),("source.initialized","config.protocol"))
    for code,relative in (("managed.skill",SKILL),("managed.agents","AGENTS.md")):
        def managed(relative=relative):
            path=safe_path(root,relative); require(path.is_file()); data=path.read_bytes()
            if relative=="AGENTS.md":
                data=agents_block(data); require(data is not None)
            return data
        data=probe(code,managed,("project.git-root",))
        probe(code+"-digest",lambda data=data,relative=relative:require(digest(data)==receipts[SKILL if relative==SKILL else "AGENTS.md#ftckb"]),(code,"config.receipts"))
    add("environment.python","passed" if sys.version_info>=(3,10) else "failed","Python 3.10 or newer is required.")
    probe("environment.git",lambda:executable("git",root))
    add("environment.jsonschema","passed" if importlib.util.find_spec("jsonschema") else "failed","jsonschema is required for subsequent kernel response validation.")
    if root is None:
        add("environment.jdk","blocked","A verified target root is required before executing tool probes.",["project.git-root"])
    else:
        add("environment.jdk",jdk_status(root),"JDK 21+ is needed for builds; an unresolved local toolchain may require Gradle provisioning.")
    def distribution():
        launcher=launcher_path(source)
        lib=safe_path(source,"apps/knowledge-cli/build/install/ftckb/lib")
        require(launcher.is_file() and lib.is_dir())
        main_jar=safe_path(source,"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar")
        jars=list(lib.glob("*.jar"))
        require(main_jar.is_file() and jars and all(p.is_file() and not p.is_symlink() for p in jars))
        return launcher
    launcher=probe("cli.distribution",distribution,("source.initialized",))
    def receipt():
        stamp=safe_path(source,"apps/knowledge-cli/build/ftckb-integration-commit")
        require(stamp.is_file()); require(stamp.read_text(encoding="utf-8").strip()==pin)
    probe("cli.receipt",receipt,("cli.distribution","config.commit"))
    add("cli.provenance","unknown","Static files and a matching receipt cannot prove build provenance or runtime behavior.",mandatory=False)
    trusted_source=("source.metadata","source.gitlink","source.pin","source.clean","source.protocol")
    add("source.dashboard","not_checked","Optional human Dashboard capability is not inspected by CLI diagnosis.",mandatory=False)
    for code in ("dashboard.connection","kernel.resolve","kernel.check","robot.validation"):
        add(code,"not_checked","This diagnostic does not execute or verify this stage.",mandatory=False)
    if states["config.read"]!="passed":
        step("configure","Review installation with explicit actual team, season and profile choice; missing configuration requires installation, malformed configuration requires repair.")
    elif any(states[k]!="passed" for k in states if k.startswith("config.")):
        step("repair-config","Repair the reported configuration fields; preserve the intended pin and explicit context.")
    if states["source.initialized"]!="passed" and states["source.metadata"]==states["source.gitlink"]=="passed":
        step("initialize","Initialize the verified pinned submodule.",root,["git","submodule","update","--init","--",SOURCE])
    if source and all(states[k]=="passed" for k in trusted_source) and (states["cli.distribution"]!="passed" or states["cli.receipt"]!="passed"):
        step("build","Review the pinned build/toolchain, then build explicitly; doctor has not run it.",source,["gradlew.bat" if os.name=="nt" else "sh"]+([] if os.name=="nt" else ["gradlew"])+[":apps:knowledge-cli:installDist"])
        step("verify-build","After building, run the pinned installer/verify CLI acceptance workflow to establish its build receipt; do not fabricate a receipt.")
    step("workflow","After resolving diagnostic issues: run work resolve, modify code, then work check with the returned receipt and report every soft item. Dashboard is optional for humans; hardware remains separate.")
    out["ok"]=all(states[k]=="passed" for k in required)
    return out


class Parser(argparse.ArgumentParser):
    def error(self,message):
        raise ProbeError("usage")


def main(argv=None):
    parser=Parser(description=__doc__)
    parser.add_argument("--project",required=True)
    try:
        args=parser.parse_args(argv)
    except ProbeError:
        out=report(); out["error"]={"code":"usage","message":"Expected doctor --project ROOT with no other options."}
        print(json.dumps(out,ensure_ascii=False)); return 64
    try:
        out=diagnose(Path(args.project))
    except (OSError,ValueError,IntegrationError,ProbeError):
        out=report(); out["checks"]=[{"code":"doctor.inspection","status":"failed","message":"Read-only inspection could not complete safely."}]
    print(json.dumps(out,ensure_ascii=False))
    return 0 if out["ok"] else 1
