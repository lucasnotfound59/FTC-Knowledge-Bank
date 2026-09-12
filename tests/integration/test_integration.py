"""Offline integration tests: real Git repositories, isolated build/kernel fixtures."""
import contextlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
BUNDLE=Path(".agents/skills/ftckb-integrate")
sys.path.insert(0,str(ROOT/BUNDLE/"scripts"))
import integrate
import project
import verify


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


if __name__=="__main__":
    unittest.main()
