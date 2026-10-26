"""Explicit staged install: isolated Git fixtures and no implicit kernel before accept."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import test_integration as fixture
from test_integration import commit,git,integrate,project,snapshot,write
import installation_workflow as workflow
import verify


class StagedInstallationTest(unittest.TestCase):
    setUpFixture=fixture.IntegrationTest.setUp

    def setUp(self):
        self.setUpFixture()
        build=self.source/"tools/fake_build.py"
        build.write_text(build.read_text()+"\nlib=root/'apps/knowledge-cli/build/install/ftckb/lib'\nlib.mkdir(parents=True,exist_ok=True)\n(lib/'knowledge-cli.jar').write_text('fixture main jar')\n")
        self.sha=commit(self.source,"staged distribution fixture")
        self.audit=patch.dict(os.environ,{"FTCKB_AUDIT_DIR":str(self.base/"audit")})
        self.audit.start();self.addCleanup(self.audit.stop)

    def args(self,**kwargs):
        kwargs.setdefault("ref",self.sha)
        args=fixture.IntegrationTest.args(self,**kwargs)
        args.prepare_only=True
        return args

    def prepare(self,**kwargs):
        with tempfile.TemporaryFile(mode="w+") as logs,contextlib.redirect_stderr(logs):
            return integrate.integrate(self.args(**kwargs))

    def test_prepare_dry_run_is_distinct_unaccepted_and_byte_immutable(self):
        before=snapshot(self.target)
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            code,payload=self.prepare(dry_run=True)
        self.assertEqual(0,code)
        self.assertEqual(1,payload["installationWorkflowSchemaVersion"])
        self.assertEqual("prepare",payload["command"])
        self.assertEqual("planned",payload["stage"])
        self.assertIsNone(payload["preparedContextSha256"])
        self.assertNotIn("installationOk",payload)
        self.assertEqual(before,snapshot(self.target))

    def test_prepare_builds_observed_distribution_without_running_kernel(self):
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            code,payload=self.prepare()
        self.assertEqual(0,code,payload)
        self.assertEqual("prepared",payload["stage"])
        self.assertEqual(64,len(payload["preparedContextSha256"]))
        self.assertEqual("not_performed",payload["kernelStatus"])
        self.assertNotIn("installationOk",payload)
        self.assertTrue(all("argv" not in step for step in payload["nextSteps"] if step["code"] in ("accept","connection-input")))
        code,repeated=self.prepare()
        self.assertEqual(0,code,repeated)
        self.assertEqual(payload["preparedContextSha256"],repeated["preparedContextSha256"])

    def accepted(self,prepared):
        return workflow.accept(self.target,prepared["preparedContextSha256"],str(self.base/"private.json"),True)

    def test_accept_checks_actual_kernel_with_explicit_env_and_preserves_receipt_inputs(self):
        _,prepared=self.prepare();before=snapshot(self.target)
        with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})),patch.object(project,"build_cli",side_effect=AssertionError("build")),patch.object(project,"kernel",wraps=project.kernel) as kernel:
            code,payload=self.accepted(prepared)
        self.assertEqual(0,code,payload);self.assertTrue(payload["installationOk"])
        self.assertEqual(["validate","resolve","check"],[call.args[2] for call in kernel.call_args_list])
        self.assertTrue(all(call.kwargs["env"]=={"FTCKB_AUDIT_DIR":prepared["context"]["expectedAuditRoot"]} for call in kernel.call_args_list))
        self.assertEqual("not_reported",payload["auditStatus"])
        self.assertTrue(payload["projectCheck"]["soft"])
        self.assertEqual(before,snapshot(self.target))

    def test_browser_and_accept_only_flags_are_required_before_any_kernel(self):
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")),patch.object(verify,"verify",side_effect=AssertionError("legacy")):
            for extra in (["--prepared-context-sha256","a"*64],["--connection-file","SECRET"],["--browser-confirmed"],
                          ["--accept"],["--accept","--prepared-context-sha256","bad","--connection-file","SECRET","--browser-confirmed"],
                          ["--accept","--prepared-context-sha256","a"*64,"--connection-file","SECRET","--work-mode","dev"]):
                stream=io.StringIO()
                with contextlib.redirect_stdout(stream):code=verify.main(["--project",str(self.target),*extra])
                self.assertEqual(64,code)
                payload=json.loads(stream.getvalue());self.assertEqual("usage",payload["error"]["code"])
                self.assertNotIn("SECRET",stream.getvalue())

    def test_phase_option_abbreviations_never_reach_legacy_or_kernel(self):
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")),patch.object(verify,"verify",side_effect=AssertionError("legacy")),patch.object(integrate,"integrate",side_effect=AssertionError("install")):
            for flag in ("--acc","--prepared-context-sha","--connection-fi","--browser-conf"):
                stream=io.StringIO()
                with contextlib.redirect_stdout(stream):code=verify.main(["--project",str(self.target),flag])
                self.assertEqual(64,code);self.assertEqual("usage",json.loads(stream.getvalue())["error"]["code"])
            stream=io.StringIO()
            with contextlib.redirect_stdout(stream):code=integrate.main(["--project",str(self.target),"--prepare-o"])
            self.assertEqual(64,code);self.assertEqual("usage",json.loads(stream.getvalue())["error"]["code"])

    def test_context_change_and_wrong_connection_do_not_execute_kernel(self):
        _,prepared=self.prepare()
        with (self.target/project.CONFIG).open("ab") as stream:stream.write(b" ")
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            code,payload=self.accepted(prepared)
        self.assertEqual(2,code);self.assertEqual("preparation_changed",payload["error"]["code"])
        _,prepared=self.prepare()
        with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(1,{"ok":False})),patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            code,payload=self.accepted(prepared)
        self.assertEqual(2,code);self.assertEqual("connection_failed",payload["error"]["code"])

    def test_each_stage_drift_stops_all_later_kernel_calls(self):
        _,prepared=self.prepare();kernel=project.kernel
        for at,expected in (("validate",["validate"]),("resolve",["validate","resolve"])):
            if at=="resolve":_,prepared=self.prepare()
            calls=[]
            def drift(*args,**kwargs):
                calls.append(args[2]);result=kernel(*args,**kwargs)
                if args[2]==at:
                    with (self.target/project.CONFIG).open("ab") as stream:stream.write(b" ")
                return result
            with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})),patch.object(project,"kernel",side_effect=drift):
                code,payload=self.accepted(prepared)
            self.assertEqual(2,code);self.assertEqual(expected,calls)
            self.assertEqual("preparation_changed",payload["error"]["code"])
            self.assertEqual(0,payload["checks"][at]["kernel"]["exitCode"])

    def test_audit_storage_diagnostic_blocks_but_keeps_kernel_exit_zero(self):
        _,prepared=self.prepare();kernel=project.kernel;calls=[]
        def diagnostic(*args,**kwargs):
            calls.append(args[2]);result=kernel(*args,**kwargs)
            kwargs["diagnostic_sink"]("ftckb audit: storage write failed; invocation audit is incomplete\nSECRET")
            return result
        with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})),patch.object(project,"kernel",side_effect=diagnostic):
            code,payload=self.accepted(prepared)
        self.assertEqual(2,code);self.assertEqual(["validate"],calls)
        self.assertEqual("audit_incomplete",payload["error"]["code"])
        self.assertEqual(0,payload["checks"]["validate"]["kernel"]["exitCode"])
        self.assertEqual("incomplete",payload["auditStatus"]);self.assertNotIn("SECRET",json.dumps(payload))

    def test_version_unknown_diagnostic_is_not_misreported_as_storage_failure(self):
        _,prepared=self.prepare();kernel=project.kernel
        def diagnostic(*args,**kwargs):
            result=kernel(*args,**kwargs);kwargs["diagnostic_sink"]("ftckb audit: checked input version unavailable")
            return result
        with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})),patch.object(project,"kernel",side_effect=diagnostic):
            code,payload=self.accepted(prepared)
        self.assertEqual(0,code,payload);self.assertEqual("unknown",payload["auditStatus"])
        self.assertEqual("version-unknown",payload["checks"]["validate"]["diagnostics"]["items"][0]["code"])

    def test_current_hard_check_is_not_installation_failure(self):
        _,prepared=self.prepare();write(self.target,"build.common.gradle","fixture hard violation")
        with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})):
            code,payload=self.accepted(prepared)
        self.assertEqual(1,code,payload);self.assertTrue(payload["installationOk"])
        self.assertFalse(payload["projectCheck"]["ok"]);self.assertTrue(payload["projectCheck"]["violations"])

    def test_distribution_symlinks_missing_main_and_budget_are_rejected(self):
        _,prepared=self.prepare();source=self.target/project.SOURCE
        main=source/"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar";main.unlink()
        code,payload=self.accepted(prepared);self.assertEqual(2,code);self.assertEqual("setup_required",payload["error"]["code"])
        main.symlink_to(self.source/"README.md")
        self.assertEqual(2,self.accepted(prepared)[0])

    def test_unknown_dashboard_capability_has_no_executable_command(self):
        _,prepared=self.prepare()
        step=next(item for item in prepared["nextSteps"] if item["code"]=="dashboard")
        self.assertEqual("unknown",step["capability"]);self.assertNotIn("argv",step)
        with patch.object(workflow,"dashboard_capability",return_value="passed"):
            steps=workflow.prepare_steps(self.target,self.source,prepared["context"],prepared["preparedContextSha256"],{})
        self.assertEqual(str(Path(workflow.dashboard_runtime.__file__).resolve()),steps[1]["argv"][1])
        self.assertEqual(["--distribution",str(self.source/"apps/knowledge-cli/build/install/ftckb"),"--knowledge",prepared["context"]["knowledgePath"],"--no-browser"],steps[1]["argv"][2:])
        self.assertEqual(str(self.target),steps[1]["cwd"])
        with patch.object(workflow.dashboard_runtime,"recommendation",return_value=None),patch.object(workflow,"dashboard_capability",return_value="passed"):
            unsupported=workflow.prepare_steps(self.target,self.source,prepared["context"],prepared["preparedContextSha256"],{})[1]
        self.assertNotIn("argv",unsupported);self.assertEqual("unavailable",unsupported["protection"])

    def test_failed_process_and_untrusted_output_keep_observed_execution_facts(self):
        _,prepared=self.prepare()
        for result,executed,exit_code in ((OSError("SECRET"),False,None),
                (subprocess.CompletedProcess([],7,"SECRET-not-json","SECRET-stderr"),True,7),
                (subprocess.CompletedProcess([],0,'{"schemaVersion":99}',""),True,0)):
            with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})),patch.object(project,"run",side_effect=result if isinstance(result,Exception) else None,return_value=result):
                code,payload=self.accepted(prepared)
            self.assertEqual(2,code);self.assertEqual(["validate"],list(payload["checks"]))
            stage=payload["checks"]["validate"]
            self.assertTrue(stage["attempted"]);self.assertEqual(executed,stage["executed"])
            self.assertEqual("partially_performed" if executed else "not_performed",payload["kernelStatus"])
            if executed:
                self.assertEqual(exit_code,stage["kernel"]["exitCode"]);self.assertIsNone(stage["kernel"]["output"])
            else:self.assertIsNone(stage["kernel"])
            self.assertNotIn("SECRET",json.dumps(payload))

    def test_context_artifact_and_ignored_rule_changes_block_before_kernel(self):
        _,prepared=self.prepare();source=self.target/project.SOURCE
        for path in (self.target/project.CONFIG,source/"apps/knowledge-cli/build/install/ftckb/lib/knowledge-cli.jar"):
            original=path.read_bytes()
            try:
                path.write_bytes(original+b" ")
                with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                    code,payload=self.accepted(prepared)
                self.assertEqual(2,code);self.assertEqual("preparation_changed",payload["error"]["code"])
            finally:path.write_bytes(original)
        with patch.dict(os.environ,{"FTCKB_AUDIT_DIR":str(self.base/"different-audit")}):
            with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                self.assertEqual("preparation_changed",self.accepted(prepared)[1]["error"]["code"])
        exclude=Path(git(source,"rev-parse","--git-path","info/exclude"))
        if not exclude.is_absolute():exclude=source/exclude
        exclude.parent.mkdir(parents=True,exist_ok=True)
        with exclude.open("a") as stream:stream.write("\nknowledge/ignored.yaml\n")
        ignored=source/"knowledge/ignored.yaml";ignored.write_text("rules: []\n")
        _,prepared=self.prepare();ignored.write_text("rules: [] # changed input\n")
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            self.assertEqual("preparation_changed",self.accepted(prepared)[1]["error"]["code"])

    def test_ordinary_robot_code_change_keeps_preparation_valid(self):
        _,prepared=self.prepare()
        write(self.target,"TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/LengthUnits.java","// isolated helper\n")
        with patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})):
            code,payload=self.accepted(prepared)
        self.assertEqual(0,code,payload);self.assertTrue(payload["installationOk"])

    def test_distribution_budget_and_prebuild_symlink_fail_closed(self):
        _,prepared=self.prepare();source=self.target/project.SOURCE
        with patch.object(workflow,"FILE_LIMIT",1),patch.object(project,"kernel",side_effect=AssertionError("kernel")):
            self.assertEqual("setup_required",self.accepted(prepared)[1]["error"]["code"])
        stamp=source/"apps/knowledge-cli/build/ftckb-integration-commit";stamp.unlink();stamp.symlink_to(self.source/"README.md")
        with patch.object(project,"build_cli",side_effect=AssertionError("build")):
            code,payload=self.prepare()
        self.assertEqual(2,code)

    def test_all_prepared_configuration_and_managed_inputs_fail_closed(self):
        _,prepared=self.prepare();source=self.target/project.SOURCE;config_path=self.target/project.CONFIG
        original=config_path.read_bytes()
        for field,value in (("team","99999"),("season","2026-2027"),("profiles",["command-based"])):
            config=json.loads(original);config[field]=value;config_path.write_text(json.dumps(config))
            try:
                with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                    self.assertEqual(2,self.accepted(prepared)[0],field)
            finally:config_path.write_bytes(original)
        for field,value in (("repository","https://example.invalid/changed.git"),("commit","0"*40)):
            config=json.loads(original);config["source"][field]=value;config_path.write_text(json.dumps(config))
            try:
                with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                    self.assertEqual(2,self.accepted(prepared)[0],field)
            finally:config_path.write_bytes(original)
        for path in (self.target/project.SKILL,self.target/"AGENTS.md",source/"apps/knowledge-cli/build/ftckb-integration-commit",Path(project.launcher(source)[-1])):
            contents=path.read_bytes()
            try:
                path.write_bytes(contents+b" # changed\n")
                with patch.object(project,"kernel",side_effect=AssertionError("kernel")):
                    self.assertEqual(2,self.accepted(prepared)[0],str(path))
            finally:path.write_bytes(contents)

    def test_before_each_stage_drift_does_not_execute_that_stage(self):
        _,prepared=self.prepare();capture=workflow.preparation_snapshot
        for changed_at,expected in ((2,[]),(4,["validate"]),(6,["validate","resolve"])):
            count=0;calls=[]
            def changing(root):
                nonlocal count
                count+=1;result=capture(root)
                return ("0"*64,*result[1:]) if count==changed_at else result
            kernel=project.kernel
            def observed(*args,**kwargs):calls.append(args[2]);return kernel(*args,**kwargs)
            with patch.object(workflow,"preparation_snapshot",side_effect=changing),patch.object(workflow.dashboard_connection,"verify_connection",return_value=(0,{"ok":True})),patch.object(project,"kernel",side_effect=observed):
                code,payload=self.accepted(prepared)
            self.assertEqual(2,code);self.assertEqual(expected,calls)
            self.assertEqual("preparation_changed",payload["error"]["code"])

    def test_long_diagnostics_are_bounded_fixed_messages(self):
        diagnostic=workflow.classify_diagnostics("SECRET http://127.0.0.1:123/#token=PRIVATE\x00"*2000)
        self.assertTrue(diagnostic["truncated"]);self.assertEqual("unknown",diagnostic["auditStatus"])
        self.assertLess(len(json.dumps(diagnostic)),512)
        self.assertNotIn("PRIVATE",json.dumps(diagnostic));self.assertNotIn("SECRET",json.dumps(diagnostic))

    def test_prepare_failure_retains_installation_and_can_retry(self):
        with patch.dict(os.environ,{"FTCKB_TEST_BUILD_FAIL":"1"}):code,payload=self.prepare()
        self.assertEqual(2,code);self.assertEqual("build_failed",payload["error"]["code"])
        self.assertTrue((self.target/project.CONFIG).exists())
        self.assertEqual(0,self.prepare()[0])


if __name__=="__main__":
    unittest.main()
