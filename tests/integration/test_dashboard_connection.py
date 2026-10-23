"""Dashboard connection boundaries: isolated HTTP server and real temporary Git fixtures."""
import contextlib
import copy
import io
import http.client
import json
import os
from pathlib import Path
import socket
import threading
import unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from unittest.mock import patch

from test_integration import ROOT,BUNDLE,IntegrationTest,git,project,snapshot,write
import dashboard_connection as connection


class DashboardConnectionTest(unittest.TestCase):
    setUpFixture=IntegrationTest.setUp
    args=IntegrationTest.args
    install=IntegrationTest.install

    def setUp(self):
        self.setUpFixture()
        self.install()
        self.audit_env=patch.dict(os.environ,{"FTCKB_AUDIT_DIR":str(self.base/"audit")})
        self.audit_env.start();self.addCleanup(self.audit_env.stop)
        self.local=connection.capture(self.target)
        expected=self.local["context"]
        self.payload={"apiVersion":1,"ok":True,"identitySchemaVersion":1,
                      "knowledge":{"root":expected["knowledgePath"],"version":"a"*64,
                                   "fileDigests":self.local["fileDigests"],"valid":True,"ruleCount":0},
                      "audit":{"kind":"file","root":expected["expectedAuditRoot"],
                               "partition":expected["expectedAuditPartition"],"health":{"ok":True,"warnings":[]}}}
        self.requests=[];self.callback=None;self.status=200;self.body=None;self.extra_headers={}
        self.token="A"*43
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                owner.requests.append(("GET",self.path))
                authenticated=self.headers.get("Authorization")=="Bearer "+owner.token
                status=owner.status if authenticated else 401
                if owner.callback: owner.callback()
                self.send_response(status)
                self.send_header("Content-Type","application/json")
                for key,value in owner.extra_headers.items(): self.send_header(key,value)
                self.end_headers()
                body=owner.body if owner.body is not None else json.dumps(owner.payload).encode()
                try: self.wfile.write(body)
                except (BrokenPipeError,ConnectionResetError): pass
            def do_POST(self):
                owner.requests.append(("POST",self.path));self.send_response(405);self.end_headers()
        self.server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        self.thread=threading.Thread(target=lambda:self.server.serve_forever(poll_interval=0.01),daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close);self.addCleanup(self.server.shutdown)
        self.origin="http://127.0.0.1:"+str(self.server.server_port)
        self.private=(self.base/"connection.json").resolve()
        self.write_connection(self.origin+"/#token="+self.token)

    def write_connection(self,url):
        self.private.write_text(json.dumps({"url":url}));self.private.chmod(0o600)

    def invoke(self,*extra,private=None):
        output=io.StringIO()
        with contextlib.redirect_stdout(output):
            code=project.main(["dashboard","verify","--project",str(self.target),"--connection-file",str(private or self.private),*extra])
        payload=json.loads(output.getvalue())
        self.assertNotIn(self.token,output.getvalue())
        return code,payload

    def states(self,payload):
        return {check["code"]:check["status"] for check in payload["checks"]}

    def test_matches_api_identity_and_returns_explicit_workflow_environment(self):
        before=snapshot(self.target)
        code,payload=self.invoke()
        self.assertEqual(0,code,payload)
        self.assertEqual(1,payload["dashboardConnectionSchemaVersion"])
        self.assertEqual("dashboard-verify",payload["command"])
        self.assertTrue(payload["ok"])
        self.assertEqual("not_checked",self.states(payload)["browser.connection"])
        self.assertEqual("not_checked",self.states(payload)["kernel.defaultAudit"])
        self.assertEqual("unknown",self.states(payload)["server.binaryPin"])
        action=next(step for step in payload["nextSteps"] if step["code"]=="work-resolve")
        self.assertEqual(str((ROOT/BUNDLE/"scripts/project.py").resolve()),action["argv"][1])
        self.assertEqual(self.local["context"]["expectedAuditRoot"],action["env"]["FTCKB_AUDIT_DIR"])
        self.assertEqual(before,snapshot(self.target))
        self.assertEqual([("GET","/api/dashboard/identity")],self.requests)

    def test_wrong_knowledge_root_content_or_invalid_library_are_not_success(self):
        for key,value in (("root","/private/tmp/SECRET"),("fileDigests",{"other.yaml":"b"*64}),("valid",False)):
            original=copy.deepcopy(self.payload)
            self.payload["knowledge"][key]=value
            with self.subTest(key=key):
                code,payload=self.invoke();self.assertEqual(1,code)
                self.assertEqual("knowledge-mismatch",payload["error"]["code"])
                self.assertNotIn("SECRET",json.dumps(payload))
            self.payload=original

    def test_wrong_actual_audit_root_partition_or_health_are_not_success(self):
        for key,value in (("root","/private/tmp/SECRET"),("partition","/private/tmp/OTHER"),("health",{"ok":False,"warnings":["SECRET"]})):
            original=copy.deepcopy(self.payload);self.payload["audit"][key]=value
            with self.subTest(key=key):
                code,payload=self.invoke();self.assertEqual(1,code)
                self.assertEqual("audit-mismatch",payload["error"]["code"])
                self.assertNotIn("SECRET",json.dumps(payload))
            self.payload=original

    def test_custom_store_unknown_does_not_fall_back_to_default(self):
        self.payload["audit"].update(kind="unknown",root=None,partition=None)
        code,payload=self.invoke();self.assertEqual(1,code)
        self.assertEqual("storage-unknown",payload["error"]["code"])
        self.assertEqual("unknown",self.states(payload)["audit.partition"])

    def test_relative_and_missing_suffix_audit_environment_uses_project_cwd(self):
        with patch.dict(os.environ,{"FTCKB_AUDIT_DIR":"audit/new/tail"}):
            current=connection.capture(self.target)
        expected=(self.target/"audit/new/tail").resolve()
        self.assertEqual(str(expected),current["context"]["expectedAuditRoot"])
        with patch.dict(os.environ,{"FTCKB_AUDIT_DIR":"~/literal"}):
            current=connection.capture(self.target)
        self.assertEqual(str((self.target/"~/literal").resolve()),current["context"]["expectedAuditRoot"])

    def test_blank_environment_selects_python_home_only_as_explicit_expectation(self):
        with patch.dict(os.environ,{"FTCKB_AUDIT_DIR":"  "}):
            current=connection.capture(self.target)
        self.assertEqual(str(connection.canonical_path(Path.home()/".ftckb/audit")),current["context"]["expectedAuditRoot"])

    def test_canonical_audit_root_follows_existing_symlink_parent(self):
        existing=self.base/"real";existing.mkdir();alias=self.base/"alias";alias.symlink_to(existing,target_is_directory=True)
        self.assertEqual((existing/"new/tail").resolve(),connection.canonical_path(alias/"new/tail"))

    def test_no_work_storage_or_cli_jdk_requirement_is_introduced(self):
        # No build stamp/main JAR exists in this fixture; verification still passes.
        (self.target/project.SOURCE/"apps/knowledge-cli/build/install/ftckb/bin/ftckb").unlink()
        code,payload=self.invoke();self.assertEqual(0,code,payload)
        with patch.object(project,"kernel",side_effect=AssertionError("kernel")),patch.object(project,"build_cli",side_effect=AssertionError("build")):
            self.assertEqual(0,self.invoke()[0])

    def test_private_file_permissions_owner_symlink_and_nonregular_are_rejected(self):
        self.private.chmod(0o644)
        self.assertEqual("private-file",self.invoke()[1]["error"]["code"])
        self.private.chmod(0o600)
        with patch.object(connection.os,"geteuid",return_value=os.getuid()+1):
            self.assertEqual("private-file",self.invoke()[1]["error"]["code"])
        alias=self.private.parent/"alias.json";alias.symlink_to(self.private)
        self.assertEqual("private-file",self.invoke(private=alias)[1]["error"]["code"])
        folder=self.private.parent/"directory";folder.mkdir()
        self.assertEqual("private-file",self.invoke(private=folder)[1]["error"]["code"])
        if hasattr(os,"mkfifo"):
            fifo=self.private.parent/"pipe";os.mkfifo(fifo)
            self.assertEqual("private-file",self.invoke(private=fifo)[1]["error"]["code"])
        self.assertFalse(self.requests)

    def test_private_file_duplicate_extra_bad_and_oversized_json_are_rejected(self):
        for body in ('{"url":"SECRET","url":"other"}','{"url":"SECRET","token":"SECRET"}',"{bad","x"*(connection.FILE_LIMIT+1)):
            self.private.write_text(body)
            with self.subTest(body=body[:20]):
                code,payload=self.invoke();self.assertEqual(1,code)
                self.assertEqual("private-file",payload["error"]["code"])
                self.assertNotIn("SECRET",json.dumps(payload))
        self.assertFalse(self.requests)

    def test_only_exact_loopback_fragment_url_is_accepted(self):
        suffix="/#token="+self.token
        urls=["https://127.0.0.1:1234"+suffix,"http://localhost:1234"+suffix,"http://127.0.0.2:1234"+suffix,
              "http://user@127.0.0.1:1234"+suffix,"http://127.0.0.1:0"+suffix,"http://127.0.0.1:99999"+suffix,
              self.origin+"/other#token="+self.token,self.origin+"/?query=x#token="+self.token,
              self.origin+"/#token="+self.token+"&token="+self.token,self.origin+"/#token=bad"]
        for url in urls:
            self.write_connection(url)
            with self.subTest(url=url.split("#")[0]):
                code,payload=self.invoke();self.assertEqual(1,code)
                self.assertEqual("invalid-url",payload["error"]["code"])
        self.assertFalse(self.requests)

    def test_unverifiable_privacy_is_explicit_unknown(self):
        with patch.object(connection,"private_connection",side_effect=connection.ConnectionProblem("privacy-unknown","unknown")):
            code,payload=self.invoke()
        self.assertEqual(1,code);self.assertEqual("unknown",self.states(payload)["connection.private-file"])

    def test_401_and_old_endpoint_are_distinguished_without_body_echo(self):
        for status,error,state in ((401,"unauthorized","failed"),(404,"unsupported-api","unknown")):
            self.status=status;self.body=b"SECRET"
            code,payload=self.invoke();self.assertEqual(1,code)
            self.assertEqual(error,payload["error"]["code"])
            self.assertEqual(state,self.states(payload)["connection.authenticated"])
            self.assertNotIn("SECRET",json.dumps(payload))

    def test_missing_wrong_versions_fields_and_types_are_safe_unknown(self):
        original=copy.deepcopy(self.payload)
        for mutate in (lambda p:p.update(apiVersion=2),lambda p:p.update(identitySchemaVersion=True),
                       lambda p:p.pop("knowledge"),lambda p:p["knowledge"].pop("fileDigests"),
                       lambda p:p["knowledge"].update(ruleCount=True),lambda p:p["audit"].update(root=123)):
            self.payload=copy.deepcopy(original);mutate(self.payload)
            code,payload=self.invoke();self.assertEqual(1,code)
            self.assertEqual("unknown",self.states(payload)["connection.authenticated"])

    def test_redirect_is_not_followed_and_environment_proxy_is_ignored(self):
        self.status=302;self.extra_headers={"Location":self.origin+"/SECRET"}
        code,payload=self.invoke();self.assertEqual(1,code)
        self.assertEqual("redirect",payload["error"]["code"])
        self.assertEqual(1,len(self.requests))
        self.status=200;self.extra_headers={}
        with patch.dict(os.environ,{"HTTP_PROXY":"http://127.0.0.1:1","http_proxy":"http://127.0.0.1:1","NO_PROXY":"","no_proxy":""}):
            self.assertEqual(0,self.invoke()[0])

    def test_timeout_unreachable_oversize_and_bad_json_are_safe(self):
        with patch.object(connection.urllib.request.OpenerDirector,"open",side_effect=socket.timeout("SECRET")):
            code,payload=self.invoke();self.assertEqual(1,code);self.assertEqual("unreachable",payload["error"]["code"])
            self.assertNotIn("SECRET",json.dumps(payload))
        with patch.object(connection.urllib.request.OpenerDirector,"open",side_effect=http.client.IncompleteRead(b"SECRET")):
            code,payload=self.invoke();self.assertEqual(1,code);self.assertEqual("invalid-response",payload["error"]["code"])
            self.assertNotIn("SECRET",json.dumps(payload))
        for body in (b"SECRET",b"x"*(connection.RESPONSE_LIMIT+1),b'{"apiVersion":1,"apiVersion":1}'):
            self.body=body
            code,payload=self.invoke();self.assertEqual(1,code)
            self.assertEqual("invalid-response",payload["error"]["code"])
        self.extra_headers={"Content-Length":str(connection.RESPONSE_LIMIT+1)}
        self.assertEqual("invalid-response",self.invoke()[1]["error"]["code"])

    def test_response_paths_are_only_compared_never_read(self):
        secret=self.base/"SECRET";secret.write_text("never disclose")
        self.payload["audit"]["root"]=str(secret.resolve())
        code,payload=self.invoke();self.assertEqual(1,code)
        self.assertEqual("audit-mismatch",payload["error"]["code"])
        self.assertNotIn("SECRET",json.dumps(payload))

    def test_local_configuration_change_during_get_is_rejected(self):
        def drift():
            with (self.target/project.CONFIG).open("ab") as stream: stream.write(b" ")
        self.callback=drift
        code,payload=self.invoke();self.assertEqual(1,code)
        self.assertEqual("local-changed",payload["error"]["code"])
        self.assertEqual("failed",self.states(payload)["local.stable"])

    def test_ignored_unicode_yaml_is_part_of_local_mapping_and_drift(self):
        source=self.target/project.SOURCE
        exclude=Path(git(source,"rev-parse","--absolute-git-dir"))/"info/exclude"
        with exclude.open("a") as stream: stream.write("\nknowledge/guides/目录/ignored.yml\n")
        target=source/"knowledge/guides/目录/ignored.yml"
        write(source,"knowledge/guides/目录/ignored.yml","[]\n")
        local=connection.capture(self.target)
        self.payload["knowledge"]["fileDigests"]=local["fileDigests"]
        self.assertIn("guides/目录/ignored.yml",local["fileDigests"])
        self.callback=lambda:target.write_text("# changed\n[]\n")
        code,payload=self.invoke();self.assertEqual(1,code)
        self.assertEqual("local-changed",payload["error"]["code"])

    def test_invalid_project_blocks_http_and_errors_never_echo_configuration(self):
        value=json.loads((self.target/project.CONFIG).read_text());value["source"]["path"]="../SECRET"
        write(self.target,project.CONFIG,json.dumps(value))
        code,payload=self.invoke();self.assertEqual(1,code)
        self.assertEqual("integration-invalid",payload["error"]["code"])
        self.assertFalse(self.requests);self.assertNotIn("SECRET",json.dumps(payload))

    def test_git_fsmonitor_is_not_executed_and_no_target_metadata_is_written(self):
        marker=self.base/"executed";script=self.base/"monitor"
        script.write_text('#!/bin/sh\nprintf x > "'+str(marker)+'"\n');script.chmod(0o755)
        git(self.target,"config","core.fsmonitor",str(script));git(self.target/project.SOURCE,"config","core.fsmonitor",str(script))
        before=snapshot(self.target)
        self.assertEqual(0,self.invoke()[0]);self.assertEqual(before,snapshot(self.target));self.assertFalse(marker.exists())

    def test_recursion_at_local_capture_boundaries_is_fixed_json(self):
        with patch.object(connection,"capture",side_effect=RecursionError("SECRET")):
            code,payload=self.invoke()
        self.assertEqual(1,code);self.assertEqual("integration-invalid",payload["error"]["code"])
        with patch.object(connection,"capture",side_effect=[self.local,RecursionError("SECRET")]):
            code,payload=self.invoke()
        self.assertEqual(1,code);self.assertEqual("local-changed",payload["error"]["code"])
        self.assertNotIn("SECRET",json.dumps(payload))

    def test_recursive_json_is_fixed_error_at_both_decode_boundaries(self):
        loads=json.loads
        for marker,expected in (('"url"',"private-file"),('"identitySchemaVersion"',"invalid-response")):
            def nested(data,*args,**kwargs):
                if isinstance(data,str) and marker in data: raise RecursionError("SECRET")
                return loads(data,*args,**kwargs)
            with self.subTest(boundary=expected),patch.object(connection.json,"loads",side_effect=nested):
                code,payload=self.invoke()
            self.assertEqual(1,code);self.assertEqual(expected,payload["error"]["code"])
            self.assertNotIn("SECRET",json.dumps(payload))

    def test_usage_errors_are_single_json_exit_64(self):
        for args in ([],["verify","--project",str(self.target)],["wrong","--project",str(self.target),"--connection-file",str(self.private)],
                     ["verify","--project",str(self.target),"--connection-file",str(self.private),"--token=SECRET"]):
            output=io.StringIO()
            with contextlib.redirect_stdout(output): code=project.main(["dashboard",*args])
            payload=json.loads(output.getvalue());self.assertEqual(64,code)
            self.assertEqual("usage",payload["error"]["code"]);self.assertNotIn("SECRET",output.getvalue())


if __name__=="__main__":
    unittest.main()
