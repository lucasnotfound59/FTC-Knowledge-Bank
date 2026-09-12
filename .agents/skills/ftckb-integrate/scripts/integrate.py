#!/usr/bin/env python3
"""Install a pinned FTC Knowledge Bank submodule and project-level Agent Skill."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import urlsplit

sys.dont_write_bytecode=True
from project import (CONFIG,SOURCE,SKILL,BEGIN,END,IntegrationError,agents_block,check_pin,
                     digest,git,identity,load_config,module_metadata,project_root,run,safe_path,source_versions,validator)
from integration_contract import normalize_profiles

REPOSITORY="https://github.com/lucasnotfound59/FTC-Knowledge-Bank.git"
BUNDLE=".agents/skills/ftckb-integrate"


def repository_url(value):
    if not value or value.startswith("-") or any(c in value for c in "\r\n\x00"):
        raise IntegrationError("Invalid repository URL")
    parsed=urlsplit(value)
    if parsed.scheme in ("https","http") and (parsed.username or parsed.password):
        raise IntegrationError("Do not embed credentials in a repository URL; use Git's credential manager")
    if Path(value).exists():
        return str(Path(value).resolve())
    return value


def resolve_ref(repository,ref):
    if re.fullmatch(r"[0-9a-fA-F]{40}",ref or ""):
        return ref.lower()
    if not ref or ref.startswith("-") or ref.startswith("refs/"):
        raise IntegrationError("Supply --ref with a release tag or full commit SHA; a branch is not a pin")
    if run(["git","check-ref-format","refs/tags/"+ref],check=False).returncode:
        raise IntegrationError("Invalid release tag")
    tag="refs/tags/"+ref
    result=run(["git","ls-remote","--tags","--",repository,tag,tag+"^{}"])
    entries=dict(line.split()[::-1] for line in result.stdout.splitlines())
    commit=entries.get(tag+"^{}",entries.get(tag))
    if not commit:
        raise IntegrationError(f"No exact tag {ref!r}; main/branch names are not accepted. Use a published tag or full SHA.")
    return commit


@contextmanager
def pinned_source(repository,commit,existing=None):
    if existing is not None and git(existing,"rev-parse","HEAD").stdout.strip()==commit:
        yield existing
        return
    with tempfile.TemporaryDirectory(prefix="ftckb-source-") as temp:
        source=Path(temp)/"source"
        run(["git","clone","--quiet","--no-checkout","--no-hardlinks","--",repository,source])
        if git(source,"cat-file","-e",commit+"^{commit}",check=False).returncode:
            git(source,"fetch","--quiet","origin",commit)
        actual=git(source,"rev-parse","--verify",commit+"^{commit}").stdout.strip()
        if actual!=commit:
            raise IntegrationError("The supplied SHA is not a commit object")
        git(source,"checkout","--quiet","--detach",commit)
        yield source


def template_files(source):
    required=[BUNDLE+"/scripts/project.py",BUNDLE+"/scripts/verify.py",
              "docs/kernel-contract.schema.json","gradlew","gradlew.bat"]
    for relative in required:
        if not safe_path(source,relative).is_file():
            raise IntegrationError(f"Selected revision does not support project integration: missing {relative}")
    if not safe_path(source,"knowledge").is_dir():
        raise IntegrationError("Selected revision has no knowledge directory")
    assets=safe_path(source,BUNDLE+"/assets")
    skill=safe_path(assets,"project-skill/SKILL.md").read_bytes()
    block=safe_path(assets,"AGENTS.block.md").read_bytes().rstrip(b"\r\n")
    if agents_block(block)!=block:
        raise IntegrationError("Invalid upstream managed block template")
    return skill,block


def managed_plan(root,old,config,skill,block):
    files={SKILL:skill}
    current_skill=safe_path(root,SKILL)
    if current_skill.exists():
        if not current_skill.is_file() or not old or digest(current_skill.read_bytes())!=old["managedFiles"][SKILL]:
            raise IntegrationError("Existing project Skill is not an unchanged managed file; refusing overwrite")
    agents=safe_path(root,"AGENTS.md")
    data=agents.read_bytes() if agents.exists() else b""
    previous=agents_block(data)
    if previous is not None:
        if not old or digest(previous)!=old["managedFiles"]["AGENTS.md#ftckb"]:
            raise IntegrationError("Existing AGENTS.md block is not an unchanged managed block; refusing overwrite")
        data=data.replace(previous,block,1)
    else:
        data+=(b"\n\n" if data and not data.endswith(b"\n") else b"\n" if data else b"")+block+b"\n"
    files["AGENTS.md"]=data
    config["managedFiles"]={SKILL:digest(skill),"AGENTS.md#ftckb":digest(block)}
    files[CONFIG]=(json.dumps(config,ensure_ascii=False,indent=2)+"\n").encode()
    before={relative:safe_path(root,relative).read_bytes() if safe_path(root,relative).exists() else None
            for relative in files}
    return files,before


def write_files(root,files,before):
    # Recheck every managed file before the first write. Never reset user files on failure.
    for relative,expected in before.items():
        path=safe_path(root,relative)
        actual=path.read_bytes() if path.exists() else None
        if actual!=expected:
            raise IntegrationError(f"File changed since preflight: {relative}; retry after reviewing it")
    # Receipt first makes an interrupted write recoverable without adopting arbitrary files.
    for relative in [CONFIG,SKILL,"AGENTS.md"]:
        path=safe_path(root,relative)
        if before[relative]==files[relative]:
            continue
        path.parent.mkdir(parents=True,exist_ok=True)
        fd,temp=tempfile.mkstemp(prefix=".ftckb-write-",dir=path.parent)
        try:
            with os.fdopen(fd,"wb") as handle:
                handle.write(files[relative])
            if path.exists():
                os.chmod(temp,path.stat().st_mode)
            os.replace(temp,path)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)


def integrate(args):
    if args.generic_profile and args.profile is not None:
        raise IntegrationError("--profile and --generic-profile are mutually exclusive")
    root=project_root(args.project)
    if not (root/"TeamCode").is_dir() or not any((root/name).is_file() for name in ("settings.gradle","settings.gradle.kts")):
        raise IntegrationError("Expected an FTC Gradle project with TeamCode and settings.gradle[.kts]")
    old=load_config(root) if safe_path(root,CONFIG).exists() else None
    team=args.team if args.team is not None else old["team"] if old else None
    season=args.season if args.season is not None else old["season"] if old else None
    identity(team,season)
    repository=repository_url(args.repository or (old["source"]["repository"] if old else REPOSITORY))
    if old and repository!=old["source"]["repository"]:
        raise IntegrationError("Changing submodule repository is not an automatic upgrade; review a manual migration")
    ref=args.ref or (old["source"]["ref"] if old else None)
    commit=old["source"]["commit"] if old and args.ref is None else resolve_ref(repository,ref)
    source_path=safe_path(root,SOURCE)
    existing=None
    if old:
        module_metadata(root,old)
        if (source_path/".git").exists():
            existing=check_pin(root,old)
        elif source_path.exists() and any(source_path.iterdir()):
            raise IntegrationError("Uninitialized submodule directory is not empty")
    else:
        if source_path.exists() or git(root,"ls-files","--",SOURCE).stdout:
            raise IntegrationError(f"Conflicting existing path: {SOURCE}; refusing adoption or overwrite")
        if git(root,"status","--porcelain","--",".gitmodules").stdout:
            raise IntegrationError(".gitmodules has user changes; finish or save those before adding a submodule")
        safe_path(root,".gitmodules")
        if git(root,"config","--file",".gitmodules","--get-regexp",r"^submodule\.ftckb\.",check=False).stdout:
            raise IntegrationError("Submodule name ftckb is already used")
        if git(root,"config","--local","--get-regexp",r"^submodule\.ftckb\.",check=False).stdout:
            raise IntegrationError("A previous submodule.ftckb registration exists; inspect it before retrying")
    with pinned_source(repository,commit,existing) as source:
        skill,block=template_files(source)
        protocol=source_versions(source)
        if protocol["schemaVersion"]==1 and not old:
            raise IntegrationError("New installs require a protocol v2 revision and an explicit profile choice")
        if protocol["schemaVersion"]==1 and old["schemaVersion"]!=1:
            raise IntegrationError("Automatic protocol downgrade from v2 to v1 is not supported")
        if protocol["schemaVersion"]==1 and (args.profile is not None or args.generic_profile):
            raise IntegrationError("Protocol v1 does not support profiles; explicitly upgrade to v2 first")
        config=protocol|{"team":team,"season":season,
                         "source":{"repository":repository,"ref":ref,"commit":commit,"path":SOURCE}}
        if protocol["schemaVersion"]==2:
            if not safe_path(source,BUNDLE+"/scripts/integration_contract.py").is_file():
                raise IntegrationError("Selected v2 revision is missing scripts/integration_contract.py")
            selected=[] if args.generic_profile else args.profile
            if selected is None and old and old["schemaVersion"]==2:
                selected=old["profiles"]
            normalize_profiles(selected)
            config["profiles"]=list(selected)
        files,before=managed_plan(root,old,config,skill,block)
        changes=[relative for relative,data in files.items() if before[relative]!=data]
        plan=protocol|{"dryRun":args.dry_run,"team":team,"season":season,"commit":commit,"files":changes,
              "submodule":"add" if not old else "upgrade" if commit!=old["source"]["commit"] else "keep",
              "gitEffects":["submodule registration; .gitmodules and gitlink are staged"] if not old else
                           ["only the updated gitlink is staged"] if commit!=old["source"]["commit"] else [],
              "branch":git(root,"symbolic-ref","--short","HEAD",check=False).stdout.strip() or "detached HEAD",
              "commitOrPush":False}
        if "profiles" in config:
            plan["profiles"]=config["profiles"]
        if args.dry_run:
            return 0,plan
        validator(source)  # Dependency/schema preflight, before changing the target.
        print(json.dumps({"plan":plan},ensure_ascii=False),file=sys.stderr)
        if not old:
            git(root,"submodule","add","--name","ftckb","--",repository,SOURCE)
        elif existing is None:
            git(root,"submodule","update","--init","--",SOURCE)
        if git(source_path,"cat-file","-e",commit+"^{commit}",check=False).returncode:
            git(source_path,"fetch","--quiet","origin",commit)
        if git(source_path,"rev-parse","HEAD").stdout.strip()!=commit:
            git(source_path,"checkout","--quiet","--detach",commit)
        if not old or old["source"]["commit"]!=commit:
            git(root,"add","--",SOURCE)
        write_files(root,files,before)
    from verify import verify
    return verify(root)


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project",required=True)
    parser.add_argument("--team")
    parser.add_argument("--season")
    parser.add_argument("--repository")
    parser.add_argument("--ref",help="Exact release tag or full commit SHA; never a branch")
    profiles=parser.add_mutually_exclusive_group()
    profiles.add_argument("--profile",action="append",help="Explicit project profile; repeat to select several compatible profiles")
    profiles.add_argument("--generic-profile",action="store_true",help="Explicitly select no project profiles")
    parser.add_argument("--dry-run",action="store_true")
    args=parser.parse_args(argv)
    try:
        code,payload=integrate(args)
        print(json.dumps(payload,ensure_ascii=False,indent=2))
        return code
    except (IntegrationError,OSError,ValueError) as error:
        print(json.dumps({"installationOk":False,"error":str(error),
                          "recovery":"No user files were reset. Inspect git status; build failures can be retried with the same command."},ensure_ascii=False))
        return 2


if __name__=="__main__":
    sys.exit(main())
