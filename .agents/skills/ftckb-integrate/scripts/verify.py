#!/usr/bin/env python3
"""Verify installation wiring, kernel contracts and target compliance separately."""
import argparse
import json
import sys

sys.dont_write_bytecode=True
from project import (IntegrationError,build_cli,check_managed,check_pin,kernel,load_config,
                     project_root,validator)


def verify(root):
    config=load_config(root)
    source=check_pin(root,config)
    check_managed(root,config)
    validator(source)
    build_cli(source,config["source"]["commit"])
    results={}
    for command in ("validate","resolve","check"):
        code,payload=kernel(source,config,command,root)
        results[command]={"exitCode":code,"output":payload}
        if code not in (0,1):
            return 2,{"installationOk":False,"team":config["team"],"season":config["season"],
                      "checks":results,"error":f"{command} cannot proceed; resolve errors/conflicts before editing"}
    check=results["check"]
    return check["exitCode"],{
        "installationOk":True,"team":config["team"],"season":config["season"],
        "commit":config["source"]["commit"],"checks":results,
        "projectCheck":{"ok":check["output"]["ok"],"exitCode":check["exitCode"],
                        "violations":check["output"]["violations"],"soft":check["output"]["soft"]},
        "robotValidation":"not-performed"}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project",required=True)
    args=parser.parse_args(argv)
    try:
        code,payload=verify(project_root(args.project))
        print(json.dumps(payload,ensure_ascii=False,indent=2))
        return code
    except (IntegrationError,OSError,ValueError) as error:
        print(json.dumps({"installationOk":False,"error":str(error)},ensure_ascii=False))
        return 2


if __name__=="__main__":
    sys.exit(main())
