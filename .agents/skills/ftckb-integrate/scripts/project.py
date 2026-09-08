#!/usr/bin/env python3
"""Portable project configuration and deterministic ftckb invocation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.dont_write_bytecode=True
CONFIG=".ftckb/project.yaml"
SOURCE="tools/FTC-Knowledge-Bank"
SKILL=".agents/skills/ftc-knowledge-bank/SKILL.md"
BEGIN="<!-- BEGIN FTC-KNOWLEDGE-BANK -->"
END="<!-- END FTC-KNOWLEDGE-BANK -->"


class IntegrationError(Exception):
    pass


def run(args,cwd=None,check=True):
    result=subprocess.run([str(x) for x in args],cwd=cwd,capture_output=True,text=True,
                          encoding="utf-8",errors="replace")
    if check and result.returncode:
        raise IntegrationError(f"{args[0]} failed ({result.returncode}): {result.stderr.strip() or result.stdout.strip()}")
    return result


def git(root,*args,check=True):
    # Read-only Git commands must not refresh index stat data: dry-run promises
    # byte-for-byte immutability of the target, including .git metadata.
    # Required locks for mutating commands remain enabled.
    return run(["git","--no-optional-locks","-C",root,*args],check=check)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(root,relative):
    path=Path(relative)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise IntegrationError(f"Not a project-relative path: {relative}")
    cursor=root
    for index,part in enumerate(path.parts):
        cursor=cursor/part
        if cursor.is_symlink():
            raise IntegrationError(f"Refusing symlink in managed path: {relative}")
        if index<len(path.parts)-1 and cursor.exists() and not cursor.is_dir():
            raise IntegrationError(f"Managed path parent is not a directory: {relative}")
    return cursor


def project_root(value):
    root=Path(value).resolve(strict=True)
    if Path(git(root,"rev-parse","--show-toplevel").stdout.strip()).resolve()!=root:
        raise IntegrationError("--project must name the Git repository root")
    git(root,"rev-parse","--verify","HEAD")
    if git(root,"ls-files","--unmerged").stdout:
        raise IntegrationError("Resolve the project's merge conflicts before integration")
    return root


def identity(team,season):
    if not isinstance(team,str) or not re.fullmatch(r"[1-9][0-9]*",team):
        raise IntegrationError("An explicit positive team number is required; never use a README example as a default")
    if not isinstance(season,str) or not re.fullmatch(r"[0-9]{4}-[0-9]{4}",season):
        raise IntegrationError("An explicit season is required, in YYYY-YYYY format")
    first,last=map(int,season.split("-"))
    if last!=first+1:
        raise IntegrationError("Season must consist of consecutive years")


def no_duplicate_keys(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise IntegrationError(f"Duplicate configuration key: {key}")
        result[key]=value
    return result


def load_config(root):
    path=safe_path(root,CONFIG)
    try:
        config=json.loads(path.read_text(encoding="utf-8"),object_pairs_hook=no_duplicate_keys)
    except (ValueError,OSError) as error:
        raise IntegrationError(f"Cannot read {CONFIG}: {error}. Use JSON syntax (valid YAML 1.2).") from error
    if not isinstance(config,dict):
        raise IntegrationError("Project configuration must be an object")
    for field in ("schemaVersion","kernelSchemaVersion","integrationVersion"):
        if type(config.get(field)) is not int or config[field]!=1:
            raise IntegrationError(f"Unsupported {field}; expected 1")
    identity(config.get("team"),config.get("season"))
    source=config.get("source")
    if not isinstance(source,dict) or source.get("path")!=SOURCE:
        raise IntegrationError(f"source.path must be {SOURCE}")
    if not isinstance(source.get("commit"),str) or not re.fullmatch(r"[0-9a-f]{40}",source["commit"]):
        raise IntegrationError("source.commit must be a full 40-character Git commit SHA")
    for key in ("repository","ref"):
        if not isinstance(source.get(key),str) or not source[key].strip():
            raise IntegrationError(f"source.{key} is required")
    hashes=config.get("managedFiles")
    if not isinstance(hashes,dict) or set(hashes)!={SKILL,"AGENTS.md#ftckb"}:
        raise IntegrationError("Invalid managedFiles receipt")
    if any(not isinstance(v,str) or not re.fullmatch(r"[0-9a-f]{64}",v) for v in hashes.values()):
        raise IntegrationError("Invalid managed file digest")
    return config


def agents_block(data):
    start=data.count(BEGIN.encode())
    end=data.count(END.encode())
    if not start and not end:
        return None
    if start!=1 or end!=1:
        raise IntegrationError("AGENTS.md has duplicate or incomplete FTC-KB markers")
    lo=data.index(BEGIN.encode())
    hi=data.index(END.encode())+len(END)
    if hi<=lo:
        raise IntegrationError("AGENTS.md has reversed FTC-KB markers")
    return data[lo:hi]


def module_metadata(root,config):
    safe_path(root,".gitmodules")
    source=config["source"]
    result=git(root,"config","--file",".gitmodules","--get-regexp",r"^submodule\..*\.path$",check=False)
    matches=[line.rsplit(" ",1)[0][:-5] for line in result.stdout.splitlines()
             if line.endswith(" "+SOURCE)]
    if len(matches)!=1:
        raise IntegrationError("Expected exactly one FTC-KB submodule in .gitmodules")
    url=git(root,"config","--file",".gitmodules","--get",matches[0]+".url").stdout.strip()
    if url!=source["repository"]:
        raise IntegrationError("Submodule URL does not match the pinned project configuration")
    entries=git(root,"ls-files","--stage","--",SOURCE).stdout.splitlines()
    if len(entries)!=1 or entries[0].split()[:3]!=["160000",source["commit"],"0"]:
        raise IntegrationError("Submodule gitlink in the index does not match source.commit")


def check_pin(root,config):
    module_metadata(root,config)
    source=safe_path(root,SOURCE)
    if not (source/".git").exists():
        raise IntegrationError("Submodule is not initialized; run git submodule update --init -- tools/FTC-Knowledge-Bank")
    if Path(git(source,"rev-parse","--show-toplevel").stdout.strip()).resolve()!=source.resolve():
        raise IntegrationError("Knowledge path is not an independent Git submodule")
    if git(source,"rev-parse","HEAD").stdout.strip()!=config["source"]["commit"]:
        raise IntegrationError("Submodule HEAD does not match source.commit; do not follow main")
    if git(source,"status","--porcelain","--untracked-files=all").stdout:
        raise IntegrationError("Knowledge submodule has local changes; review them before using its pinned rules")
    if not (source/"knowledge").is_dir():
        raise IntegrationError("Knowledge directory is missing")
    return source


def check_managed(root,config):
    skill=safe_path(root,SKILL)
    agents=safe_path(root,"AGENTS.md")
    if not skill.is_file() or digest(skill.read_bytes())!=config["managedFiles"][SKILL]:
        raise IntegrationError("Project Skill is missing or locally modified")
    block=agents_block(agents.read_bytes()) if agents.is_file() else None
    if block is None or digest(block)!=config["managedFiles"]["AGENTS.md#ftckb"]:
        raise IntegrationError("AGENTS.md managed block is missing or locally modified")


def validator(source):
    try:
        from jsonschema import Draft7Validator
    except ImportError as error:
        raise IntegrationError("jsonschema is required: use a Python venv and pip install 'jsonschema>=4.18,<5'") from error
    schema=json.loads((source/"docs/kernel-contract.schema.json").read_text(encoding="utf-8"))
    Draft7Validator.check_schema(schema)
    return Draft7Validator(schema)


def launcher(source,windows=None):
    windows=os.name=="nt" if windows is None else windows
    path=source/"apps/knowledge-cli/build/install/ftckb/bin"/("ftckb.bat" if windows else "ftckb")
    return [str(path)] if windows else ["sh",str(path)]


def build_cli(source,commit):
    stamp=source/"apps/knowledge-cli/build/ftckb-integration-commit"
    command=launcher(source)
    if Path(command[-1]).is_file() and stamp.is_file() and stamp.read_text().strip()==commit:
        return
    wrapper=[str(source/"gradlew.bat")] if os.name=="nt" else ["sh",str(source/"gradlew")]
    result=subprocess.run(wrapper+[":apps:knowledge-cli:installDist","--console=plain"],cwd=source,
                          stdout=sys.stderr,stderr=sys.stderr)
    if result.returncode:
        raise IntegrationError("CLI build failed; installed files are retained. Fix JDK/network/cache access, then rerun the same installation or verify.py.")
    if not Path(command[-1]).is_file():
        raise IntegrationError("Build succeeded but the ftckb launcher is missing")
    stamp.write_text(commit+"\n",encoding="utf-8")


def kernel(source,config,command,root,diff=None):
    args=launcher(source)+[command]
    if command=="validate":
        args+=[str(source/"knowledge")]
    elif command=="resolve":
        args+=[str(source/"knowledge"),"--team",config["team"],"--season",config["season"]]
    elif command=="check":
        args+=[str(root),"--knowledge",str(source/"knowledge"),"--team",config["team"],"--season",config["season"]]
        if diff is not None:
            args+=["--diff",str(Path(diff).resolve(strict=True))]
    else:
        raise IntegrationError(f"Unsupported kernel command: {command}")
    result=run(args+["--json"],cwd=root,check=False)
    try:
        payload=json.loads(result.stdout)
    except ValueError as error:
        raise IntegrationError(f"{command} did not return JSON (exit {result.returncode}): {result.stderr.strip()}") from error
    errors=list(validator(source).iter_errors(payload))
    if errors:
        raise IntegrationError(f"{command} violates kernel JSON Schema: {errors[0].message}")
    if payload.get("schemaVersion")!=1 or payload.get("command")!=command:
        raise IntegrationError("Unsupported kernel version or mismatched command")
    if "error" in payload:
        expected=64 if payload["error"]["code"]=="usage" else 2
    else:
        if command in ("resolve","check"):
            if (payload.get("team"),payload.get("season"))!=(config["team"],config["season"]):
                raise IntegrationError("Kernel response does not match the configured team and season")
        issues=payload["conflicts"] if command=="resolve" else payload["violations"]
        if payload["ok"]!=(len(issues)==0):
            raise IntegrationError("Kernel ok field contradicts reported conflicts/violations")
        expected=0 if payload["ok"] else (1 if command=="check" else 2)
    if result.returncode!=expected:
        raise IntegrationError(f"{command} JSON and exit code disagree: expected {expected}, got {result.returncode}")
    return result.returncode,payload


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=["validate","resolve","check"])
    parser.add_argument("--project",required=True)
    parser.add_argument("--diff")
    args=parser.parse_args(argv)
    try:
        if args.diff and args.command!="check":
            raise IntegrationError("--diff is only supported for check")
        root=project_root(args.project)
        config=load_config(root)
        source=check_pin(root,config)
        check_managed(root,config)
        validator(source)
        build_cli(source,config["source"]["commit"])
        code,payload=kernel(source,config,args.command,root,args.diff)
        print(json.dumps(payload,ensure_ascii=False))
        return code
    except (IntegrationError,OSError,ValueError) as error:
        print(json.dumps({"integrationOk":False,"error":str(error)},ensure_ascii=False))
        return 2


if __name__=="__main__":
    sys.exit(main())
