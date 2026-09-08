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
payload={"schemaVersion":1,"command":command,"ok":True}
code=0
if command=="validate":
    payload.update(ruleCount=1,violations=[])
else:
    payload.update(team=args[args.index("--team")+1],season=args[args.index("--season")+1])
    if command=="resolve":
        payload.update(activeRules=[],conflicts=[])
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
                    repository=str(self.source),ref="v-test",dry_run=False)
        values.update(kwargs)
        return type("Args",(),values)()

    def install(self,**kwargs):
        with tempfile.TemporaryFile(mode="w+") as logs,contextlib.redirect_stderr(logs):
            return integrate.integrate(self.args(**kwargs))

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
        valid={"schemaVersion":1,"command":"check","team":"16093","season":"2025-2026",
               "ok":True,"violations":[],"soft":[]}
        for changes,code in (({},1),({"schemaVersion":2},0),({"team":"20827"},0),({"ok":False},1),({"soft":"not an array"},0)):
            payload=valid|changes
            completed=subprocess.CompletedProcess([],code,json.dumps(payload),"")
            with self.subTest(changes=changes,code=code),patch.object(project,"run",return_value=completed):
                with self.assertRaises(project.IntegrationError):
                    project.kernel(source,config,"check",self.target)

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
        schema=project.validator(ROOT)
        paths=list((ROOT/"fixtures/kernel").glob("*.json"))
        self.assertGreaterEqual(len(paths),10)
        for path in paths:
            with self.subTest(path=path.name):
                schema.validate(json.loads(path.read_text()))

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
