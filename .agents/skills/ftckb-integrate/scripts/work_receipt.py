"""Explicit local resolve/check association; independent of kernel and audit JSON."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import uuid

import project
from integration_contract import IntegrationError,normalize_profiles,versions
from project_doctor import ProbeError,git,launcher_path

LIMIT=65536
CONTEXT_KEYS={"projectRoot","gitDir","configSha256","protocol","team","season","profiles",
              "normalizedProfiles","sourceCommit","knowledgePath","knowledgeDigest","workMode"}


class WorkError(Exception):
    def __init__(self,code,fields=()):
        self.code=code
        self.fields=sorted(fields)


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode("utf-8")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition,code="integration-invalid"):
    if not condition:
        raise WorkError(code)


def valid_id(value):
    try:
        parsed=uuid.UUID(value)
        return parsed.version==4 and str(parsed)==value
    except (ValueError,TypeError,AttributeError):
        return False


def knowledge_digest(root):
    """Same lowercase yaml/yml input scope as FileKnowledgeRepository, including ignored files."""
    entries=[]
    require(root.is_dir() and not root.is_symlink(),"knowledge-unsafe")
    def visit(directory):
        with os.scandir(directory) as iterator:
            children=sorted(iterator,key=lambda entry:entry.name)
        for entry in children:
            path=Path(entry.path)
            require(not entry.is_symlink(),"knowledge-unsafe")
            if entry.is_dir(follow_symlinks=False):
                visit(path)
            elif entry.is_file(follow_symlinks=False):
                if path.suffix in (".yaml",".yml"):
                    entries.append([path.relative_to(root).as_posix(),sha(path.read_bytes())])
            else:
                raise WorkError("knowledge-unsafe")
    visit(root)
    return sha(canonical(entries))


def capture(project_path,mode):
    root=Path(project_path).resolve(strict=True)
    require(root.is_dir() and Path(git(root,"rev-parse","--show-toplevel")).resolve()==root)
    git(root,"rev-parse","--verify","HEAD")
    git_dir=Path(git(root,"rev-parse","--absolute-git-dir")).resolve(strict=True)
    require(git_dir.is_dir())
    require(not git_dir.is_relative_to(root) or git_dir.is_relative_to(root/".git"),"storage-unsafe")
    require(not git(root,"ls-files","--unmerged"))
    config_path=project.safe_path(root,project.CONFIG)
    require(config_path.is_file())
    raw=config_path.read_bytes()
    config=project.load_config(root)
    # Do not fingerprint bytes from a different configuration read.
    require(raw==config_path.read_bytes(),"context-changed")
    protocol=versions(config)
    source_cfg=config["source"]
    project.safe_path(root,".gitmodules")
    metadata=git(root,"config","--no-includes","--file",".gitmodules","--get-regexp",r"^submodule\..*\.path$")
    matches=[line.rsplit(" ",1)[0][:-5] for line in metadata.splitlines() if line.endswith(" "+project.SOURCE)]
    require(len(matches)==1)
    require(git(root,"config","--no-includes","--file",".gitmodules","--get",matches[0]+".url")==source_cfg["repository"])
    links=git(root,"ls-files","--stage","--",project.SOURCE).splitlines()
    require(len(links)==1 and links[0].split()[:3]==["160000",source_cfg["commit"],"0"])
    source=project.safe_path(root,project.SOURCE)
    require((source/".git").exists() and not (source/".git").is_symlink())
    require(Path(git(source,"rev-parse","--show-toplevel",forbidden_root=root)).resolve()==source)
    require(git(source,"rev-parse","HEAD",forbidden_root=root)==source_cfg["commit"])
    require(not git(source,"status","--porcelain","--untracked-files=all",forbidden_root=root))
    manifest=project.safe_path(source,".agents/skills/ftckb-integrate/assets/integration.json")
    require(not manifest.exists() or manifest.is_file())
    require(project.source_versions(source)==protocol)
    project.check_managed(root,config)
    schema=project.safe_path(source,"docs/kernel-contract.schema.json")
    require(schema.is_file())
    project.validator(source)
    knowledge=project.safe_path(source,"knowledge")
    profiles=config.get("profiles") if protocol["schemaVersion"]==2 else None
    context={"projectRoot":str(root),"gitDir":str(git_dir),"configSha256":sha(raw),"protocol":protocol,
             "team":config["team"],"season":config["season"],"profiles":profiles,
             "normalizedProfiles":normalize_profiles(profiles) if profiles is not None else None,
             "sourceCommit":source_cfg["commit"],"knowledgePath":str(knowledge.resolve(strict=True)),
             "knowledgeDigest":knowledge_digest(knowledge),"workMode":mode}
    return context,config,source


def setup_ready(source,commit):
    launcher=launcher_path(source)
    main=project.safe_path(source,"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar")
    stamp=project.safe_path(source,"apps/knowledge-cli/build/ftckb-integration-commit")
    require(launcher.is_file() and main.is_file() and stamp.is_file(),"setup-required")
    require(stamp.read_text(encoding="utf-8").strip()==commit,"setup-required")


class Store:
    """Only fixed children of the actual Git directory; no receipt-supplied paths."""
    def __init__(self,git_dir,create=False):
        self.base=Path(git_dir)
        self.create=create
        self.fd=None
        self.path=None

    def __enter__(self):
        if os.name=="posix":
            flags=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
            fd=os.open(self.base,flags)
            try:
                for name in ("ftckb","receipts"):
                    if self.create:
                        try:
                            os.mkdir(name,0o700,dir_fd=fd)
                        except FileExistsError:
                            pass
                    child=os.open(name,flags,dir_fd=fd)
                    os.close(fd); fd=child
                    require(stat.S_IMODE(os.fstat(fd).st_mode)==0o700,"storage-unsafe")
                self.fd=fd
            except Exception:
                os.close(fd)
                raise
        else:
            for relative in ("ftckb","ftckb/receipts"):
                child=project.safe_path(self.base,relative)
                if self.create:
                    child.mkdir(exist_ok=True)
                require(child.is_dir(),"storage-unsafe")
            self.path=project.safe_path(self.base,"ftckb/receipts")
        return self

    def __exit__(self,*args):
        if self.fd is not None:
            os.close(self.fd)

    def open(self,name,flags,mode=0o600):
        flags|=getattr(os,"O_NOFOLLOW",0)
        if self.fd is not None:
            return os.open(name,flags,mode,dir_fd=self.fd)
        return os.open(project.safe_path(self.path,name),flags,mode)

    def unlink(self,name):
        if self.fd is not None:
            os.unlink(name,dir_fd=self.fd)
        else:
            os.unlink(project.safe_path(self.path,name))

    def read(self,identifier):
        fd=self.open(identifier+".json",os.O_RDONLY|getattr(os,"O_NONBLOCK",0))
        try:
            info=os.fstat(fd)
            require(stat.S_ISREG(info.st_mode) and info.st_size<=LIMIT,"receipt-invalid")
            require(os.name!="posix" or stat.S_IMODE(info.st_mode)==0o600,"storage-unsafe")
            data=b""
            while len(data)<=LIMIT:
                chunk=os.read(fd,min(8192,LIMIT+1-len(data)))
                if not chunk:
                    break
                data+=chunk
            require(len(data)<=LIMIT,"receipt-invalid")
            return json.loads(data.decode("utf-8"),object_pairs_hook=project.no_duplicate_keys)
        finally:
            os.close(fd)

    def write(self,receipt):
        temporary="."+str(uuid.uuid4())+".tmp"
        fd=self.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL)
        published=False
        try:
            if os.name=="posix":
                os.fchmod(fd,0o600)
            with os.fdopen(fd,"wb") as stream:
                fd=None
                stream.write(canonical(receipt)+b"\n"); stream.flush(); os.fsync(stream.fileno())
            final=receipt["id"]+".json"
            if self.fd is not None:
                os.link(temporary,final,src_dir_fd=self.fd,dst_dir_fd=self.fd,follow_symlinks=False)
                published=True
                os.fsync(self.fd)
            else:
                os.link(project.safe_path(self.path,temporary),project.safe_path(self.path,final))
                published=True
        except Exception:
            if published:
                self.unlink(receipt["id"]+".json")
            raise
        finally:
            if fd is not None:
                os.close(fd)
            self.unlink(temporary)


def validate_receipt(value,identifier):
    keys={"workReceiptSchemaVersion","id","createdAt","context","resolve","integrityDigest"}
    require(isinstance(value,dict) and set(value)==keys,"receipt-invalid")
    require(value["workReceiptSchemaVersion"]==1 and type(value["workReceiptSchemaVersion"]) is int,"receipt-invalid")
    require(value["id"]==identifier and valid_id(value["id"]),"receipt-invalid")
    require(isinstance(value["createdAt"],str),"receipt-invalid")
    try:
        require(datetime.fromisoformat(value["createdAt"]).tzinfo is not None,"receipt-invalid")
    except ValueError:
        raise WorkError("receipt-invalid") from None
    context=value["context"]
    require(isinstance(context,dict) and set(context)==CONTEXT_KEYS,"receipt-invalid")
    try:
        require(context["protocol"]==versions(context["protocol"]),"receipt-invalid")
        project.identity(context["team"],context["season"])
        require(context["workMode"] in ("normal","test","dev"),"receipt-invalid")
        for key in ("projectRoot","gitDir","knowledgePath"):
            require(isinstance(context[key],str) and Path(context[key]).is_absolute(),"receipt-invalid")
        for key in ("configSha256","knowledgeDigest"):
            require(isinstance(context[key],str) and re.fullmatch(r"[0-9a-f]{64}",context[key]),"receipt-invalid")
        require(isinstance(context["sourceCommit"],str) and re.fullmatch(r"[0-9a-f]{40}",context["sourceCommit"]),"receipt-invalid")
        if context["protocol"]["schemaVersion"]==1:
            require(context["profiles"] is None and context["normalizedProfiles"] is None,"receipt-invalid")
        else:
            require(normalize_profiles(context["profiles"])==context["normalizedProfiles"],"receipt-invalid")
    except (ValueError,TypeError,IntegrationError):
        raise WorkError("receipt-invalid") from None
    require(isinstance(value["resolve"],dict) and set(value["resolve"])=={"outputDigest"},"receipt-invalid")
    for checksum in (value["resolve"]["outputDigest"],value["integrityDigest"]):
        require(isinstance(checksum,str) and re.fullmatch(r"[0-9a-f]{64}",checksum),"receipt-invalid")
    require(value["integrityDigest"]==sha(canonical({k:v for k,v in value.items() if k!="integrityDigest"})),"receipt-invalid")
    return value


def receipt_summary(value,status,identifier=None):
    if value is None and identifier is None:
        return None
    return {"id":value["id"] if value else identifier,"status":status,
            "createdAt":value["createdAt"] if value else None,
            "contextDigest":sha(canonical(value["context"])) if value else None,
            "resolveOutputDigest":value["resolve"]["outputDigest"] if value else None}


def changed(before,after):
    return sorted(key for key in CONTEXT_KEYS if before.get(key)!=after.get(key))


def next_step(root,mode,identifier=None):
    argv=[sys.executable,str(Path(__file__).with_name("project.py").resolve()),"work",
          "check" if identifier else "resolve","--project",str(root)]
    if identifier:
        argv.extend(["--receipt",identifier])
    if mode!="normal":
        argv.extend(["--work-mode",mode])
    message="Run the explicit associated check; report every soft item."
    if not identifier:
        message="Resolve again in the intended current context before editing."
    elif mode=="dev":
        message="Before running, append --diff FILE for this explicitly authorized dev task; report every downgraded item."
    return {"code":"work-check" if identifier else "work-resolve","description":message,"cwd":str(root),"argv":argv}


def envelope(command):
    return {"workReceiptSchemaVersion":1,"command":"work-"+command,"ok":False,
            "receipt":None,"kernel":None,"error":None,"nextSteps":[]}


MESSAGES={"usage":"Use work resolve --project ROOT, or work check --project ROOT --receipt UUID; dev check also requires --diff FILE.",
          "receipt-invalid":"The receipt is missing, malformed or damaged; resolve again.",
          "context-changed":"Context or actual knowledge inputs changed; resolve again.",
          "setup-required":"Prepare and review the pinned CLI distribution and build receipt explicitly, then confirm Dashboard connection before work resolve.",
          "knowledge-unsafe":"Knowledge inputs cannot be completely and safely read; resolve again after repair.",
          "storage-unsafe":"Receipt storage is unsafe or unavailable; no association can be claimed.",
          "integration-invalid":"Project integration or kernel invocation could not be verified; no association can be claimed.",
          "kernel-failed":"The actual kernel operation did not succeed; review its embedded output."}


class Parser(argparse.ArgumentParser):
    def error(self,message):
        raise WorkError("usage")


def main(argv=None):
    argv=list(sys.argv[1:] if argv is None else argv)
    command=argv[0] if argv and argv[0] in ("resolve","check") else "resolve"
    out=envelope(command)
    parser=Parser(description=__doc__)
    parser.add_argument("command",choices=["resolve","check"])
    parser.add_argument("--project",required=True)
    parser.add_argument("--receipt")
    parser.add_argument("--diff")
    parser.add_argument("--work-mode",choices=["normal","test","dev"],default="normal")
    identifier=None; stored=None; root=None; mode="normal"
    try:
        args=parser.parse_args(argv); mode=args.work_mode
        out=envelope(args.command)
        require(not (args.command=="resolve" and (args.receipt or args.diff)),"usage")
        require(args.command!="check" or valid_id(args.receipt),"usage")
        require(not (args.command=="check" and mode=="dev" and not args.diff),"usage")
        identifier=args.receipt
        before,config,source=capture(args.project,mode)
        root=Path(before["projectRoot"])
        if identifier:
            try:
                with Store(before["gitDir"]) as store:
                    stored=validate_receipt(store.read(identifier),identifier)
            except (OSError,ValueError,TypeError,IntegrationError):
                raise WorkError("receipt-invalid") from None
            fields=changed(stored["context"],before)
            if fields:
                raise WorkError("context-changed",fields)
        setup_ready(source,config["source"]["commit"])
        diff_hash=sha(Path(args.diff).resolve(strict=True).read_bytes()) if args.diff else None
        code,payload=project.kernel(source,config,args.command,root,args.diff,mode)
        out["kernel"]={"exitCode":code,"output":payload}
        try:
            after,_,_=capture(args.project,mode)
        except (OSError,ValueError,TypeError,IntegrationError,ProbeError,WorkError):
            raise WorkError("context-changed") from None
        fields=changed(before,after)
        if args.diff:
            try:
                diff_changed=diff_hash!=sha(Path(args.diff).resolve(strict=True).read_bytes())
            except OSError:
                diff_changed=True
            if diff_changed:
                fields.append("diffSha256")
        if fields:
            raise WorkError("context-changed",fields)
        if args.command=="resolve":
            if code!=0:
                raise WorkError("kernel-failed")
            stored={"workReceiptSchemaVersion":1,"id":str(uuid.uuid4()),
                    "createdAt":datetime.now(timezone.utc).isoformat(),"context":before,
                    "resolve":{"outputDigest":sha(canonical(payload))}}
            stored["integrityDigest"]=sha(canonical(stored))
            try:
                with Store(before["gitDir"],create=True) as store:
                    store.write(stored)
            except (OSError,ValueError,WorkError):
                raise WorkError("storage-unsafe") from None
            identifier=stored["id"]
            out["receipt"]=receipt_summary(stored,"created")
            out["nextSteps"]=[next_step(root,mode,identifier)]
        else:
            out["receipt"]=receipt_summary(stored,"matched")
            if code not in (0,1):
                raise WorkError("kernel-failed")
        out["ok"]=code==0
        print(json.dumps(out,ensure_ascii=False)); return code
    except (WorkError,IntegrationError,ProbeError,OSError,ValueError,TypeError) as error:
        code=error.code if isinstance(error,WorkError) else "integration-invalid"
        fields=error.fields if isinstance(error,WorkError) else []
        out["error"]={"code":code,"message":MESSAGES[code],"changedFields":fields}
        out["receipt"]=receipt_summary(stored,"rejected",identifier)
        if root:
            out["nextSteps"]=[next_step(root,mode)]
        print(json.dumps(out,ensure_ascii=False))
        return 64 if code=="usage" else 2
