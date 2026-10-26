"""Protected Dashboard startup: independent distributions and owned lifetimes."""
import contextlib
import io
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode=True
SCRIPTS=Path(__file__).resolve().parents[2]/'.agents/skills/ftckb-integrate/scripts'
sys.path.insert(0,str(SCRIPTS))
import dashboard_runtime as runtime


@unittest.skipUnless(os.name=='posix','protected runtime is POSIX only')
class DashboardRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='ftckb runtime 空格 ')
        self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name);self.dist=self.base/'source distribution';self.knowledge=self.base/'knowledge';self.knowledge.mkdir()
        (self.dist/'bin').mkdir(parents=True);(self.dist/'lib').mkdir()
        (self.dist/'lib/knowledge-cli.jar').write_bytes(b'main jar')
        (self.dist/'lib/dependency.jar').write_bytes(b'dependency jar')
        self.marker=self.base/'child.json'
        self.program=self.dist/'bin/probe.py'
        self.program.write_text("import os,json,sys,time\nfrom pathlib import Path\nPath(os.environ['RUNTIME_MARKER']).write_text(json.dumps({'file':__file__,'pid':os.getpid(),'cwd':os.getcwd(),'audit':os.environ.get('FTCKB_AUDIT_DIR'),'argv':sys.argv[1:]}))\nif os.environ.get('RUNTIME_IDLE'): time.sleep(60)\nsys.exit(int(os.environ.get('RUNTIME_EXIT','0')))\n")
        (self.dist/'bin/ftckb').write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable)+' "$(dirname "$0")/probe.py" "$@"\n')
        (self.dist/'bin/ftckb').chmod(0o755)

    def args(self):return ['--distribution',str(self.dist),'--knowledge',str(self.knowledge),'--no-browser']

    def invoke(self,*extra):
        with patch.dict(os.environ,{'RUNTIME_MARKER':str(self.marker)}),contextlib.redirect_stderr(io.StringIO()):
            return runtime.main(self.args()+list(extra))

    def test_independent_full_copy_preserves_modes_and_rejects_source_changes(self):
        root,copy=runtime.snapshot(self.dist)
        try:
            self.assertEqual(runtime.inventory(self.dist),runtime.inventory(copy))
            self.assertNotEqual((self.dist/'lib/dependency.jar').stat().st_ino,(copy/'lib/dependency.jar').stat().st_ino)
            self.assertEqual(0o700,root.stat().st_mode & 0o777)
            (self.dist/'lib/dependency.jar').write_bytes(b'changed')
            self.assertEqual(b'dependency jar',(copy/'lib/dependency.jar').read_bytes())
        finally:runtime.cleanup(root)

    def test_normal_exit_preserves_cli_status_cwd_env_and_cleans_snapshot(self):
        with patch.dict(os.environ,{'RUNTIME_EXIT':'7','FTCKB_AUDIT_DIR':str(self.base/'audit')}):
            self.assertEqual(7,self.invoke('--port','0'))
        child=json.loads(self.marker.read_text());self.assertEqual(os.getcwd(),child['cwd']);self.assertEqual(str(self.base/'audit'),child['audit'])
        self.assertEqual(['dashboard','--knowledge',str(self.knowledge.resolve()),'--port','0','--no-browser'],child['argv'])
        self.assertFalse(Path(child['file']).parents[2].exists());self.assertTrue(self.dist.exists())

    def test_invalid_arguments_and_unsupported_platform_do_not_start(self):
        for args in ([],self.args()+['--dis','x'],self.args()+['--port','-1'],self.args()+['--port','65536']):
            with contextlib.redirect_stderr(io.StringIO()):self.assertEqual(64,runtime.main(args))
        with patch.object(runtime.os,'name','nt'),contextlib.redirect_stderr(io.StringIO()):self.assertEqual(2,runtime.main(self.args()))
        self.assertFalse(self.marker.exists())

    def test_missing_launcher_main_jar_links_and_special_files_are_rejected(self):
        for name in ('bin/ftckb','lib/knowledge-cli.jar'):
            path=self.dist/name;original=path.read_bytes();path.unlink()
            self.assertEqual(2,self.invoke());path.write_bytes(original)
        link=self.dist/'lib/link';link.symlink_to(self.dist/'lib/dependency.jar');self.assertEqual(2,self.invoke());link.unlink()
        os.mkfifo(self.dist/'fifo');self.assertEqual(2,self.invoke());self.assertFalse(self.marker.exists())

    def test_limits_cover_files_bytes_and_directory_entries(self):
        for constant,value in [('MAX_FILES',1),('MAX_BYTES',2),('MAX_ENTRIES',1)]:
            with patch.object(runtime,constant,value):self.assertEqual(2,self.invoke())
        self.assertFalse(self.marker.exists())

    def test_copy_failure_and_observed_source_drift_never_spawn_or_leave_snapshot(self):
        for failure in ('copy','drift'):
            root=self.base/failure;root.mkdir()
            real_inventory=runtime.inventory;calls=[]
            def observe(path,*args):
                value=real_inventory(path,*args);calls.append(path)
                if len(calls)==2:(self.dist/'lib/dependency.jar').write_bytes(b'changed during copy')
                return value
            with patch.object(runtime.tempfile,'mkdtemp',return_value=str(root)),patch.object(runtime.subprocess,'Popen',side_effect=AssertionError('spawn')):
                if failure=='copy':
                    with patch.object(runtime,'copy_file',side_effect=OSError('private-secret')):self.assertEqual(2,self.invoke())
                else:
                    with patch.object(runtime,'inventory',side_effect=observe):self.assertEqual(2,self.invoke())
            self.assertFalse(root.exists())

    def test_source_growth_at_first_or_later_file_is_rejected_before_write(self):
        original=runtime.copy_file
        for change_at in (1,3):
            calls=[];written=[]
            root=self.base/('growth-'+str(change_at));root.mkdir()
            def grow(source,target,mode,check,limit):
                calls.append(source)
                if len(calls)==change_at:source.write_bytes(source.read_bytes()+b'growth')
                try:return original(source,target,mode,check,limit)
                finally:written.append(target.stat().st_size if target.exists() else 0)
            before=sum(meta[1] for meta in runtime.inventory(self.dist)['files'].values())
            with patch.object(runtime.tempfile,'mkdtemp',return_value=str(root)),patch.object(runtime,'copy_file',side_effect=grow),patch.object(runtime.subprocess,'Popen',side_effect=AssertionError('spawn')):
                self.assertEqual(2,self.invoke())
            self.assertFalse(root.exists());self.assertLessEqual(sum(written),before)
            self.assertEqual(0,written[-1])

    def test_spawn_failure_cleans_only_own_directory_without_echoing_error(self):
        outside=self.base/'keep';outside.write_text('keep')
        err=io.StringIO()
        with patch.object(runtime.subprocess,'Popen',side_effect=OSError('secret-value')),patch.dict(os.environ,{'RUNTIME_MARKER':str(self.marker)}),contextlib.redirect_stderr(err):
            self.assertEqual(2,runtime.main(self.args()))
        self.assertEqual('keep',outside.read_text());self.assertNotIn('secret-value',err.getvalue())

    def test_cleanup_refuses_unowned_or_replaced_directory(self):
        outside=self.base/'unowned';outside.mkdir();(outside/'keep').write_text('keep')
        self.assertFalse(runtime.cleanup(outside));self.assertTrue((outside/'keep').exists())
        root,copy=runtime.snapshot(self.dist);moved=root.with_name(root.name+'-moved');root.rename(moved);root.mkdir()
        try:self.assertFalse(runtime.cleanup(root));self.assertTrue(moved.exists())
        finally:root.rmdir();import shutil;shutil.rmtree(moved)

    def child(self):
        env=os.environ|{'RUNTIME_MARKER':str(self.marker),'RUNTIME_IDLE':'1'}
        proc=subprocess.Popen([sys.executable,str(SCRIPTS/'dashboard_runtime.py'),*self.args()],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        self.addCleanup(lambda:proc.poll() is None and proc.kill())
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            if self.marker.exists():return proc,json.loads(self.marker.read_text())
            if proc.poll() is not None:self.fail(proc.communicate())
            time.sleep(.01)
        self.fail('child did not start')

    def test_sigterm_and_sigint_stop_only_own_child_then_clean(self):
        for signum in (signal.SIGTERM,signal.SIGINT):
            self.marker.unlink(missing_ok=True);proc,child=self.child();root=Path(child['file']).parents[2]
            self.assertTrue(root.exists());proc.send_signal(signum);out,err=proc.communicate(timeout=8)
            self.assertEqual(128+signum,proc.returncode,(out,err));self.assertFalse(root.exists());self.assertTrue(self.dist.exists())
            with self.assertRaises(ProcessLookupError):os.kill(child['pid'],0)

    def test_sigkill_leaves_live_child_and_snapshot_for_explicit_recovery(self):
        proc,child=self.child();root=Path(child['file']).parents[2]
        try:
            proc.kill();proc.wait(timeout=3);os.kill(child['pid'],0);self.assertTrue(root.exists())
        finally:
            os.kill(child['pid'],signal.SIGTERM);proc.communicate(timeout=3)
            import shutil;shutil.rmtree(root)

    def test_signal_during_copy_cleans_without_spawning(self):
        original=runtime.copy_file
        def stop_copy(*args):
            os.kill(os.getpid(),signal.SIGTERM);return original(*args)
        with patch.object(runtime,'copy_file',side_effect=stop_copy),patch.object(runtime.subprocess,'Popen',side_effect=AssertionError('spawn')):
            self.assertEqual(143,self.invoke())
        self.assertFalse(self.marker.exists())

    def test_signal_in_popen_return_window_still_owns_and_reaps_child(self):
        original=runtime.subprocess.Popen;children=[];roots=[]
        def spawn(*args,**kwargs):
            child=original(*args,**kwargs);children.append(child);roots.append(Path(args[0][1]).parents[2])
            os.kill(os.getpid(),signal.SIGTERM);return child
        with patch.object(runtime.subprocess,'Popen',side_effect=spawn):self.assertEqual(143,self.invoke())
        self.assertIsNotNone(children[0].poll());self.assertFalse(roots[0].exists())

    def test_unconfirmed_child_retains_its_snapshot(self):
        from unittest.mock import Mock
        fake=Mock();fake.poll.return_value=None;fake.wait.side_effect=subprocess.TimeoutExpired([],1)
        roots=[]
        def spawn(command,**kwargs):
            roots.append(Path(command[1]).parents[2]);os.kill(os.getpid(),signal.SIGTERM);return fake
        with patch.object(runtime.subprocess,'Popen',side_effect=spawn),patch.object(runtime,'stop_child',return_value=False):
            self.assertEqual(143,self.invoke())
        self.assertTrue(roots[0].exists());self.assertTrue(runtime.cleanup(roots[0]))

    def test_recommendation_uses_reviewed_helper_and_explicit_target_paths(self):
        argv=runtime.recommendation(self.dist,self.knowledge,platform='posix')
        self.assertEqual([sys.executable,str((SCRIPTS/'dashboard_runtime.py').resolve()),'--distribution',str(self.dist),'--knowledge',str(self.knowledge),'--no-browser'],argv)
        self.assertIsNone(runtime.recommendation(self.dist,self.knowledge,platform='nt'))
        with patch.object(runtime,'helper_path',return_value=self.base/'missing.py'):self.assertIsNone(runtime.recommendation(self.dist,self.knowledge,platform='posix'))


if __name__=='__main__':unittest.main()
