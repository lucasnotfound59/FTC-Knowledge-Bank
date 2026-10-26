"""Read-only verification of one explicitly selected existing Dashboard API."""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import stat
import sys
import urllib.error
import urllib.request

import project
from integration_contract import IntegrationError,versions
from project_doctor import ProbeError,git

FILE_LIMIT=8192
RESPONSE_LIMIT=1024*1024
TIMEOUT=3
REQUIRED=("local.identity","connection.private-file","connection.authenticated","knowledge.root",
          "knowledge.files","knowledge.valid","audit.root","audit.partition","audit.health","local.stable")
MESSAGES={
    "usage":"Use dashboard verify --project ROOT --connection-file PRIVATE_JSON.",
    "integration-invalid":"Project identity could not be verified; run doctor and repair the reported integration first.",
    "private-file":"Use a current-user-owned private regular connection file with one url field; do not pass the token on the command line.",
    "privacy-unknown":"This platform cannot verify the required connection-file privacy boundary.",
    "invalid-url":"Use the exact existing http://127.0.0.1:PORT/ fragment-token link in the private file.",
    "unauthorized":"The selected service rejected the credential; reopen its current private link and refresh the private file.",
    "unsupported-api":"The selected service does not provide the supported connection identity API; explicitly choose a supported instance or review an upgrade.",
    "unreachable":"The selected existing service could not be reached within the limit; verify the chosen connection without scanning or starting another instance.",
    "redirect":"Dashboard identity redirects are forbidden; select the exact existing loopback service.",
    "invalid-response":"The identity response could not be safely verified.",
    "knowledge-mismatch":"The selected service does not match the configured valid knowledge path and complete input files.",
    "audit-mismatch":"The selected service does not match the expected audit root, partition and healthy storage; set FTCKB_AUDIT_DIR explicitly for the intended workflow.",
    "storage-unknown":"The selected service cannot report a reliable file audit-store identity.",
    "local-changed":"Local configuration or knowledge changed during verification; verify again after the inputs are stable."
}


class ConnectionProblem(Exception):
    def __init__(self,code,status="failed"):
        self.code=code;self.status=status


def require(condition,code="integration-invalid",status="failed"):
    if not condition:
        raise ConnectionProblem(code,status)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical_path(path):
    # Match FileAuditStore: normalize first, realpath the longest existing prefix,
    # then append the missing suffix. Never expand a literal '~' environment value.
    absolute=Path(os.path.abspath(str(path)))
    current=absolute; suffix=[]
    while not current.exists():
        require(current.parent!=current,"integration-invalid")
        suffix.append(current.name);current=current.parent
    current=current.resolve(strict=True)
    for name in reversed(suffix):
        current=current/name
    return current


def file_digests(root):
    require(root.is_dir() and not root.is_symlink())
    result={}
    def visit(directory):
        with os.scandir(directory) as iterator:
            entries=sorted(iterator,key=lambda entry:entry.name)
        for entry in entries:
            require(not entry.is_symlink())
            path=Path(entry.path)
            if entry.is_dir(follow_symlinks=False):
                visit(path)
            elif entry.is_file(follow_symlinks=False) and path.suffix in (".yaml",".yml"):
                relative=path.relative_to(root).as_posix().replace("\\","/")
                require(relative not in result)
                fd=os.open(path,os.O_RDONLY|getattr(os,"O_NOFOLLOW",0)|getattr(os,"O_NONBLOCK",0))
                try:
                    require(stat.S_ISREG(os.fstat(fd).st_mode))
                    checksum=hashlib.sha256()
                    while True:
                        chunk=os.read(fd,65536)
                        if not chunk: break
                        checksum.update(chunk)
                    result[relative]=checksum.hexdigest()
                finally:
                    os.close(fd)
            elif path.suffix in (".yaml",".yml"):
                raise ConnectionProblem("integration-invalid")
    visit(root)
    return result


