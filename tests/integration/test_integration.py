"""Offline integration tests: real Git repositories, isolated build/kernel fixtures."""
import contextlib
import concurrent.futures
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
BUNDLE=Path(".agents/skills/ftckb-integrate")
sys.path.insert(0,str(ROOT/BUNDLE/"scripts"))
import integrate
import project
import verify
import project_doctor as doctor
import work_receipt as work


class IntegrationContractTest(unittest.TestCase):
    def test_profile_args_are_explicit(self):
        from integration_contract import IntegrationError,normalize_profiles,profile_args
        self.assertEqual(["--generic-profile"],profile_args({"profiles":[]}))
        self.assertEqual(["--profile","command-based"],profile_args({"profiles":["command-based"]}))
        with self.assertRaises(IntegrationError):
            normalize_profiles(None)
        with self.assertRaises(IntegrationError):
            normalize_profiles(["simple-opmode","command-based"])

    def test_profiles_validate_implications_without_changing_selection(self):
        from integration_contract import IntegrationError,normalize_profiles,profile_args
        for selected,normalized in ((["rookiebot"],["rookiebot","simple-opmode"]),
                                    (["ftclib-command"],["command-based","ftclib-command"])):
            with self.subTest(selected=selected):
                config={"profiles":selected.copy()}
                self.assertEqual(normalized,normalize_profiles(selected))
                self.assertEqual([part for value in normalized for part in ("--profile",value)],profile_args(config))
                self.assertEqual(selected,config["profiles"])
        for values in (None,"rookiebot",[1],[{}],["unknown"],["rookiebot","rookiebot"],
                       ["rookiebot","command-based"],["simple-opmode","ftclib-command"],
                       ["rookiebot","ftclib-command"]):
            with self.subTest(values=values),self.assertRaises(IntegrationError):
                normalize_profiles(values)

    def test_versions_and_capabilities_reject_mixed_or_unknown_protocols(self):
        from integration_contract import IntegrationError,capabilities,versions
        for version in (1,2):
            config={"schemaVersion":version,"kernelSchemaVersion":version,"integrationVersion":version}
            manifest={"projectSchemaVersion":version,"kernelSchemaVersion":version,"integrationVersion":version}
            self.assertEqual(config,versions(config))
            self.assertEqual(config,capabilities(manifest))
        self.assertEqual(versions({"schemaVersion":1,"kernelSchemaVersion":1,"integrationVersion":1}),capabilities(None))
        for value in (None,{},[],{"schemaVersion":2,"kernelSchemaVersion":1,"integrationVersion":2},
                      {"schemaVersion":True,"kernelSchemaVersion":1,"integrationVersion":1},
                      {"schemaVersion":3,"kernelSchemaVersion":3,"integrationVersion":3}):
            with self.subTest(value=value),self.assertRaises(IntegrationError):
                versions(value)
        for value in ({},[],{"projectSchemaVersion":2,"kernelSchemaVersion":2,"integrationVersion":1}):
            with self.subTest(value=value),self.assertRaises(IntegrationError):
                capabilities(value)


def command(*args,cwd=None,env=None):
    return subprocess.run([str(a) for a in args],cwd=cwd,env=env,capture_output=True,text=True,check=True)


def git(root,*args):
    return command("git","-C",root,*args).stdout.strip()


def write(root,path,text):
    target=root/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text,encoding="utf-8")


def initialize(root):
    root.mkdir(parents=True)
    git(root,"init","-b","team-work")
    git(root,"config","user.name","Integration Test")
    git(root,"config","user.email","integration@example.invalid")


def commit(root,message="fixture"):
    git(root,"add",".")
    git(root,"commit","-m",message)
    return git(root,"rev-parse","HEAD")


def snapshot(root):
    # Includes worktree, index, .git/config and refs; omit access-time metadata.
    return {str(path.relative_to(root)):path.read_bytes() for path in root.rglob("*") if path.is_file()}


FAKE_KERNEL='''import json,sys
from pathlib import Path
args=sys.argv[1:]
command=args[0]
version=2 if (Path(__file__).resolve().parent.parent/".agents/skills/ftckb-integrate/assets/integration.json").exists() else 1
payload={"schemaVersion":version,"command":command,"ok":True}
if "--work-mode" in args:
    payload["workMode"]=args[args.index("--work-mode")+1]
code=0
if command=="validate":
    payload.update(ruleCount=1,violations=[])
else:
    payload.update(team=args[args.index("--team")+1],season=args[args.index("--season")+1])
    if version==2:
        profiles={args[index+1] for index,arg in enumerate(args) if arg=="--profile"}
        if not profiles and "--generic-profile" not in args:
            sys.exit("Missing explicit profile choice")
        if "rookiebot" in profiles:
            profiles.add("simple-opmode")
        if "ftclib-command" in profiles:
            profiles.add("command-based")
        payload.update(profiles=sorted(profiles))
    elif "--profile" in args or "--generic-profile" in args:
        sys.exit("Legacy kernel does not support profile flags")
    if command=="resolve":
        payload.update(activeRules=[],conflicts=[])
        if version==2:
            payload.update(excludedRules=[],overriddenRules=[])
    else:
        hard=(Path(args[1])/"build.common.gradle").exists()
        violations=[{"ruleId":"fixture.path","check":"path-forbidden","path":"build.common.gradle","pattern":"build.common.gradle","detail":"fixture"}] if hard else []
        payload.update(ok=not hard,violations=violations,soft=[{"ruleId":"fixture.soft","note":"Review robot behavior"}])
        code=1 if hard else 0
print(json.dumps(payload))
sys.exit(code)
'''

FAKE_BUILD='''from pathlib import Path
import os,sys
root=Path(__file__).resolve().parent.parent
if os.environ.get("FTCKB_TEST_BUILD_FAIL")=="1":
    sys.exit(9)
launcher=root/"apps/knowledge-cli/build/install/ftckb/bin/ftckb"
launcher.parent.mkdir(parents=True,exist_ok=True)
launcher.write_text('#!/bin/sh\\nexec "'+sys.executable+'" "'+str(root/"tools/fake_kernel.py")+'" "$@"\\n')
'''


class ShellEntryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="ftckb-shell-")
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/"repo with spaces"
        self.root.mkdir()
        self.launcher=self.root/"apps/knowledge-cli/build/install/ftckb/bin/ftckb"
        write(self.root,str(self.launcher.relative_to(self.root)),
              '#!/bin/sh\nprintf "%s\\n" "$@"\nexit "${FTCKB_GATE_EXIT:-0}"\n')
        self.launcher.chmod(0o755)
        self.base=[str(self.root),str(self.root/"knowledge with spaces"),"20827","2025-2026"]

    def gate(self,args,code=0):
        return subprocess.run(["sh",str(ROOT/"scripts/check-gate.sh"),*args],
                              env=os.environ|{"FTCKB_GATE_EXIT":str(code)},
                              capture_output=True,text=True,timeout=10)

    def test_gate_requires_exactly_five_or_six_arguments_before_using_them(self):
        for count in (0,1,2,3,4,7,8):
            with self.subTest(count=count):
                result=self.gate((self.base+["generic","patch file","extra","extra"])[:count])
                self.assertEqual(64,result.returncode)
                self.assertEqual("",result.stdout)
                self.assertIn("PROFILE_OR_GENERIC [DIFF]",result.stderr)

    def test_gate_forwards_profiles_diff_and_kernel_exit_codes_exactly(self):
        for profile,flags in (("generic",["--generic-profile"]),
                              ("command-based",["--profile","command-based"]),
                              ("name with spaces;*",["--profile","name with spaces;*"])):
            for diff in ([],[str(self.root/"patch with spaces;*.diff")],[""]):
                for code in (0,1,2,64):
                    with self.subTest(profile=profile,diff=diff,code=code):
                        result=self.gate(self.base+[profile]+diff,code)
                        expected=["check",self.base[0],"--knowledge",self.base[1],
                                  "--team","20827","--season","2025-2026"]+flags
                        if diff:
                            expected+=["--diff",diff[0]]
                        self.assertEqual(expected+["--json"],result.stdout.splitlines())
                        self.assertEqual(code,result.returncode)
                        self.assertEqual("",result.stderr)

    def test_gate_builds_missing_launcher_and_returns_two_on_build_failure(self):
        self.launcher.rename(self.root/"launcher-template")
        write(self.root,"gradlew",'#!/bin/sh\nprintf "%s\\n" "$@" >build-args\n'
              'test "${FTCKB_GATE_BUILD_FAIL:-0}" = 0 || exit 9\n'
              'cp launcher-template apps/knowledge-cli/build/install/ftckb/bin/ftckb\n')
        (self.root/"gradlew").chmod(0o755)
        with patch.dict(os.environ,{"FTCKB_GATE_BUILD_FAIL":"1"}):
            self.assertEqual(2,self.gate(self.base+["generic"]).returncode)
        self.assertFalse(self.launcher.exists())
        result=self.gate(self.base+["generic"],1)
        self.assertEqual(1,result.returncode)
        self.assertTrue(self.launcher.is_file())
        self.assertEqual([":apps:knowledge-cli:installDist","--no-daemon"],
                         (self.root/"build-args").read_text().splitlines())
        self.assertIn("--generic-profile",result.stdout.splitlines())

    def test_smoke_successful_resolves_explicitly_choose_generic(self):
        lines=[line for line in (ROOT/"scripts/smoke.sh").read_text().splitlines()
               if '"$FTCKB" resolve knowledge --team' in line]
        self.assertEqual(2,len(lines))
        for line in lines:
            self.assertIn("--generic-profile",line)


class IntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="ftckb-test-")
        self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name)
        self.env=patch.dict(os.environ,{"GIT_ALLOW_PROTOCOL":"file","PYTHONDONTWRITEBYTECODE":"1"})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.source=self.base/"知识库 source"
        initialize(self.source)
        shutil.copytree(ROOT/BUNDLE,self.source/BUNDLE)
        write(self.source,".gitignore","**/build/\n__pycache__/\n")
        write(self.source,"knowledge/.keep","")
        write(self.source,"docs/kernel-contract.schema.json",(ROOT/"docs/kernel-contract.schema.json").read_text())
        write(self.source,"gradlew","#!/bin/sh\nexec python3 tools/fake_build.py\n")
        write(self.source,"gradlew.bat","@echo off\r\npython tools/fake_build.py\r\n")
        write(self.source,"tools/fake_kernel.py",FAKE_KERNEL)
        write(self.source,"tools/fake_build.py",FAKE_BUILD)
        self.sha=commit(self.source)
        git(self.source,"tag","v-test")
        self.target=self.base/"机器人 project"
        initialize(self.target)
        write(self.target,"TeamCode/build.gradle","// team module\n")
        write(self.target,"settings.gradle","include ':TeamCode'\n")
        write(self.target,"AGENTS.md","# Team instructions\n保留原文。\n")
        self.head=commit(self.target)

    def args(self,**kwargs):
        values=dict(project=str(self.target),team="16093",season="2025-2026",
                    repository=str(self.source),ref="v-test",dry_run=False,profile=None,generic_profile=True)
        values.update(kwargs)
        return type("Args",(),values)()

    def install(self,**kwargs):
        with tempfile.TemporaryFile(mode="w+") as logs,contextlib.redirect_stderr(logs):
            return integrate.integrate(self.args(**kwargs))

    def legacy_install(self):
        # Seed an already installed v1 pin, not a new installation via the v2 installer.
        (self.source/BUNDLE/"assets/integration.json").unlink()
        (self.source/BUNDLE/"scripts/integration_contract.py").unlink()
        write(self.source,"docs/kernel-contract.schema.json",(ROOT/"docs/kernel-contract.v1.schema.json").read_text())
        sha=commit(self.source,"legacy v1 fixture without capability manifest")
        repository=str(self.source.resolve())
        git(self.target,"submodule","add","--name","ftckb","--",repository,project.SOURCE)
        skill=(self.source/BUNDLE/"assets/project-skill/SKILL.md").read_bytes()
        block=(self.source/BUNDLE/"assets/AGENTS.block.md").read_bytes().rstrip(b"\r\n")
        write(self.target,project.SKILL,skill.decode())
        write(self.target,"AGENTS.md",(self.target/"AGENTS.md").read_text()+"\n"+block.decode()+"\n")
        config={"schemaVersion":1,"kernelSchemaVersion":1,"integrationVersion":1,"team":"16093","season":"2025-2026",
                "source":{"repository":repository,"ref":sha,"commit":sha,"path":project.SOURCE},
                "managedFiles":{project.SKILL:project.digest(skill),"AGENTS.md#ftckb":project.digest(block)}}
        write(self.target,project.CONFIG,json.dumps(config,ensure_ascii=False,indent=2)+"\n")
        return sha

    def publish_v2_fixture(self):
        write(self.source,str(BUNDLE/"assets/integration.json"),(ROOT/BUNDLE/"assets/integration.json").read_text())
        write(self.source,str(BUNDLE/"scripts/integration_contract.py"),(ROOT/BUNDLE/"scripts/integration_contract.py").read_text())
        write(self.source,"docs/kernel-contract.schema.json",(ROOT/"docs/kernel-contract.schema.json").read_text())
        return commit(self.source,"v2 upgrade fixture")

    def test_existing_v1_pin_is_retained_without_implicit_upgrade(self):
        sha=self.legacy_install()
        self.assertEqual(0,verify.verify(self.target)[0])
        self.publish_v2_fixture()
        before=(self.target/project.CONFIG).read_bytes()
        code,result=self.install(team=None,season=None,ref=None,repository=None,profile=None,generic_profile=False)
        self.assertEqual(0,code)
        self.assertEqual(sha,result["commit"])
        self.assertEqual(1,result["kernelSchemaVersion"])
        self.assertNotIn("profiles",result)
        self.assertEqual(before,(self.target/project.CONFIG).read_bytes())
        self.assertEqual(sha,git(self.target/project.SOURCE,"rev-parse","HEAD"))

    def test_v1_to_v2_upgrade_requires_explicit_choice(self):
        self.legacy_install()
        sha=self.publish_v2_fixture()
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"profile"):
            self.install(ref=sha,profile=None,generic_profile=False)
        self.assertEqual(before,snapshot(self.target))
        code,plan=self.install(ref=sha,profile=["command-based"],generic_profile=False,dry_run=True)
        self.assertEqual(0,code)
        self.assertEqual(["command-based"],plan["profiles"])
        self.assertEqual(before,snapshot(self.target))
        code,result=self.install(ref=sha,profile=["command-based"],generic_profile=False)
        self.assertEqual(0,code)
        self.assertEqual(sha,result["commit"])
        self.assertEqual(2,result["schemaVersion"])
        self.assertEqual(["command-based"],result["profiles"])

    def test_v1_rejects_profile_selection_instead_of_ignoring_it(self):
        self.legacy_install()
        for values in ({"profile":["command-based"],"generic_profile":False},
                       {"profile":None,"generic_profile":True}):
            with self.subTest(values=values):
                before=snapshot(self.target)
                with self.assertRaisesRegex(project.IntegrationError,"v1"):
                    self.install(ref=None,**values)
                self.assertEqual(before,snapshot(self.target))

    def test_v2_upgrade_preserves_user_choice_when_omitted(self):
        self.install(profile=["ftclib-command"],generic_profile=False)
        write(self.source,"knowledge/.keep","new release fixture\n")
        sha=commit(self.source,"next v2 release")
        code,result=self.install(ref=sha,profile=None,generic_profile=False)
        self.assertEqual(0,code)
        self.assertEqual(["ftclib-command"],result["profiles"])
        self.assertEqual(["ftclib-command"],project.load_config(self.target)["profiles"])
        self.assertEqual(["command-based","ftclib-command"],result["checks"]["resolve"]["output"]["profiles"])

    def test_invalid_capability_manifest_is_rejected_before_mutation(self):
        for manifest in ("null","[]",'{}',
                         '{"projectSchemaVersion":2,"kernelSchemaVersion":1,"integrationVersion":2}'):
            with self.subTest(manifest=manifest):
                write(self.source,str(BUNDLE/"assets/integration.json"),manifest)
                sha=commit(self.source,"invalid manifest fixture")
                before=snapshot(self.target)
                with self.assertRaisesRegex(project.IntegrationError,"manifest|[Pp]rotocol"):
                    self.install(ref=sha,dry_run=True)
                self.assertEqual(before,snapshot(self.target))

    def test_config_protocol_must_match_the_pinned_capability_manifest(self):
        self.install()
        config=project.load_config(self.target)
        config.update(schemaVersion=1,kernelSchemaVersion=1,integrationVersion=1)
        config.pop("profiles")
        write(self.target,project.CONFIG,json.dumps(config))
        with self.assertRaisesRegex(project.IntegrationError,"capabilit"):
            project.check_pin(self.target,project.load_config(self.target))

    def test_v2_revision_requires_shared_protocol_helper_before_install(self):
        (self.source/BUNDLE/"scripts/integration_contract.py").unlink()
        sha=commit(self.source,"incomplete bundle fixture")
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"integration_contract.py"):
            self.install(ref=sha,dry_run=True)
        self.assertEqual(before,snapshot(self.target))

    def test_legacy_capabilities_do_not_silently_downgrade_v2_config(self):
        self.install()
        (self.source/BUNDLE/"assets/integration.json").unlink()
        write(self.source,"docs/kernel-contract.schema.json",(ROOT/"docs/kernel-contract.v1.schema.json").read_text())
        sha=commit(self.source,"legacy revision fixture")
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"downgrade"):
            self.install(ref=sha,profile=None,generic_profile=False,dry_run=True)
        self.assertEqual(before,snapshot(self.target))

    def test_new_generic_install_uses_v2_and_reports_explicit_profiles(self):
        code,result=self.install()
        self.assertEqual(0,code)
        config=project.load_config(self.target)
        for field in ("schemaVersion","kernelSchemaVersion","integrationVersion"):
            self.assertEqual(2,config[field])
            self.assertEqual(2,result[field])
        self.assertEqual([],config["profiles"])
        self.assertEqual([],result["profiles"])
        for name in ("resolve","check"):
            self.assertEqual([],result["checks"][name]["output"]["profiles"])

    def test_cli_profile_install_preserves_selection_and_normalizes_response(self):
        for flags,selected,normalized in ((["--profile","command-based"],["command-based"],["command-based"]),
                                          (["--profile","ftclib-command","--profile","command-based"],
                                           ["ftclib-command","command-based"],["command-based","ftclib-command"]),
                                          (["--profile","rookiebot"],["rookiebot"],["rookiebot","simple-opmode"]),
                                          (["--generic-profile"],[],[])):
            with self.subTest(flags=flags):
                result=command(sys.executable,ROOT/BUNDLE/"scripts/integrate.py","--project",self.target,
                               "--team","16093","--season","2025-2026","--repository",self.source,
                               "--ref","v-test",*flags)
                payload=json.loads(result.stdout)
                self.assertEqual(selected,project.load_config(self.target)["profiles"])
                self.assertEqual(selected,payload["profiles"])
                for name in ("resolve","check"):
                    self.assertEqual(normalized,payload["checks"][name]["output"]["profiles"])

    def test_invalid_or_missing_profile_choice_fails_before_mutation(self):
        for values in ({"profile":None,"generic_profile":False},
                       {"profile":["unknown"],"generic_profile":False},
                       {"profile":["rookiebot","rookiebot"],"generic_profile":False},
                       {"profile":["simple-opmode","command-based"],"generic_profile":False},
                       {"profile":["rookiebot","ftclib-command"],"generic_profile":False},
                       {"profile":["command-based"],"generic_profile":True}):
            with self.subTest(values=values):
                before=snapshot(self.target)
                with self.assertRaises(project.IntegrationError):
                    self.install(**values)
                self.assertEqual(before,snapshot(self.target))

    def test_dry_run_does_not_touch_files_index_or_config(self):
        before=snapshot(self.target)
        code,plan=self.install(dry_run=True)
        self.assertEqual(0,code)
        self.assertEqual(self.sha,plan["commit"])
        self.assertEqual("add",plan["submodule"])
        self.assertEqual(before,snapshot(self.target))
        self.assertFalse((self.target/project.SOURCE).exists())

    def test_installed_dry_run_does_not_refresh_submodule_index(self):
        self.install()
        keep=self.target/project.SOURCE/"knowledge/.keep"
        stat=keep.stat()
        os.utime(keep,ns=(stat.st_atime_ns,stat.st_mtime_ns+1_000_000_000))
        before=snapshot(self.target)
        code,plan=self.install(team=None,season=None,ref=None,repository=None,dry_run=True)
        self.assertEqual(0,code)
        self.assertEqual("keep",plan["submodule"])
        self.assertEqual(before,snapshot(self.target))

    def test_install_repeat_preserves_user_files_index_head_and_branch(self):
        write(self.target,"TeamCode/user.txt","staged user work\n")
        git(self.target,"add","TeamCode/user.txt")
        staged=git(self.target,"ls-files","--stage","TeamCode/user.txt")
        write(self.target,"TeamCode/user.txt","unstaged user work\n")
        original=(self.target/"AGENTS.md").read_bytes()
        code,result=self.install()
        self.assertEqual(0,code)
        self.assertTrue(result["installationOk"])
        self.assertEqual(self.sha,git(self.target/project.SOURCE,"rev-parse","HEAD"))
        self.assertEqual(staged,git(self.target,"ls-files","--stage","TeamCode/user.txt"))
        self.assertEqual("unstaged user work\n",(self.target/"TeamCode/user.txt").read_text())
        self.assertTrue((self.target/"AGENTS.md").read_bytes().startswith(original))
        self.assertEqual(self.head,git(self.target,"rev-parse","HEAD"))
        self.assertEqual("team-work",git(self.target,"branch","--show-current"))
        diff=git(self.target,"diff","HEAD")
        config=(self.target/project.CONFIG).read_bytes()
        self.assertEqual(0,self.install(team=None,season=None,ref=None,repository=None)[0])
        self.assertEqual(diff,git(self.target,"diff","HEAD"))
        self.assertEqual(config,(self.target/project.CONFIG).read_bytes())
        self.assertEqual(1,(self.target/"AGENTS.md").read_text().count(project.BEGIN))

    def test_missing_identity_and_branch_ref_fail_before_mutation(self):
        for values in ({"team":None},{"season":None},{"team":"0"},{"season":"2025-2027"},{"ref":"team-work"},{"ref":None}):
            with self.subTest(values=values):
                before=snapshot(self.target)
                with self.assertRaises(project.IntegrationError):
                    self.install(**values)
                self.assertEqual(before,snapshot(self.target))

    def test_existing_source_directory_is_not_adopted(self):
        write(self.target,project.SOURCE+"/user.txt","keep")
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"Conflicting existing path"):
            self.install()
        self.assertEqual(before,snapshot(self.target))

    def test_unmanaged_skill_is_not_overwritten(self):
        write(self.target,project.SKILL,"user skill")
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"refusing overwrite"):
            self.install()
        self.assertEqual(before,snapshot(self.target))

    def test_edited_managed_files_are_not_overwritten(self):
        self.install()
        for relative in (project.SKILL,"AGENTS.md"):
            path=self.target/relative
            original=path.read_bytes()
            edited=original+b"\nUser change\n" if relative==project.SKILL else original.replace(b"## FTC Knowledge Bank",b"## User-edited block")
            path.write_bytes(edited)
            before=snapshot(self.target)
            with self.assertRaisesRegex(project.IntegrationError,"refusing overwrite"):
                self.install()
            self.assertEqual(before,snapshot(self.target))
            path.write_bytes(original)

    def test_unmanaged_agents_changes_survive_reinstallation(self):
        self.install()
        path=self.target/"AGENTS.md"
        with path.open("ab") as stream:
            stream.write("\n## 队伍补充\n保留。\n".encode())
        before=path.read_bytes()
        self.install()
        self.assertEqual(before,path.read_bytes())

    def test_duplicate_markers_fail_before_any_write(self):
        write(self.target,"AGENTS.md",project.BEGIN+"\n"+project.BEGIN+"\n"+project.END)
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"markers"):
            self.install()
        self.assertEqual(before,snapshot(self.target))

    def test_symlink_cannot_write_outside_project(self):
        outside=self.base/"outside"
        outside.mkdir()
        (self.target/".agents").symlink_to(outside,target_is_directory=True)
        with self.assertRaisesRegex(project.IntegrationError,"symlink"):
            self.install()
        self.assertEqual([],list(outside.iterdir()))

    def test_dirty_gitmodules_is_preserved(self):
        write(self.target,".gitmodules","# unrelated user work\n")
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"user changes"):
            self.install()
        self.assertEqual(before,snapshot(self.target))

    def test_build_failure_is_retryable(self):
        with patch.dict(os.environ,{"FTCKB_TEST_BUILD_FAIL":"1"}):
            with self.assertRaisesRegex(project.IntegrationError,"CLI build failed"):
                self.install()
        self.assertTrue((self.target/project.CONFIG).exists())
        self.assertEqual(0,self.install()[0])

    def test_hard_violation_is_not_an_installation_failure(self):
        write(self.target,"build.common.gradle","pre-existing user change\n")
        code,result=self.install()
        self.assertEqual(1,code)
        self.assertTrue(result["installationOk"])
        self.assertFalse(result["projectCheck"]["ok"])
        self.assertTrue(result["projectCheck"]["soft"])
        self.assertEqual("not-performed",result["robotValidation"])

    def test_explicit_upgrade_changes_pin_and_templates(self):
        self.install()
        write(self.source,str(BUNDLE/"assets/project-skill/SKILL.md"),
              (self.source/BUNDLE/"assets/project-skill/SKILL.md").read_text()+"\nRelease note.\n")
        sha=commit(self.source,"upgrade")
        git(self.source,"tag","v-next")
        code,result=self.install(ref="v-next")
        self.assertEqual(0,code)
        self.assertEqual(sha,result["commit"])
        self.assertIn("Release note.",(self.target/project.SKILL).read_text())
        self.assertEqual(self.head,git(self.target,"rev-parse","HEAD"))

    def test_fresh_clone_can_initialize_and_verify(self):
        self.install()
        commit(self.target,"publish integration in fixture only")
        clone=self.base/"fresh clone"
        command("git","clone","--quiet",self.target,clone)
        code,_=self.install(project=str(clone),ref=None,team=None,season=None,repository=None)
        self.assertEqual(0,code)
        self.assertEqual(self.sha,git(clone/project.SOURCE,"rev-parse","HEAD"))

    def test_local_knowledge_edits_and_wrong_gitlink_block_runtime(self):
        self.install()
        path=self.target/project.SOURCE/"knowledge/.keep"
        path.write_text("local rule modification")
        with self.assertRaisesRegex(project.IntegrationError,"local changes"):
            verify.verify(self.target)
        path.write_text("")
        git(self.target,"update-index","--cacheinfo","160000,"+self.head+","+project.SOURCE)
        with self.assertRaisesRegex(project.IntegrationError,"gitlink"):
            verify.verify(self.target)

    def test_kernel_checks_schema_exit_and_identity_together(self):
        self.install()
        config=project.load_config(self.target)
        source=self.target/project.SOURCE
        valid={"schemaVersion":2,"command":"check","team":"16093","season":"2025-2026",
               "profiles":[],"ok":True,"violations":[],"soft":[]}
        for changes,code in (({},1),({"schemaVersion":1},0),({"team":"20827"},0),({"ok":False},1),({"soft":"not an array"},0)):
            payload=valid|changes
            completed=subprocess.CompletedProcess([],code,json.dumps(payload),"")
            with self.subTest(changes=changes,code=code),patch.object(project,"run",return_value=completed):
                with self.assertRaises(project.IntegrationError):
                    project.kernel(source,config,"check",self.target)

    def test_kernel_rejects_different_or_unnormalized_profiles(self):
        self.install(profile=["rookiebot"],generic_profile=False)
        config=project.load_config(self.target)
        source=self.target/project.SOURCE
        for name in ("resolve","check"):
            _,valid=project.kernel(source,config,name,self.target)
            for profiles in ([],["command-based"],["rookiebot"],["simple-opmode","rookiebot"]):
                response=subprocess.CompletedProcess([],0,json.dumps(valid|{"profiles":profiles}),"")
                with self.subTest(command=name,profiles=profiles),patch.object(project,"run",return_value=response):
                    with self.assertRaisesRegex(project.IntegrationError,"profiles"):
                        project.kernel(source,config,name,self.target)

    def test_wrapper_returns_kernel_json_without_plan_noise(self):
        self.install()
        result=command(sys.executable,ROOT/BUNDLE/"scripts/project.py","resolve","--project",self.target)
        payload=json.loads(result.stdout)
        self.assertEqual("resolve",payload["command"])
        self.assertEqual("16093",payload["team"])

    def test_project_adapter_forwards_explicit_work_modes_without_touching_config(self):
        self.install()
        before=(self.target/project.CONFIG).read_bytes()
        patch_file=self.base/"change.patch"
        patch_file.write_text("diff --git a/TeamCode/New.java b/TeamCode/New.java\n"
                              "new file mode 100644\n"
                              "--- /dev/null\n"
                              "+++ b/TeamCode/New.java\n"
                              "@@ -0,0 +1,1 @@\n"
                              "+class New {}\n",encoding="utf-8")
        for name,mode,extra in (("resolve","test",[]),("check","test",[]),
                                ("check","dev",["--diff",str(patch_file)])):
            with self.subTest(command=name,mode=mode):
                result=command(sys.executable,ROOT/BUNDLE/"scripts/project.py",name,
                               "--project",self.target,"--work-mode",mode,*extra)
                payload=json.loads(result.stdout)
                self.assertEqual(mode,payload["workMode"])
                self.assertNotIn("workMode",json.loads(command(
                    sys.executable,ROOT/BUNDLE/"scripts/project.py",name,"--project",self.target,*extra).stdout))
        self.assertEqual(before,(self.target/project.CONFIG).read_bytes())

    def test_kernel_omits_work_mode_by_default_and_requires_the_echoed_mode(self):
        self.install()
        config=project.load_config(self.target)
        source=self.target/project.SOURCE
        patch_file=self.base/"change.patch"
        patch_file.write_text("diff --git a/TeamCode/New.java b/TeamCode/New.java\n"
                              "new file mode 100644\n"
                              "--- /dev/null\n"
                              "+++ b/TeamCode/New.java\n"
                              "@@ -0,0 +1,1 @@\n"
                              "+class New {}\n",encoding="utf-8")
        captured=[]
        valid={"schemaVersion":2,"command":"check","team":"16093","season":"2025-2026",
               "profiles":[],"ok":True,"violations":[],"soft":[]}

        def fake_run(args,cwd=None,check=True):
            captured.append([str(value) for value in args])
            payload=dict(valid)
            if "--work-mode" in captured[-1]:
                payload["workMode"]=captured[-1][captured[-1].index("--work-mode")+1]
            return subprocess.CompletedProcess([],0,json.dumps(payload),"")

        with patch.object(project,"run",side_effect=fake_run):
            self.assertEqual(0,project.kernel(source,config,"check",self.target)[0])
            self.assertNotIn("--work-mode",captured[-1])
            self.assertEqual(0,project.kernel(source,config,"check",self.target,
                                              diff=str(patch_file),work_mode="dev")[0])
            self.assertEqual("dev",captured[-1][captured[-1].index("--work-mode")+1])

        with patch.object(project,"run",return_value=subprocess.CompletedProcess([],0,json.dumps(valid),"")):
            with self.assertRaisesRegex(project.IntegrationError,"work mode"):
                project.kernel(source,config,"check",self.target,
                               diff=str(patch_file),work_mode="dev")

    def test_project_adapter_refuses_dev_without_diff_and_validate_with_work_mode(self):
        self.install()
        before=snapshot(self.target)
        for argv,expected in (
            (["check","--project",str(self.target),"--work-mode","dev"],"--diff"),
            (["validate","--project",str(self.target),"--work-mode","test"],"resolve and check"),
        ):
            with self.subTest(argv=argv):
                output=io.StringIO()
                with contextlib.redirect_stdout(output):
                    code=project.main(argv)
                self.assertEqual(2,code)
                payload=json.loads(output.getvalue())
                self.assertFalse(payload["integrationOk"])
                self.assertIn(expected,payload["error"])
        self.assertEqual(before,snapshot(self.target))
        with self.assertRaises(SystemExit):
            with contextlib.redirect_stderr(io.StringIO()):
                project.main(["check","--project",str(self.target),"--work-mode","fast"])

    def test_windows_launcher_uses_batch_file(self):
        result=project.launcher(Path("C:/Team Project/tools/FTC-Knowledge-Bank"),windows=True)
        self.assertEqual(1,len(result))
        self.assertTrue(result[0].endswith("ftckb.bat"))

    def test_non_directory_parent_is_rejected_before_mutation(self):
        write(self.target,".agents","user file, not a directory")
        before=snapshot(self.target)
        with self.assertRaisesRegex(project.IntegrationError,"not a directory"):
            self.install()
        self.assertEqual(before,snapshot(self.target))

    def test_annotated_tag_resolves_to_commit_not_tag_object(self):
        git(self.source,"tag","-a","v-annotated","-m","Release fixture")
        self.assertEqual(self.sha,integrate.resolve_ref(str(self.source),"v-annotated"))

    def test_all_kernel_fixtures_validate_against_schema(self):
        from jsonschema import Draft7Validator
        # Historical v1 fixtures remain valid against their archived contract.
        schemas={1:Draft7Validator(json.loads((ROOT/"docs/kernel-contract.v1.schema.json").read_text())),
                 2:project.validator(ROOT)}
        paths=list((ROOT/"fixtures/kernel").glob("*.json"))
        self.assertGreaterEqual(len(paths),10)
        for path in paths:
            with self.subTest(path=path.name):
                payload=json.loads(path.read_text())
                self.assertIn(payload["schemaVersion"],schemas)
                schemas[payload["schemaVersion"]].validate(payload)

    @unittest.skipUnless(os.environ.get("FTCKB_REAL_INTEGRATION")=="1","Set FTCKB_REAL_INTEGRATION=1 for a real pinned-source Gradle build and CLI run")
    def test_real_cli_from_pinned_source(self):
        # Snapshot only tracked source plus this feature's bundle, never unrelated user files.
        for relative in git(ROOT,"ls-files","-z").split("\0"):
            if not relative:
                continue
            original=ROOT/relative
            if not original.is_file():
                continue
            destination=self.source/relative
            destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(original,destination)
        sha=commit(self.source,"isolated current source for real CLI integration")
        code,result=self.install(ref=sha)
        self.assertEqual(0,code,result)
        self.assertTrue(result["installationOk"])
        self.assertGreater(len(result["checks"]["resolve"]["output"]["activeRules"]),0)
        self.assertEqual("16093",result["checks"]["resolve"]["output"]["team"])
        write(self.target,"build.common.gradle","// intentional forbidden change in disposable fixture\n")
        config=project.load_config(self.target)
        code,payload=project.kernel(self.target/project.SOURCE,config,"check",self.target)
        self.assertEqual(1,code,payload)
        self.assertIn("official.keep-customizations-in-teamcode",[v["ruleId"] for v in payload["violations"]])


