#!/usr/bin/env python3
"""Run a POSIX Dashboard from a verified, private distribution snapshot."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tempfile

sys.dont_write_bytecode=True
MAX_FILES=512
MAX_BYTES=256*1024*1024
MAX_ENTRIES=2048
STOP_TIMEOUT=5
_OWNED={}


class RuntimeErrorBoundary(Exception):pass
class RuntimeInterrupted(Exception):pass


def helper_path():return Path(__file__).resolve()


def recommendation(distribution,knowledge,platform=None):
    """Use this reviewed helper, never a helper guessed inside the target pin."""
    if (os.name if platform is None else platform)!='posix' or not helper_path().is_file():return None
    return [sys.executable,str(helper_path()),'--distribution',str(distribution),'--knowledge',str(knowledge),'--no-browser']



def audit_environment(project_root):
    """Choose an explicit workflow path; later identity checks verify JVM reality."""
    selected=os.environ.get('FTCKB_AUDIT_DIR')
    path=Path(selected) if selected is not None and selected.strip() else Path.home()/'.ftckb/audit'
    if not path.is_absolute():path=Path(project_root)/path
    return {'FTCKB_AUDIT_DIR':str(Path(os.path.abspath(path)).resolve())}


def interrupted(check):
    if check is not None and check():raise RuntimeInterrupted()


def read_chunks(path,check=None):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    with os.fdopen(fd,'rb') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):raise RuntimeErrorBoundary()
        total=0
        while True:
            interrupted(check);chunk=stream.read(65536)
            if not chunk:break
            total+=len(chunk)
            if total>MAX_BYTES:raise RuntimeErrorBoundary()
            yield chunk


def inventory(root,check=None):
    """Bounded byte inventory; reject links and special filesystem objects."""
    root=Path(root)
    if not stat.S_ISDIR(root.lstat().st_mode):raise RuntimeErrorBoundary()
    files={};directories=[];entries=0;total=0;pending=[root]
    while pending:
        directory=pending.pop()
        children=[]
        with os.scandir(directory) as iterator:
            for entry in iterator:
                interrupted(check);entries+=1
                if entries>MAX_ENTRIES:raise RuntimeErrorBoundary()
                children.append(entry)
        for entry in sorted(children,key=lambda value:value.name):
            path=Path(entry.path);info=entry.stat(follow_symlinks=False);relative=path.relative_to(root).as_posix()
            if stat.S_ISDIR(info.st_mode):directories.append(relative);pending.append(path)
            elif stat.S_ISREG(info.st_mode):
                if len(files)>=MAX_FILES or info.st_size>MAX_BYTES-total:raise RuntimeErrorBoundary()
                checksum=hashlib.sha256();size=0
                for chunk in read_chunks(path,check):
                    checksum.update(chunk);size+=len(chunk)
                    if size>MAX_BYTES-total:raise RuntimeErrorBoundary()
                total+=size;files[relative]=(checksum.hexdigest(),size,stat.S_IMODE(info.st_mode)&0o777)
            else:raise RuntimeErrorBoundary()
    if not {'bin/ftckb','lib/knowledge-cli.jar'}.issubset(files):raise RuntimeErrorBoundary()
    return {'files':files,'directories':sorted(directories)}


def copy_file(source,target,mode,check=None,limit=None):
    written=0
    with target.open('xb') as output:
        for chunk in read_chunks(source,check):
            written+=len(chunk)
            if written>(MAX_BYTES if limit is None else limit):raise RuntimeErrorBoundary()
            output.write(chunk)
    target.chmod(mode)


def cleanup(root):
    """Delete only a directory created here whose identity still matches."""
    root=Path(root);identity=_OWNED.get(str(root))
    if identity is None:return False
    try:
        info=root.lstat()
        if not stat.S_ISDIR(info.st_mode) or (info.st_dev,info.st_ino,info.st_uid)!=identity:return False
        shutil.rmtree(root);_OWNED.pop(str(root),None);return True
    except OSError:return False


def snapshot(distribution,check=None):
    distribution=Path(distribution);before=inventory(distribution,check)
    root=Path(tempfile.mkdtemp(prefix='ftckb-dashboard-'));info=root.lstat();_OWNED[str(root)]=(info.st_dev,info.st_ino,info.st_uid)
    try:
        root.chmod(0o700);copy=root/'distribution';copy.mkdir(mode=0o700)
        for name in before['directories']:
            interrupted(check);(copy/name).mkdir(mode=0o700)
        for name,(_,size,mode) in before['files'].items():
            interrupted(check);copy_file(distribution/name,copy/name,mode,check,size)
        copied=inventory(copy,check);after=inventory(distribution,check)
        if before!=copied or before!=after:raise RuntimeErrorBoundary()
        interrupted(check);return root,copy
    except BaseException:
        if not cleanup(root):print('dashboard runtime: retained snapshot '+str(root),file=sys.stderr)
        raise


class Parser(argparse.ArgumentParser):
    def error(self,message):raise RuntimeErrorBoundary()


def port(value):
    number=int(value)
    if not 0<=number<=65535:raise ValueError()
    return number


def stop_child(child,signum):
    if child.poll() is not None:return True
    try:child.send_signal(signum)
    except ProcessLookupError:pass
    try:child.wait(timeout=STOP_TIMEOUT);return True
    except subprocess.TimeoutExpired:
        try:child.kill()
        except ProcessLookupError:pass
        try:child.wait(timeout=STOP_TIMEOUT);return True
        except subprocess.TimeoutExpired:return False


def main(argv=None):
    parser=Parser(description=__doc__,allow_abbrev=False)
    parser.add_argument('--distribution',required=True);parser.add_argument('--knowledge',required=True)
    parser.add_argument('--port',type=port);parser.add_argument('--no-browser',action='store_true')
    try:args=parser.parse_args(argv)
    except (RuntimeErrorBoundary,ValueError):
        print('dashboard runtime: invalid arguments; see --help',file=sys.stderr);return 64
    if os.name!='posix':
        print('dashboard runtime: protected startup is unsupported on this platform; original CLI remains available',file=sys.stderr);return 2
    root=None;child=None;received=[None];previous={}
    def receive(signum,frame):
        # Never raise between Popen creating the child and returning its handle.
        if received[0] is None:received[0]=signum
    try:
        for signum in (signal.SIGINT,signal.SIGTERM):previous[signum]=signal.signal(signum,receive)
        distribution=Path(args.distribution)
        if distribution.is_symlink():raise RuntimeErrorBoundary()
        distribution=distribution.resolve(strict=True);knowledge=Path(args.knowledge).resolve(strict=True)
        if not knowledge.is_dir():raise RuntimeErrorBoundary()
        root,copy=snapshot(distribution,lambda:received[0] is not None);interrupted(lambda:received[0] is not None)
        print('dashboard runtime snapshot='+str(root),file=sys.stderr,flush=True)
        command=['sh',str(copy/'bin/ftckb'),'dashboard','--knowledge',str(knowledge)]
        if args.port is not None:command+=['--port',str(args.port)]
        if args.no_browser:command.append('--no-browser')
        # Gradle's POSIX launcher execs the JVM; isolate terminal Ctrl-C delivery.
        child=subprocess.Popen(command,start_new_session=True)
        while child.poll() is None:
            if received[0] is not None:
                stop_child(child,received[0]);return 128+received[0]
            try:child.wait(timeout=.1)
            except subprocess.TimeoutExpired:pass
        return 128+received[0] if received[0] is not None else (child.returncode if child.returncode>=0 else 128-child.returncode)
    except RuntimeInterrupted:return 128+(received[0] or signal.SIGINT)
    except (RuntimeErrorBoundary,OSError,ValueError):
        print('dashboard runtime: snapshot or startup failed; no existing service was changed',file=sys.stderr);return 2
    finally:
        if child is not None and child.poll() is None:stop_child(child,received[0] or signal.SIGTERM)
        if root is not None:
            if child is not None and child.poll() is None:print('dashboard runtime: child exit unconfirmed; retained snapshot '+str(root),file=sys.stderr)
            elif not cleanup(root):print('dashboard runtime: retained snapshot '+str(root),file=sys.stderr)
        for signum,handler in previous.items():signal.signal(signum,handler)


if __name__=='__main__':sys.exit(main())