def capture(value):
    """Validate project identity without work storage, JDK, build or kernel probes."""
    root=Path(value).resolve(strict=True)
    require(root.is_dir() and Path(git(root,"rev-parse","--show-toplevel")).resolve()==root)
    git(root,"rev-parse","--verify","HEAD")
    require(not git(root,"ls-files","--unmerged"))
    path=project.safe_path(root,project.CONFIG);require(path.is_file())
    raw=path.read_bytes();config=project.load_config(root);require(raw==path.read_bytes())
    protocol=versions(config);source_cfg=config["source"]
    project.safe_path(root,".gitmodules")
    rows=git(root,"config","--no-includes","--file",".gitmodules","--get-regexp",r"^submodule\..*\.path$")
    matches=[line.rsplit(" ",1)[0][:-5] for line in rows.splitlines() if line.endswith(" "+project.SOURCE)]
    require(len(matches)==1)
    require(git(root,"config","--no-includes","--file",".gitmodules","--get",matches[0]+".url")==source_cfg["repository"])
    entries=git(root,"ls-files","--stage","--",project.SOURCE).splitlines()
    require(len(entries)==1 and entries[0].split()[:3]==["160000",source_cfg["commit"],"0"])
    source=project.safe_path(root,project.SOURCE)
    require((source/".git").exists() and not (source/".git").is_symlink())
    require(Path(git(source,"rev-parse","--show-toplevel",forbidden_root=root)).resolve()==source)
    require(git(source,"rev-parse","HEAD",forbidden_root=root)==source_cfg["commit"])
    require(not git(source,"status","--porcelain","--untracked-files=all",forbidden_root=root))
    manifest=project.safe_path(source,".agents/skills/ftckb-integrate/assets/integration.json")
    require(not manifest.exists() or manifest.is_file())
    require(project.source_versions(source)==protocol)
    project.check_managed(root,config)
    knowledge=project.safe_path(source,"knowledge").resolve(strict=True)
    selected=os.environ.get("FTCKB_AUDIT_DIR")
    audit=Path(selected) if selected is not None and selected.strip() else Path.home()/".ftckb/audit"
    if not audit.is_absolute(): audit=root/audit
    audit=canonical_path(audit)
    context={"projectRoot":str(root),"sourceCommit":source_cfg["commit"],"knowledgePath":str(knowledge),
             "expectedAuditRoot":str(audit),"expectedAuditPartition":str(audit/sha(str(knowledge).encode("utf-8")))}
    return {"context":context,"configSha256":sha(raw),"fileDigests":file_digests(knowledge)}


def private_connection(value):
    if os.name!="posix" or not hasattr(os,"geteuid") or not hasattr(os,"O_NOFOLLOW"):
        raise ConnectionProblem("privacy-unknown","unknown")
    path=Path(os.path.abspath(str(value)))
    cursor=Path(path.anchor)
    for name in path.parts[1:]:
        cursor=cursor/name
        require(not cursor.is_symlink(),"private-file")
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|getattr(os,"O_NONBLOCK",0))
    try:
        info=os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid==os.geteuid() and not info.st_mode&0o077 and info.st_size<=FILE_LIMIT,"private-file")
        data=b""
        while len(data)<=FILE_LIMIT:
            chunk=os.read(fd,min(4096,FILE_LIMIT+1-len(data)))
            if not chunk: break
            data+=chunk
        require(len(data)<=FILE_LIMIT,"private-file")
    finally:
        os.close(fd)
    try:
        payload=json.loads(data.decode("utf-8"),object_pairs_hook=project.no_duplicate_keys)
    except (RecursionError,ValueError,IntegrationError):
        raise ConnectionProblem("private-file") from None
    require(isinstance(payload,dict) and set(payload)=={"url"} and isinstance(payload["url"],str),"private-file")
    match=re.fullmatch(r"http://127\.0\.0\.1:([1-9][0-9]{0,4})/#token=([A-Za-z0-9_-]{16,256})",payload["url"])
    require(match is not None,"invalid-url")
    port=int(match[1]);token=match[2];require(port<=65535,"invalid-url")
    try:
        base64.b64decode(token+"="*((-len(token))%4),altchars=b"-_",validate=True)
    except ValueError:
        raise ConnectionProblem("invalid-url") from None
    return "http://127.0.0.1:"+str(port),token


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None