class DoctorTest(unittest.TestCase):
    setUp=IntegrationTest.setUp
    args=IntegrationTest.args
    install=IntegrationTest.install
    legacy_install=IntegrationTest.legacy_install

    def checks(self,payload):
        return {item["code"]:item for item in payload["checks"]}

    def config(self,**changes):
        path=self.target/project.CONFIG
        value=json.loads(path.read_text()); value.update(changes)
        path.write_text(json.dumps(value))
        return value

    def call(self,*args):
        return subprocess.run([sys.executable,str(ROOT/BUNDLE/"scripts/project.py"),"doctor",*map(str,args)],
                              capture_output=True,text=True,timeout=20)

    def test_missing_config_is_classified_and_contract_is_stable(self):
        before=snapshot(self.target)
        result=self.call("--project",self.target)
        self.assertEqual(1,result.returncode,result.stderr)
        payload=json.loads(result.stdout)
        self.assertEqual(1,payload["doctorSchemaVersion"])
        self.assertEqual("doctor",payload["command"])
        self.assertNotIn("schemaVersion",payload)
        self.assertIn("missing",self.checks(payload)["config.read"]["message"])
        self.assertEqual("blocked",self.checks(payload)["source.pin"]["status"])
        self.assertTrue(all(item["status"] in {"passed","failed","blocked","unknown","not_checked"} for item in payload["checks"]))
        self.assertFalse(any("argv" in item for item in payload["nextSteps"]))
        self.assertEqual(before,snapshot(self.target))

    def test_bad_and_duplicate_json_are_malformed(self):
        for value in ('{bad','{"team":"secret","team":"other"}','[]'):
            with self.subTest(value=value):
                write(self.target,project.CONFIG,value)
                payload=doctor.diagnose(self.target)
                self.assertIn("malformed",self.checks(payload)["config.read"]["message"])
                self.assertNotIn("secret",json.dumps(payload))

    def test_four_independent_errors_are_aggregated_without_secrets(self):
        self.install()
        value=self.config(team="https://user:password@example.invalid/#token=SECRET",season="2025-2028",profiles=["SECRET"])
        value["source"].update(commit="SECRET",repository="https://SECRET.invalid",ref="SECRET")
        write(self.target,project.CONFIG,json.dumps(value))
        payload=doctor.diagnose(self.target); checks=self.checks(payload)
        for code in ("config.team","config.season","config.profiles","config.commit"):
            self.assertEqual("failed",checks[code]["status"],code)
        self.assertNotIn("SECRET",json.dumps(payload))
        self.assertIsNone(payload["context"]["team"])
        self.assertFalse(any("argv" in step for step in payload["nextSteps"]))

    def test_v2_context_keeps_selection_and_expands_profiles(self):
        for selected,expected in (([],[]),(["rookiebot"],["rookiebot","simple-opmode"]),(["ftclib-command"],["command-based","ftclib-command"])):
            with self.subTest(selected=selected):
                self.install(profile=selected or None,generic_profile=not selected)
                payload=doctor.diagnose(self.target)
                self.assertEqual(selected,payload["context"]["profiles"])
                self.assertEqual(expected,payload["context"]["normalizedProfiles"])
                self.assertEqual("passed",self.checks(payload)["source.pin"]["status"])

    def test_v1_does_not_invent_generic_profiles(self):
        self.legacy_install()
        payload=doctor.diagnose(self.target)
        self.assertIsNone(payload["context"]["profiles"])
        self.assertIsNone(payload["context"]["normalizedProfiles"])
        self.assertEqual(1,payload["context"]["protocol"]["schemaVersion"])

    def test_invalid_root_nonroot_and_unborn_are_failures(self):
        unborn=self.base/"unborn"; initialize(unborn)
        for path in (self.base/"absent",self.target/"TeamCode",unborn):
            with self.subTest(path=path):
                self.assertEqual("failed",self.checks(doctor.diagnose(path))["project.git-root"]["status"])

    def test_uninitialized_submodule_has_exact_safe_next_step(self):
        self.install(); commit(self.target)
        clone=self.base/"clone"; command("git","clone","--quiet",self.target,clone)
        payload=doctor.diagnose(clone)
        self.assertEqual("failed",self.checks(payload)["source.initialized"]["status"])
        step=next(s for s in payload["nextSteps"] if s["code"]=="initialize")
        self.assertEqual(str(clone.resolve()),step["cwd"])
        self.assertEqual(["git","submodule","update","--init","--",project.SOURCE],step["argv"])
        git(clone,"config","--file",".gitmodules","submodule.ftckb.url","mismatch")
        self.assertFalse(any(s["code"]=="initialize" for s in doctor.diagnose(clone)["nextSteps"]))

    def test_dirty_pin_and_gitlink_checks_remain_independent(self):
        self.install()
        write(self.target/project.SOURCE,"knowledge/.keep","dirty")
        value=self.config(); value["source"]["commit"]="0"*40
        write(self.target,project.CONFIG,json.dumps(value))
        checks=self.checks(doctor.diagnose(self.target))
        for code in ("source.clean","source.pin","source.gitlink"):
            self.assertEqual("failed",checks[code]["status"])

    def test_managed_presence_survives_bad_receipt_and_symlinks_are_rejected(self):
        self.install(); self.config(managedFiles={})
        (self.target/project.SKILL).unlink()
        (self.target/project.SKILL).symlink_to(self.source/BUNDLE/"SKILL.md")
        checks=self.checks(doctor.diagnose(self.target))
        self.assertEqual("failed",checks["managed.skill"]["status"])
        self.assertEqual("passed",checks["managed.agents"]["status"])
        self.assertEqual("blocked",checks["managed.agents-digest"]["status"])

    def test_managed_hashes_and_markers_report_separately(self):
        self.install()
        write(self.target,project.SKILL,"modified")
        write(self.target,"AGENTS.md",project.BEGIN+project.BEGIN+project.END)
        checks=self.checks(doctor.diagnose(self.target))
        self.assertEqual("failed",checks["managed.skill-digest"]["status"])
        self.assertEqual("failed",checks["managed.agents"]["status"])

    def test_distribution_receipt_and_provenance_are_distinct(self):
        self.install(); source=self.target/project.SOURCE
        checks=self.checks(doctor.diagnose(self.target))
        self.assertEqual("failed",checks["cli.distribution"]["status"])
        write(source,"apps/knowledge-cli/build/install/ftckb/lib/fixture.jar","static fixture")
        self.assertEqual("failed",self.checks(doctor.diagnose(self.target))["cli.distribution"]["status"])
        write(source,"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar","main entry fixture")
        checks=self.checks(doctor.diagnose(self.target))
        self.assertEqual("passed",checks["cli.distribution"]["status"])
        self.assertEqual("passed",checks["cli.receipt"]["status"])
        self.assertEqual("unknown",checks["cli.provenance"]["status"])
        (source/"apps/knowledge-cli/build/ftckb-integration-commit").unlink()
        self.assertEqual("failed",self.checks(doctor.diagnose(self.target))["cli.receipt"]["status"])

    def test_default_doctor_does_not_inspect_or_recommend_dashboard(self):
        self.install()
        original=doctor.safe_path
        def guarded(source,relative):
            if relative.endswith(("Main.kt","DashboardCommand.kt")):
                raise AssertionError("Dashboard capability must not be inspected")
            return original(source,relative)
        with patch.object(doctor,"safe_path",side_effect=guarded):
            payload=doctor.diagnose(self.target)
        self.assertEqual("not_checked",self.checks(payload)["source.dashboard"]["status"])
        self.assertEqual("not_checked",self.checks(payload)["dashboard.connection"]["status"])
        self.assertFalse(any("dashboard" in step["code"] for step in payload["nextSteps"]))
        workflow=next(step for step in payload["nextSteps"] if step["code"]=="workflow")
        self.assertIn("work resolve",workflow["message"])
        self.assertIn("returned receipt",workflow["message"])

    def test_usage_errors_are_json_exit_64(self):
        for args in ([],["--project",str(self.target),"--secret-url=SECRET"],["--project"]):
            result=self.call(*args)
            self.assertEqual(64,result.returncode)
            payload=json.loads(result.stdout)
            self.assertEqual("usage",payload["error"]["code"])
            self.assertNotIn("SECRET",result.stdout+result.stderr)

    def test_safe_git_timeouts_and_stderr_are_not_echoed(self):
        for effect in (subprocess.TimeoutExpired(["git"],1,stderr="SECRET"),OSError("SECRET")):
            with self.subTest(effect=effect),patch.object(doctor.subprocess,"run",side_effect=effect):
                payload=doctor.diagnose(self.target)
                self.assertEqual("failed",self.checks(payload)["project.git-root"]["status"])
                self.assertNotIn("SECRET",json.dumps(payload))
        with patch.object(doctor.subprocess,"run",return_value=subprocess.CompletedProcess([],1,"","SECRET")):
            with self.assertRaises(doctor.ProbeError):
                doctor.git(self.target,"rev-parse","HEAD")
        with self.assertRaises(doctor.ProbeError):
            doctor.git(self.target,"fetch")

    def test_readonly_including_git_and_no_fsmonitor_build_or_kernel(self):
        self.install()
        sentinel=self.base/"executed"
        script=self.base/"fsmonitor"
        script.write_text('#!/bin/sh\ntouch "'+str(sentinel)+'"\n'); script.chmod(0o755)
        git(self.target,"config","core.fsmonitor",str(script))
        git(self.target/project.SOURCE,"config","core.fsmonitor",str(script))
        for relative in ("gradlew","apps/knowledge-cli/build/install/ftckb/bin/ftckb"):
            write(self.target/project.SOURCE,relative,'#!/bin/sh\ntouch "'+str(sentinel)+'"\n')
        before=snapshot(self.target)
        with patch.object(project,"build_cli",side_effect=AssertionError("build")),patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            payload=doctor.diagnose(self.target)
        self.assertEqual(before,snapshot(self.target))
        self.assertFalse(sentinel.exists())
        self.assertEqual("not_checked",self.checks(payload)["kernel.resolve"]["status"])

    def test_complete_static_fixture_can_pass_without_claiming_runtime(self):
        self.install()
        write(self.target/project.SOURCE,"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar","fixture only")
        with patch.object(doctor,"jdk_status",return_value="passed"):
            payload=doctor.diagnose(self.target)
        self.assertTrue(payload["ok"])
        self.assertEqual("unknown",self.checks(payload)["cli.provenance"]["status"])
        self.assertEqual("not_checked",self.checks(payload)["dashboard.connection"]["status"])
        output=io.StringIO()
        with patch.object(doctor,"diagnose",return_value=payload),contextlib.redirect_stdout(output):
            self.assertEqual(0,project.main(["doctor","--project",str(self.target)]))
        self.assertTrue(json.loads(output.getvalue())["ok"])

    def test_jdk_queries_both_versions_and_drops_agent_options(self):
        results=[subprocess.CompletedProcess([],0,"",'java version "25"'),
                 subprocess.CompletedProcess([],0,"javac 8","")]
        with patch.object(doctor,"executable",side_effect=lambda name,*args:"/tools/"+name),patch.object(doctor.subprocess,"run",side_effect=results) as runner,patch.dict(os.environ,{"JAVA_TOOL_OPTIONS":"SECRET"}):
            self.assertEqual("unknown",doctor.jdk_status())
            self.assertEqual([["/tools/java","-version"],["/tools/javac","-version"]],[call.args[0] for call in runner.call_args_list])
            self.assertNotIn("JAVA_TOOL_OPTIONS",runner.call_args.kwargs["env"])

    def test_java_home_is_authoritative_and_target_tools_are_never_executed(self):
        marker=self.base/"tool-executed"
        for tool in ("java","javac","git"):
            path=self.target/"bin"/tool
            write(self.target,"bin/"+tool,'#!/bin/sh\nprintf x > "'+str(marker)+'"\necho tool-ran\n')
            path.chmod(0o755)
        with patch.dict(os.environ,{"PATH":str(self.target/"bin"),"JAVA_HOME":str(self.base/"missing-jdk")}):
            self.assertEqual("unknown",doctor.jdk_status(self.target))
            with self.assertRaises(doctor.ProbeError):
                doctor.git(self.target,"rev-parse","HEAD")
        with patch.dict(os.environ,{"PATH":str(self.target/"bin"),"JAVA_HOME":""}):
            self.assertEqual("unknown",doctor.jdk_status(self.target))
        with patch.dict(os.environ,{"JAVA_HOME":str(self.target)}):
            self.assertEqual("unknown",doctor.jdk_status(self.target))
        self.assertFalse(marker.exists())

    def test_failed_git_root_never_executes_target_java(self):
        marker=self.base/"java-executed"
        other=self.base/"not-git"; other.mkdir()
        for target in (other,self.target):
            for name in ("git","java","javac"):
                tool=target/"bin"/name
                write(target,"bin/"+name,'#!/bin/sh\nprintf x > "'+str(marker)+'"\nexit 1\n')
                tool.chmod(0o755)
            with patch.dict(os.environ,{"PATH":str(target/"bin"),"JAVA_HOME":""}):
                payload=doctor.diagnose(target)
            self.assertEqual("failed",self.checks(payload)["project.git-root"]["status"])
            self.assertEqual("blocked",self.checks(payload)["environment.jdk"]["status"])
        self.assertFalse(marker.exists())

    def test_git_tool_symlink_into_target_is_rejected(self):
        tool=self.target/"bin/git"
        write(self.target,"bin/git","#!/bin/sh\nexit 0\n"); tool.chmod(0o755)
        alias=self.base/"tool-alias"; alias.mkdir(); (alias/"git").symlink_to(tool)
        with patch.dict(os.environ,{"PATH":str(alias)}):
            with self.assertRaises(doctor.ProbeError):
                doctor.git(self.target,"rev-parse","HEAD")

    def test_jar_directory_is_not_a_distribution(self):
        self.install(); source=self.target/project.SOURCE
        (source/"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar").mkdir(parents=True)
        self.assertEqual("failed",self.checks(doctor.diagnose(self.target))["cli.distribution"]["status"])

    def test_windows_missing_distribution_has_no_protected_dashboard_argv(self):
        write(self.source,"apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt",'if (args.firstOrNull()=="dashboard") return runDashboardCommand(args.drop(1),out)')
        write(self.source,"apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/DashboardCommand.kt",'fun runDashboardCommand(args:List<String>) {}')
        self.sha=commit(self.source); self.install(ref=self.sha)
        windows=SimpleNamespace(name="nt",environ=os.environ,access=os.access,X_OK=os.X_OK,devnull=os.devnull)
        with patch.object(doctor,"os",windows):
            payload=doctor.diagnose(self.target)
        self.assertEqual("failed",self.checks(payload)["cli.distribution"]["status"])
        self.assertFalse(any("dashboard" in step["code"] for step in payload["nextSteps"]))

    def test_git_runner_disables_helpers_and_does_not_inherit_git_overrides(self):
        with patch.dict(os.environ,{"GIT_CONFIG_COUNT":"1","GIT_CONFIG_KEY_0":"core.fsmonitor","GIT_CONFIG_VALUE_0":"SECRET"}),patch.object(doctor.subprocess,"run",return_value=subprocess.CompletedProcess([],0,"ok","")) as runner:
            doctor.git(self.target,"rev-parse","HEAD")
            args=runner.call_args.args[0]; opts=runner.call_args.kwargs
            self.assertIn("core.fsmonitor=false",args)
            self.assertIn("--no-optional-locks",args)
            self.assertNotIn("GIT_CONFIG_COUNT",opts["env"])
            self.assertEqual("",opts["env"]["GIT_ALLOW_PROTOCOL"])
            self.assertEqual(doctor.TIMEOUT,opts["timeout"])

    def test_templates_have_cli_only_doctor_resolve_order(self):
        for relative in ("assets/project-skill/SKILL.md","assets/AGENTS.block.md"):
            text=(ROOT/BUNDLE/relative).read_text()
            self.assertIn("doctor → work resolve → 修改 → work check --receipt ID",text)
            self.assertNotIn("doctor → Dashboard 已连接",text)


