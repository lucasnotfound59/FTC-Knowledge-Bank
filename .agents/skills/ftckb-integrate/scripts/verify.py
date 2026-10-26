#!/usr/bin/env python3
"""Verify installation wiring, kernel contracts and target compliance separately."""
import argparse
import json
import sys

sys.dont_write_bytecode=True
from project import (IntegrationError,build_cli,check_managed,check_pin,kernel,load_config,
                     project_root,validator)
from integration_contract import versions


def verify(root):
    config=load_config(root)
    context=versions(config)|{"team":config["team"],"season":config["season"]}
    if "profiles" in config:
        context["profiles"]=config["profiles"]
    source=check_pin(root,config)
    check_managed(root,config)
    validator(source)
    build_cli(source,config["source"]["commit"])
    results={}
    for command in ("validate","resolve","check"):
        code,payload=kernel(source,config,command,root)
        results[command]={"exitCode":code,"output":payload}
        if code not in (0,1):
            return 2,context|{"installationOk":False,
                      "checks":results,"error":f"{command} cannot proceed; resolve errors/conflicts before editing"}
    check=results["check"]
    return check["exitCode"],context|{
        "installationOk":True,
        "commit":config["source"]["commit"],"checks":results,
        "projectCheck":{"ok":check["output"]["ok"],"exitCode":check["exitCode"],
                        "violations":check["output"]["violations"],"soft":check["output"]["soft"]},
        "robotValidation":"not-performed"}


def main(argv=None):
    argv=list(sys.argv[1:] if argv is None else argv)
    flags={"--accept","--prepared-context-sha256","--connection-file","--browser-confirmed"}
    staged=any(len(key)>2 and any(flag.startswith(key) for flag in flags) for value in argv for key in [value.split("=",1)[0]])
    if staged:
        from installation_workflow import Parser,WorkflowError,failure,accept
        parser=Parser(description=__doc__,allow_abbrev=False)
    else:
        parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project",required=True)
    parser.add_argument("--accept",action="store_true",help="Accept an explicitly prepared, connected installation without building")
    parser.add_argument("--prepared-context-sha256")
    parser.add_argument("--connection-file")
    parser.add_argument("--browser-confirmed",action="store_true")
    try:
        args=parser.parse_args(argv)
    except Exception:
        if not staged:raise
        code,payload=failure("accept","usage");print(json.dumps(payload,ensure_ascii=False));return code
    if staged:
        if not args.accept:
            code,payload=failure("accept","usage")
        else:
            code,payload=accept(args.project,args.prepared_context_sha256,args.connection_file,args.browser_confirmed)
        print(json.dumps(payload,ensure_ascii=False,indent=2));return code
    try:
        code,payload=verify(project_root(args.project))
        print(json.dumps(payload,ensure_ascii=False,indent=2))
        return code
    except (IntegrationError,OSError,ValueError) as error:
        print(json.dumps({"installationOk":False,"error":str(error)},ensure_ascii=False))
        return 2


if __name__=="__main__":
    sys.exit(main())