def identity(origin,token):
    request=urllib.request.Request(origin+"/api/dashboard/identity",headers={"Authorization":"Bearer "+token},method="GET")
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    try:
        with opener.open(request,timeout=TIMEOUT) as response:
            length=response.headers.get("Content-Length")
            require(length is None or length.isdigit() and int(length)<=RESPONSE_LIMIT,"invalid-response")
            data=response.read(RESPONSE_LIMIT+1)
            require(len(data)<=RESPONSE_LIMIT,"invalid-response")
        result=json.loads(data.decode("utf-8"),object_pairs_hook=project.no_duplicate_keys)
    except urllib.error.HTTPError as error:
        code=error.code;error.close()
        if code==401: raise ConnectionProblem("unauthorized") from None
        if code in (404,405): raise ConnectionProblem("unsupported-api","unknown") from None
        if 300<=code<400: raise ConnectionProblem("redirect") from None
        raise ConnectionProblem("invalid-response","unknown") from None
    except (urllib.error.URLError,TimeoutError,socket.timeout,ConnectionError,OSError):
        raise ConnectionProblem("unreachable","unknown") from None
    except (http.client.HTTPException,RecursionError,ValueError,IntegrationError):
        raise ConnectionProblem("invalid-response","unknown") from None
    require(isinstance(result,dict) and result.get("apiVersion")==1 and type(result.get("apiVersion")) is int
            and result.get("identitySchemaVersion")==1 and type(result.get("identitySchemaVersion")) is int,
            "unsupported-api","unknown")
    require(result.get("ok") is True and isinstance(result.get("knowledge"),dict) and isinstance(result.get("audit"),dict),"unsupported-api","unknown")
    knowledge=result["knowledge"];audit=result["audit"]
    require({"root","version","fileDigests","valid","ruleCount"}<=set(knowledge),"unsupported-api","unknown")
    require({"kind","root","partition","health"}<=set(audit),"unsupported-api","unknown")
    require(isinstance(knowledge["root"],str) and Path(knowledge["root"]).is_absolute(),"invalid-response","unknown")
    require(isinstance(knowledge["version"],str) and re.fullmatch(r"[0-9a-f]{64}",knowledge["version"]),"invalid-response","unknown")
    require(type(knowledge["valid"]) is bool and type(knowledge["ruleCount"]) is int and knowledge["ruleCount"]>=0,"invalid-response","unknown")
    require(isinstance(knowledge["fileDigests"],dict),"invalid-response","unknown")
    for name,checksum in knowledge["fileDigests"].items():
        require(isinstance(name,str) and name and not name.startswith("/") and "\\" not in name
                and all(part not in ("",".","..") for part in name.split("/")) and Path(name).suffix in (".yaml",".yml")
                and isinstance(checksum,str) and re.fullmatch(r"[0-9a-f]{64}",checksum),"invalid-response","unknown")
    require(audit["kind"] in ("file","unknown"),"invalid-response","unknown")
    if audit["kind"]=="file":
        require(all(isinstance(audit[key],str) and Path(audit[key]).is_absolute() for key in ("root","partition")),"invalid-response","unknown")
    else:
        require(audit["root"] is None and audit["partition"] is None,"invalid-response","unknown")
    health=audit["health"]
    require(isinstance(health,dict) and type(health.get("ok")) is bool and isinstance(health.get("warnings"),list)
            and all(isinstance(item,str) for item in health["warnings"]),"invalid-response","unknown")
    return result


def report():
    return {"dashboardConnectionSchemaVersion":1,"command":"dashboard-verify","ok":False,
            "context":None,"server":None,"checks":[],"nextSteps":[],"error":None}


class Parser(argparse.ArgumentParser):
    def error(self,message):
        raise ConnectionProblem("usage")