class WorkReceiptTest(unittest.TestCase):
    setUp=IntegrationTest.setUp
    args=IntegrationTest.args
    legacy_install=IntegrationTest.legacy_install

    def install(self,**kwargs):
        result=IntegrationTest.install(self,**kwargs)
        write(self.target/project.SOURCE,"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar","fixture main jar")
        return result

    def call(self,*args):
        return subprocess.run([sys.executable,str(ROOT/BUNDLE/"scripts/project.py"),"work",*map(str,args)],
                              capture_output=True,text=True,timeout=30)

    config=DoctorTest.config

    def invoke(self,*args):
        stream=io.StringIO()
        with contextlib.redirect_stdout(stream):
            code=project.main(["work",*args,"--project",str(self.target)])
        return code,json.loads(stream.getvalue())

    def resolve(self,mode="normal"):
        code,payload=self.invoke("resolve","--work-mode",mode)
        self.assertEqual(0,code,payload)
        return payload

    def receipt_path(self,identifier):
        return Path(git(self.target,"rev-parse","--absolute-git-dir"))/"ftckb/receipts"/(identifier+".json")

    def test_work_resolve_creates_explicit_receipt(self):
        self.install()
        result=self.call("resolve","--project",self.target)
        self.assertEqual(0,result.returncode,result.stderr+result.stdout)
        payload=json.loads(result.stdout)
        self.assertEqual(1,payload["workReceiptSchemaVersion"])
        self.assertEqual("work-resolve",payload["command"])
        self.assertEqual("created",payload["receipt"]["status"])
        self.assertEqual("resolve",payload["kernel"]["output"]["command"])

    def test_code_changes_and_repeat_checks_keep_same_receipt(self):
        self.install(); resolved=self.resolve(); identifier=resolved["receipt"]["id"]
        path=self.receipt_path(identifier); original=path.read_bytes()
        before_status=git(self.target,"status","--porcelain")
        write(self.target,"TeamCode/New.java","class New {}\n")
        for _ in range(2):
            code,payload=self.invoke("check","--receipt",identifier)
            self.assertEqual(0,code,payload)
            self.assertEqual("matched",payload["receipt"]["status"])
            self.assertEqual(resolved["receipt"]["resolveOutputDigest"],payload["receipt"]["resolveOutputDigest"])
        self.assertEqual(original,path.read_bytes())
        self.assertNotIn("receipts",git(self.target,"status","--porcelain"))
        self.assertNotIn("receipts",before_status)
        self.assertEqual(0o600,path.stat().st_mode&0o777)
        self.assertEqual(0o700,path.parent.stat().st_mode&0o777)

    def test_independent_resolves_create_distinct_ids_and_complete_next_step(self):
        self.install(); first=self.resolve(); second=self.resolve()
        self.assertNotEqual(first["receipt"]["id"],second["receipt"]["id"])
        self.assertTrue(self.receipt_path(first["receipt"]["id"]).is_file())
        argv=second["nextSteps"][0]["argv"]
        self.assertIn(str((ROOT/BUNDLE/"scripts/project.py").resolve()),argv)
        self.assertEqual(second["receipt"]["id"],argv[argv.index("--receipt")+1])
        self.assertNotIn("latest",list(self.receipt_path(first["receipt"]["id"]).parent.iterdir()))

    def test_parallel_resolves_publish_two_complete_independent_files(self):
        self.install()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:self.call("resolve","--project",self.target),range(2)))
        values=[json.loads(result.stdout) for result in results]
        self.assertTrue(all(result.returncode==0 for result in results),values)
        ids={value["receipt"]["id"] for value in values}
        self.assertEqual(2,len(ids))
        for identifier in ids:
            value=json.loads(self.receipt_path(identifier).read_text())
            self.assertEqual(identifier,work.validate_receipt(value,identifier)["id"])

    def test_relocated_project_cannot_reuse_original_context(self):
        self.install(); identifier=self.resolve()["receipt"]["id"]
        clone=self.base/"relocated"; shutil.copytree(self.target,clone)
        stream=io.StringIO()
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")),contextlib.redirect_stdout(stream):
            code=project.main(["work","check","--project",str(clone),"--receipt",identifier])
        self.assertEqual(2,code)
        self.assertIn("projectRoot",json.loads(stream.getvalue())["error"]["changedFields"])

    def test_configuration_byte_or_context_changes_reject_before_kernel(self):
        self.install(); original=(self.target/project.CONFIG).read_bytes()
        identifier=self.resolve()["receipt"]["id"]
        changes=[{"team":"20827"},{"season":"2026-2027"},{"profiles":["rookiebot"]},None]
        for values in changes:
            (self.target/project.CONFIG).write_bytes(original)
            if values is None:
                with (self.target/project.CONFIG).open("ab") as stream: stream.write(b" ")
            else:
                self.config(**values)
            with self.subTest(values=values),patch.object(project,"kernel",side_effect=AssertionError("kernel must not run")):
                code,payload=self.invoke("check","--receipt",identifier)
                self.assertEqual(2,code)
                self.assertEqual("context-changed",payload["error"]["code"])
                self.assertIn("configSha256",payload["error"]["changedFields"])
                self.assertIsNone(payload["kernel"])

    def test_invalid_protocol_pin_and_source_path_reject_safely(self):
        self.install(); raw=(self.target/project.CONFIG).read_bytes()
        identifier=self.resolve()["receipt"]["id"]
        for values in ({"schemaVersion":1},{"source":{"path":"../SECRET"}}, {"source":{"commit":"0"*40}}):
            value=json.loads(raw)
            if "source" in values: value["source"].update(values["source"])
            else: value.update(values)
            write(self.target,project.CONFIG,json.dumps(value))
            with self.subTest(values=values),patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                code,payload=self.invoke("check","--receipt",identifier)
                self.assertEqual(2,code)
                self.assertIsNone(payload["kernel"])
                self.assertNotIn("SECRET",json.dumps(payload))

    def test_ignored_yaml_add_change_delete_invalidates_actual_input_digest(self):
        self.install(); source=self.target/project.SOURCE
        exclude=Path(git(source,"rev-parse","--absolute-git-dir"))/"info/exclude"
        with exclude.open("a") as stream: stream.write("\nknowledge/ignored.yaml\n")
        path=source/"knowledge/ignored.yaml"
        identifier=self.resolve()["receipt"]["id"]
        for contents in ("[]\n","# changed\n[]\n"):
            path.write_text(contents)
            with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                code,payload=self.invoke("check","--receipt",identifier)
                self.assertEqual(2,code)
                self.assertEqual(["knowledgeDigest"],payload["error"]["changedFields"])
        current=self.resolve()["receipt"]["id"]; path.unlink()
        code,payload=self.invoke("check","--receipt",current)
        self.assertEqual(2,code)
        self.assertEqual(["knowledgeDigest"],payload["error"]["changedFields"])

    def test_knowledge_symlink_and_special_file_are_rejected(self):
        self.install(); source=self.target/project.SOURCE
        exclude=Path(git(source,"rev-parse","--absolute-git-dir"))/"info/exclude"
        with exclude.open("a") as stream: stream.write("\nknowledge/unsafe*\n")
        identifier=self.resolve()["receipt"]["id"]
        unsafe=source/"knowledge/unsafe.yaml"; unsafe.symlink_to(self.target/project.CONFIG)
        code,payload=self.invoke("check","--receipt",identifier)
        self.assertEqual(2,code); self.assertEqual("knowledge-unsafe",payload["error"]["code"])
        unsafe.unlink()
        if hasattr(os,"mkfifo"):
            os.mkfifo(unsafe)
            code,payload=self.invoke("check","--receipt",identifier)
            self.assertEqual(2,code); self.assertEqual("knowledge-unsafe",payload["error"]["code"])

    def test_explicit_modes_do_not_silently_inherit_and_dev_requires_diff(self):
        self.install()
        identifier=self.resolve("test")["receipt"]["id"]
        code,payload=self.invoke("check","--receipt",identifier)
        self.assertEqual(2,code); self.assertEqual(["workMode"],payload["error"]["changedFields"])
        self.assertEqual(0,self.invoke("check","--receipt",identifier,"--work-mode","test")[0])
        dev=self.resolve("dev"); identifier=dev["receipt"]["id"]
        self.assertIn("dev",dev["nextSteps"][0]["argv"])
        self.assertEqual(64,self.invoke("check","--receipt",identifier,"--work-mode","dev")[0])
        diff=self.base/"change.patch"; diff.write_text("fixture diff")
        self.assertEqual(0,self.invoke("check","--receipt",identifier,"--work-mode","dev","--diff",str(diff))[0])

    def test_named_profiles_and_v1_context_are_preserved(self):
        self.install(profile=["rookiebot"],generic_profile=False)
        identifier=self.resolve()["receipt"]["id"]
        value=json.loads(self.receipt_path(identifier).read_text())
        self.assertEqual(["rookiebot"],value["context"]["profiles"])
        self.assertEqual(["rookiebot","simple-opmode"],value["context"]["normalizedProfiles"])

    def test_existing_v1_context_does_not_invent_generic(self):
        self.legacy_install(); config=project.load_config(self.target); source=self.target/project.SOURCE
        with tempfile.TemporaryFile(mode="w+") as logs,contextlib.redirect_stderr(logs):
            project.build_cli(source,config["source"]["commit"])
        write(source,"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar","fixture")
        identifier=self.resolve()["receipt"]["id"]
        value=json.loads(self.receipt_path(identifier).read_text())
        self.assertIsNone(value["context"]["profiles"])
        self.assertEqual(0,self.invoke("check","--receipt",identifier)[0])

    def test_options_before_subcommand_report_actual_command(self):
        self.install(); identifier=self.resolve()["receipt"]["id"]
        result=self.call("--project",self.target,"check","--receipt",identifier)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertEqual("work-check",json.loads(result.stdout)["command"])

    def test_usage_missing_bad_or_path_receipt_id_is_safe_json(self):
        for args in ([],["check","--project",str(self.target)],["check","--project",str(self.target),"--receipt","../SECRET"],
                     ["resolve","--project",str(self.target),"--unknown=SECRET"]):
            result=self.call(*args); self.assertEqual(64,result.returncode)
            payload=json.loads(result.stdout)
            self.assertEqual("usage",payload["error"]["code"])
            self.assertNotIn("SECRET",result.stdout+result.stderr)

    def test_missing_damaged_oversized_duplicate_or_unsupported_receipts(self):
        self.install(); identifier=self.resolve()["receipt"]["id"]; path=self.receipt_path(identifier); original=path.read_bytes()
        for data in (b"{bad",b'{"id":"a","id":"b"}',b"x"*70000,original.replace(b'"workReceiptSchemaVersion":1',b'"workReceiptSchemaVersion":99')):
            path.write_bytes(data)
            with self.subTest(data=data[:30]),patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                code,payload=self.invoke("check","--receipt",identifier)
                self.assertEqual(2,code); self.assertEqual("receipt-invalid",payload["error"]["code"])
        path.unlink()
        self.assertEqual("receipt-invalid",self.invoke("check","--receipt",identifier)[1]["error"]["code"])

    def test_tampered_rehashed_fields_are_validated_not_used_as_paths(self):
        self.install(); identifier=self.resolve()["receipt"]["id"]; path=self.receipt_path(identifier)
        value=json.loads(path.read_text()); value["context"]["knowledgePath"]="SECRET"
        value["integrityDigest"]=work.sha(work.canonical({k:v for k,v in value.items() if k!="integrityDigest"}))
        path.write_bytes(work.canonical(value))
        code,payload=self.invoke("check","--receipt",identifier)
        self.assertEqual(2,code); self.assertEqual("receipt-invalid",payload["error"]["code"])
        self.assertNotIn("SECRET",json.dumps(payload))

    def test_storage_directory_and_receipt_symlinks_are_not_followed(self):
        self.install(); gitdir=Path(git(self.target,"rev-parse","--absolute-git-dir")); outside=self.base/"outside"; outside.mkdir()
        (gitdir/"ftckb").symlink_to(outside,target_is_directory=True)
        code,payload=self.invoke("resolve")
        self.assertEqual(2,code); self.assertEqual("storage-unsafe",payload["error"]["code"])
        self.assertEqual([],list(outside.iterdir()))
        (gitdir/"ftckb").unlink(); identifier=self.resolve()["receipt"]["id"]; path=self.receipt_path(identifier)
        other=outside/"SECRET"; other.write_bytes(path.read_bytes()); path.unlink(); path.symlink_to(other)
        self.assertEqual("receipt-invalid",self.invoke("check","--receipt",identifier)[1]["error"]["code"])

    def test_atomic_publish_and_fsync_failure_leave_no_accepted_file(self):
        self.install(); self.resolve(); folder=self.receipt_path("unused").parent
        before=snapshot(folder)
        with patch.object(work.os,"link",side_effect=OSError("SECRET")):
            code,payload=self.invoke("resolve")
        self.assertEqual(2,code); self.assertEqual(before,snapshot(folder)); self.assertNotIn("SECRET",json.dumps(payload))
        real_fsync=os.fsync
        def fail_directory(fd):
            if __import__("stat").S_ISDIR(os.fstat(fd).st_mode): raise OSError("directory sync")
            return real_fsync(fd)
        with patch.object(work.os,"fsync",side_effect=fail_directory):
            self.assertEqual(2,self.invoke("resolve")[0])
        self.assertEqual(before,snapshot(folder))

    def test_custom_git_directory_visible_in_worktree_is_rejected(self):
        self.install()
        command("git","-C",self.target,"init","--separate-git-dir",self.target/"meta")
        before=snapshot(self.target/"meta")
        code,payload=self.invoke("resolve")
        self.assertEqual(2,code); self.assertEqual("storage-unsafe",payload["error"]["code"])
        self.assertFalse((self.target/"meta/ftckb/receipts").exists())
        self.assertEqual(before,snapshot(self.target/"meta"))

    def test_linked_worktree_uses_its_own_external_git_metadata(self):
        self.install(); commit(self.target,"published integration fixture")
        linked=self.base/"linked worktree"
        git(self.target,"worktree","add","--detach",str(linked),"HEAD")
        git(linked,"submodule","update","--init","--",project.SOURCE)
        shutil.copytree(self.target/project.SOURCE/"apps/knowledge-cli/build",linked/project.SOURCE/"apps/knowledge-cli/build")
        before=git(linked,"status","--porcelain")
        stream=io.StringIO()
        with contextlib.redirect_stdout(stream):
            code=project.main(["work","resolve","--project",str(linked)])
        payload=json.loads(stream.getvalue()); self.assertEqual(0,code,payload)
        gitdir=Path(git(linked,"rev-parse","--absolute-git-dir"))
        receipt=gitdir/"ftckb/receipts"/(payload["receipt"]["id"]+".json")
        self.assertTrue(receipt.is_file())
        self.assertFalse(receipt.resolve().is_relative_to(linked.resolve()))
        self.assertEqual(before,git(linked,"status","--porcelain"))
        self.assertFalse((self.target/".git/ftckb/receipts").exists())

    def test_existing_uuid_is_not_overwritten(self):
        self.install(); identifier=self.resolve()["receipt"]["id"]; path=self.receipt_path(identifier); before=path.read_bytes()
        with patch.object(work.uuid,"uuid4",return_value=work.uuid.UUID(identifier)):
            self.assertEqual(2,self.invoke("resolve")[0])
        self.assertEqual(before,path.read_bytes())
        self.assertFalse(list(path.parent.glob("*.tmp")))

    def test_nonregular_receipt_is_rejected_without_blocking(self):
        if not hasattr(os,"mkfifo"): self.skipTest("POSIX FIFO required")
        self.install(); identifier=self.resolve()["receipt"]["id"]; path=self.receipt_path(identifier); path.unlink(); os.mkfifo(path)
        result=self.call("check","--project",self.target,"--receipt",identifier)
        self.assertEqual(2,result.returncode)
        self.assertEqual("receipt-invalid",json.loads(result.stdout)["error"]["code"])

    def test_resolve_and_check_context_drift_preserve_actual_kernel_fact(self):
        self.install(); kernel=project.kernel
        def drift(*args,**kwargs):
            result=kernel(*args,**kwargs)
            with (self.target/project.CONFIG).open("ab") as stream: stream.write(b" ")
            return result
        with patch.object(project,"kernel",side_effect=drift):
            code,payload=self.invoke("resolve")
        self.assertEqual(2,code); self.assertIsNotNone(payload["kernel"]); self.assertIsNone(payload["receipt"])
        identifier=self.resolve()["receipt"]["id"]
        with patch.object(project,"kernel",side_effect=drift):
            code,payload=self.invoke("check","--receipt",identifier)
        self.assertEqual(2,code); self.assertEqual("rejected",payload["receipt"]["status"])
        self.assertEqual(0,payload["kernel"]["exitCode"])

    def test_diff_change_during_check_is_rejected(self):
        self.install(); identifier=self.resolve()["receipt"]["id"]; diff=self.base/"change.patch"; diff.write_text("before")
        kernel=project.kernel
        def drift(*args,**kwargs):
            result=kernel(*args,**kwargs); diff.unlink(); return result
        with patch.object(project,"kernel",side_effect=drift):
            code,payload=self.invoke("check","--receipt",identifier,"--diff",str(diff))
        self.assertEqual(2,code); self.assertEqual(["diffSha256"],payload["error"]["changedFields"])
        self.assertIsNotNone(payload["kernel"])

    def test_hard_check_exit_one_and_soft_are_embedded_unchanged(self):
        self.install(); identifier=self.resolve()["receipt"]["id"]
        write(self.target,"build.common.gradle","fixture forbidden")
        code,payload=self.invoke("check","--receipt",identifier)
        self.assertEqual(1,code); self.assertFalse(payload["ok"])
        self.assertEqual("matched",payload["receipt"]["status"])
        self.assertTrue(payload["kernel"]["output"]["violations"])
        self.assertTrue(payload["kernel"]["output"]["soft"])

    def test_unprepared_distribution_does_not_build(self):
        self.install(); (self.target/project.SOURCE/"apps/knowledge-cli/build/install/ftckb/bin/ftckb").unlink()
        with patch.object(project,"build_cli",side_effect=AssertionError("build")),patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            code,payload=self.invoke("resolve")
        self.assertEqual(2,code); self.assertEqual("setup-required",payload["error"]["code"])

    def test_work_diagnostics_are_safe_visible_and_preserve_kernel_and_receipt_semantics(self):
        self.install();original=project.run
        diagnostics=(
            ("ftckb audit: storage unavailable; invocation audit is incomplete\nSECRET", "incomplete", "audit-incomplete"),
            ("ftckb audit: checked input version unavailable", "unknown", "version-unknown"),
            ("SECRET other diagnostic #token=PRIVATE", "unknown", "diagnostic-unknown"))
        for diagnostic,status,item_code in diagnostics:
            def injected(*args,**kwargs):
                result=original(*args,**kwargs)
                if "--json" in args[0]:result.stderr=diagnostic
                return result
            with patch.object(project,"run",side_effect=injected):
                code,resolved=self.invoke("resolve")
                identifier=resolved["receipt"]["id"]
                path=self.receipt_path(identifier);stored=path.read_bytes()
                checked_code,checked=self.invoke("check","--receipt",identifier)
            self.assertEqual(0,code,resolved);self.assertEqual(0,checked_code,checked)
            self.assertEqual("created",resolved["receipt"]["status"])
            self.assertEqual("matched",checked["receipt"]["status"])
            self.assertEqual(stored,path.read_bytes())
            for payload in (resolved,checked):
                self.assertTrue(payload["ok"])
                self.assertEqual(status,payload["auditStatus"])
                self.assertEqual(item_code,payload["diagnostics"]["items"][0]["code"])
                self.assertEqual(0,payload["kernel"]["exitCode"])
                self.assertNotIn("SECRET",json.dumps(payload));self.assertNotIn("PRIVATE",json.dumps(payload))

    def test_work_audit_diagnostics_do_not_hide_hard_check_exit(self):
        self.install();identifier=self.resolve()["receipt"]["id"]
        write(self.target,"build.common.gradle","fixture hard violation")
        original=project.run
        def injected(*args,**kwargs):
            result=original(*args,**kwargs)
            if "--json" in args[0]:result.stderr="ftckb audit: storage write failed; invocation audit is incomplete"
            return result
        with patch.object(project,"run",side_effect=injected):
            code,payload=self.invoke("check","--receipt",identifier)
        self.assertEqual(1,code,payload);self.assertFalse(payload["ok"])
        self.assertEqual(1,payload["kernel"]["exitCode"])
        self.assertTrue(payload["kernel"]["output"]["violations"])
        self.assertEqual("matched",payload["receipt"]["status"])
        self.assertEqual("incomplete",payload["auditStatus"])

    def test_quiet_work_output_does_not_claim_audit_health(self):
        self.install();resolved=self.resolve()
        code,checked=self.invoke("check","--receipt",resolved["receipt"]["id"])
        self.assertEqual(0,code,checked)
        for payload in (resolved,checked):
            self.assertNotIn("auditStatus",payload)
            self.assertNotIn("diagnostics",payload)

    def test_old_default_wrapper_payload_and_receipt_storage_are_unchanged(self):
        self.install(); source=self.target/project.SOURCE; config=project.load_config(self.target)
        for command_name in ("resolve","check"):
            expected_code,expected=project.kernel(source,config,command_name,self.target)
            output=io.StringIO()
            with contextlib.redirect_stdout(output): code=project.main([command_name,"--project",str(self.target)])
            self.assertEqual(expected_code,code); self.assertEqual(expected,json.loads(output.getvalue()))
        self.assertFalse((Path(git(self.target,"rev-parse","--absolute-git-dir"))/"ftckb/receipts").exists())


if __name__=="__main__":
    unittest.main()