def verify_connection(project_path,connection_file):
    args=argparse.Namespace(project=project_path,connection_file=connection_file)
    out=report();states={};first_error=None;before=None;connection=None;server=None
    def check(code,status,message,error=None):
        nonlocal first_error
        states[code]=status;out["checks"].append({"code":code,"status":status,"message":message})
        if error and first_error is None: first_error=error
    try:
        before=capture(args.project);out["context"]=before["context"]
        check("local.identity","passed","Configured project identity verified with read-only Git and file inspection.")
    except (ConnectionProblem,ProbeError,IntegrationError,OSError,RecursionError,ValueError,TypeError):
        check("local.identity","failed",MESSAGES["integration-invalid"],"integration-invalid")
    if before:
        try:
            connection=private_connection(args.connection_file)
            check("connection.private-file","passed","Private connection input and exact loopback URL verified.")
            out["server"]={"origin":connection[0],"version":None,"ruleCount":None}
        except ConnectionProblem as error:
            check("connection.private-file",error.status,MESSAGES[error.code],error.code)
        except (OSError,ValueError,TypeError):
            check("connection.private-file","failed",MESSAGES["private-file"],"private-file")
    if connection:
        try:
            server=identity(*connection)
            check("connection.authenticated","passed","Authenticated identity response verified.")
            out["server"].update(version=server["knowledge"]["version"],ruleCount=server["knowledge"]["ruleCount"])
        except ConnectionProblem as error:
            check("connection.authenticated",error.status,MESSAGES[error.code],error.code)
    if server:
        knowledge=server["knowledge"];audit=server["audit"];expected=before["context"]
        comparisons=(("knowledge.root",knowledge["root"]==expected["knowledgePath"]),
                     ("knowledge.files",knowledge["fileDigests"]==before["fileDigests"]),("knowledge.valid",knowledge["valid"]))
        for code,matched in comparisons:
            check(code,"passed" if matched else "failed","Knowledge identity matches." if matched else MESSAGES["knowledge-mismatch"],None if matched else "knowledge-mismatch")
        for code,key,local in (("audit.root","root","expectedAuditRoot"),("audit.partition","partition","expectedAuditPartition")):
            if audit["kind"]=="unknown":
                check(code,"unknown",MESSAGES["storage-unknown"],"storage-unknown")
            else:
                matched=audit[key]==expected[local]
                check(code,"passed" if matched else "failed","Actual audit-store identity matches." if matched else MESSAGES["audit-mismatch"],None if matched else "audit-mismatch")
        healthy=audit["health"]["ok"]
        check("audit.health","passed" if healthy else "failed","Audit storage health verified." if healthy else MESSAGES["audit-mismatch"],None if healthy else "audit-mismatch")
        try:
            stable=capture(args.project)==before
        except (ConnectionProblem,ProbeError,IntegrationError,OSError,RecursionError,ValueError,TypeError):
            stable=False
        check("local.stable","passed" if stable else "failed","Local inputs remained stable." if stable else MESSAGES["local-changed"],None if stable else "local-changed")
    for code in REQUIRED:
        if code not in states: check(code,"blocked","Prerequisite verification did not pass.")
    check("browser.connection","not_checked","Open the private service link and confirm the browser page is connected as required by AGENTS.")
    check("server.binaryPin","unknown","Authenticated API identity does not prove the service binary pin or build provenance.")
    check("kernel.defaultAudit","not_checked","Python home is an explicit expected root, not proof of an unconfigured kernel JVM user.home.")
    out["ok"]=all(states[code]=="passed" for code in REQUIRED)
    if out["ok"]:
        root=before["context"]["projectRoot"];env={"FTCKB_AUDIT_DIR":before["context"]["expectedAuditRoot"]}
        out["nextSteps"]=[{"code":"browser-confirm","description":"Confirm the browser page is connected to the selected instance before resolving rules."},
            {"code":"workflow-env","description":"Apply this explicit audit environment to the following resolve AND the later receipt-returned check argv.","env":env},
            {"code":"work-resolve","description":"After browser confirmation, use this reviewed wrapper; keep the same explicit audit environment for subsequent checks.",
             "cwd":root,"argv":[sys.executable,str(Path(__file__).with_name("project.py").resolve()),"work","resolve","--project",root],"env":env}]
    else:
        code=first_error or "integration-invalid"
        out["error"]={"code":code,"message":MESSAGES[code]}
        out["nextSteps"]=[{"code":"review-connection","description":MESSAGES[code]}]
    return (0 if out["ok"] else 1),out


def main(argv=None):
    out=report()
    parser=Parser(description=__doc__)
    parser.add_argument("command",choices=["verify"])
    parser.add_argument("--project",required=True)
    parser.add_argument("--connection-file",required=True)
    try:
        args=parser.parse_args(argv)
    except ConnectionProblem:
        out["error"]={"code":"usage","message":MESSAGES["usage"]};print(json.dumps(out,ensure_ascii=False));return 64
    code,payload=verify_connection(args.project,args.connection_file)
    print(json.dumps(payload,ensure_ascii=False));return code
