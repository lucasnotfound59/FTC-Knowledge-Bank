# Integration Progress and Timeouts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让固定版本安装过程中始终有可理解的阶段信息，并在 Git 网络超时后返回可诊断、不会破坏用户文件的失败。

**Architecture:** 新增 stdlib-only 的子进程执行器，统一超时、进度与进程树清理；安装器显式标记哪些调用属于网络操作。stdout 保持最终 JSON，阶段日志只进 stderr，既有 pin/托管文件/幂等逻辑不变。

**Tech Stack:** Python 3.10+、subprocess、time、signal、unittest、现有 Git CLI；不新增 Python 依赖。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-09-08-rule-applicability-priority-design.md` §9；先完成 `2026-09-08-rule-kernel-v2.md` 的协议接入闭环，再执行本计划。
- 在 stderr 输出阶段；stdout 仍只有最终 JSON。
- 长操作每 15 秒输出等待提示；网络默认每次 300 秒，本地 Git 每次 30 秒；Gradle 构建不误用网络超时值。
- 超时终止该操作及其下载子进程；无法确认清理完成时明确报 cleanup failure，不宣称终止成功。
- 不自动删除半成品、重置工作区或强制覆盖托管文件；不自动 commit/push，不切换用户分支。
- --dry-run 不改目标文件、index 或 Git 配置，不构建；允许已披露的临时下载预检。
- 不记录含凭据的 URL、完整环境或模型密钥，不配置代理／VPN／全局 Git 凭据。
- 本计划仅改善现有 Git 下载，不实现 Agent 官方文档联网检索；联网事项先在 todolist.md 登记。
- Windows 与 POSIX 清理分别验证；无 Windows 原生证据就明确未实测。

---

所有文件路径相对于 `/Users/xinlu/Documents/ChatGPT/FTC Knowledge bank`。先记录当前 HEAD/staged/unstaged，不覆盖网站或其他任务的提交。每任务完成运行相应测试与 `git diff --check`，仅提交该任务文件；不能 `git add .`。代码块为待落地代码，不是已验证实现。

## 文件与接口

- Create `.agents/skills/ftckb-integrate/scripts/process_runner.py`：`run_process(args,*,cwd=None,check=True,stage,timeout_seconds,heartbeat_seconds=15,git_auth=False,stream=None)` 返回 subprocess.CompletedProcess；唯一进程清理函数 `terminate_tree(process)`。
- Modify `project.py`：本地 git 显式指定 30 秒；kernel 调用不借用 Git 超时；build_cli 保留 Gradle 输出。
- Modify `integrate.py`：网络调用显式指定 args.network_timeout，向 resolve_ref/pinned_source 传递，不用全局可变超时。
- Modify `verify.py`：验证阶段 stderr 提示；保留安装有效与项目检查结果分离。
- Create `tests/integration/test_process_runner.py`；扩展 `test_integration.py`。
- Update `docs/project-integration.md`、安装 Skill、`todolist.md`；本轮不新增自动化、计划任务或后台监控。

## Task 1: 可终止的有界子进程

**Interfaces:** Consumes 主计划的 integration_contract.IntegrationError；Produces run_process 和 terminate_tree。每个操作单独有 deadline；不是整个安装总计 300 秒。

- [ ] 写新测试文件的成功／失败 RED，用 sys.executable 执行本地脚本，不访问网络：

```python
import io
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/".agents/skills/ftckb-integrate/scripts"))
from integration_contract import IntegrationError
from process_runner import run_process

class ProcessRunnerTest(unittest.TestCase):
    def test_stdout_is_preserved_and_stage_goes_to_stderr(self):
        logs=io.StringIO()
        result=run_process([sys.executable,"-c","print('{\"ok\":true}')"],
                           stage="fixture",timeout_seconds=5,stream=logs)
        self.assertTrue(json.loads(result.stdout)["ok"])
        self.assertIn("fixture",logs.getvalue())
        self.assertNotIn('{"ok":true}',logs.getvalue())

    def test_timeout_is_not_a_success(self):
        logs=io.StringIO()
        with self.assertRaisesRegex(IntegrationError,"timed out"):
            run_process([sys.executable,"-c","import time; time.sleep(30)"],
                        stage="download fixture",timeout_seconds=0.3,
                        heartbeat_seconds=0.1,stream=logs)
        self.assertIn("waiting",logs.getvalue())
```

- [ ] RED：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p test_process_runner.py -v`。
- [ ] 实现清理函数，不能只调用 parent.kill：

```python
import math
import os
import signal
import subprocess
import sys
import time
from integration_contract import IntegrationError


def _terminate_tree(process):
    if os.name=="nt":
        result=subprocess.run(["taskkill","/PID",str(process.pid),"/T","/F"],
                              stdin=subprocess.DEVNULL,capture_output=True,timeout=10)
        if result.returncode:
            raise IntegrationError("Process-tree cleanup failed; inspect the reported installation phase before retrying")
    else:
        try:
            os.killpg(process.pid,signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            return process.communicate(timeout=2)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError:
                pass
    try:
        return process.communicate(timeout=5)
    except subprocess.TimeoutExpired as error:
        raise IntegrationError("Process-tree cleanup did not complete; do not assume the download stopped") from error


def terminate_tree(process):
    try:
        return _terminate_tree(process)
    except (OSError,subprocess.TimeoutExpired) as error:
        raise IntegrationError("Process-tree cleanup could not be confirmed; inspect this operation before retrying") from error
```

Windows taskkill 失败必须保留 cleanup failure，不退化成“仅杀父进程但报告成功”。它不是所有脱离进程树场景的保证：若父进程提前退出而后台子进程持有输出管道，测试必须暴露无法确认清理的状态；没有原生 Windows 证据不能宣称该场景被可靠清理。POSIX 的清理按本次新 session/process group 执行，不用全局进程名搜索或宽泛 kill。

- [ ] 实现运行器；日志仅使用调用方指定的固定阶段名，不打印 args/env 或原始网络 stderr：

```python
def run_process(args,*,cwd=None,check=True,stage,timeout_seconds,
                heartbeat_seconds=15,git_auth=False,stream=None):
    if any(type(value) not in (int,float) or not math.isfinite(value) or value<=0
           for value in (timeout_seconds,heartbeat_seconds)):
        raise IntegrationError("Timeout and heartbeat must be positive")
    output=sys.stderr if stream is None else stream
    print(f"[ftckb] {stage}",file=output,flush=True)
    env=os.environ.copy()
    if git_auth:
        env["GIT_TERMINAL_PROMPT"]="0"
    options={"creationflags":subprocess.CREATE_NEW_PROCESS_GROUP} if os.name=="nt" else {"start_new_session":True}
    process=subprocess.Popen([str(value) for value in args],cwd=cwd,env=env,
                             stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                             text=True,encoding="utf-8",errors="replace",**options)
    started=time.monotonic()
    deadline=started+timeout_seconds
    cleanup_attempted=False
    try:
        while True:
            remaining=deadline-time.monotonic()
            if remaining<=0:
                cleanup_attempted=True
                terminate_tree(process)
                raise IntegrationError(f"{stage} timed out after {timeout_seconds:g}s; inspect git status before retrying")
            try:
                stdout,stderr=process.communicate(timeout=min(heartbeat_seconds,remaining))
                break
            except subprocess.TimeoutExpired:
                elapsed=time.monotonic()-started
                print(f"[ftckb] {stage}: waiting ({elapsed:.0f}s)",file=output,flush=True)
    except KeyboardInterrupt:
        cleanup_attempted=True
        terminate_tree(process)
        raise IntegrationError(f"{stage} interrupted; inspect git status before retrying")
    except BaseException:
        if not cleanup_attempted:
            terminate_tree(process)
        raise
    result=subprocess.CompletedProcess(args,process.returncode,stdout,stderr)
    if check and result.returncode:
        raise IntegrationError(f"{stage} failed (exit {result.returncode}); verify Git access and inspect git status")
    return result
```

`subprocess.Popen` 启动失败由现有入口 OSError 捕获并输出 JSON；清理函数的 TimeoutExpired/OSError 也在 runner 转成 IntegrationError，说明清理未确认。deadline 到期和等待管道的超时采用同一路径，不因为 parent.poll()!=None 就跳过清理。测试内部允许小数秒；用户 CLI 参数仅接受正整数。

- [ ] 补充单测：check=False 返回原 exit code；非零退出包含阶段而不泄漏原 stderr；git_auth 令子进程看到 GIT_TERMINAL_PROMPT=0；超时清理一次；Ctrl-C 清理；Windows taskkill 参数与失败分支 mock。增加 POSIX 真实子进程测试：父进程启动一个持有输出管道的子进程，超时后断言已不能向该子进程发送存活信号；测试使用临时 PID 文件，并在 finally 清理本测试的精确 PID。
- [ ] GREEN 运行同一命令。标准成功不能只看异常类型，必须验证无仍在运行的测试子进程；Windows native 用同一文件跑 `py -3 -m unittest discover -s tests/integration -p test_process_runner.py -v`，不可用 mock 代替 native 结论。
- [ ] 本地检查点：`feat(integration): bound subprocesses and clean up timed-out downloads`。

## Task 2: 安装阶段、显式网络超时和失败恢复

**Files:** Modify integrate.py/project.py/verify.py；Test tests/integration/test_integration.py。

**Interfaces:** `resolve_ref(repository,ref,network_timeout=300)`、`pinned_source(repository,commit,existing=None,network_timeout=300)`。新增 `network_git(root,*args,timeout_seconds,stage,check=True)`，调用 run_process；原 git 仍保留 --no-optional-locks。

- [ ] 写 RED：参数 --network-timeout=0、负数、非数字必须失败且目标 snapshot 不变；dry-run 捕获日志包含预检阶段但 target snapshot 完全相同。网络超时通过 patch run_process 抛 IntegrationError 模拟，不访问 GitHub。
- [ ] RED：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p test_integration.py -v`。
- [ ] 添加 CLI 类型函数及网络封装：

```python
def positive_timeout(value):
    try:
        number=int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("network timeout must be a positive integer") from error
    if number<=0:
        raise argparse.ArgumentTypeError("network timeout must be a positive integer")
    return number


def network_git(root,*args,timeout_seconds,stage,check=True):
    return run_process(["git","--no-optional-locks","-C",root,*args],
                       check=check,stage=stage,timeout_seconds=timeout_seconds,git_auth=True)
```

parser 增加 `parser.add_argument("--network-timeout",type=positive_timeout,default=300)`。本地 git 改用 run_process，timeout_seconds=30，固定 stage="Inspect local Git state"；网络函数绝不能借用本地 30 秒。

- [ ] 显式替换网络调用，保持 --no-optional-locks 与所有 `--` 分隔：

| 原位置 | 新阶段／超时 |
| --- | --- |
| resolve_ref 的 git ls-remote | Resolve pinned version / network_timeout |
| pinned_source 的 clone | Download pinned source / network_timeout |
| pinned_source 缺对象时 fetch | Fetch pinned commit / network_timeout |
| submodule add/update --init | Initialize knowledge submodule / args.network_timeout |
| 目标 source_path 缺对象时 fetch | Fetch installed commit / args.network_timeout |

例如 ls-remote：

```python
result=run_process(["git","ls-remote","--tags","--",repository,tag,tag+"^{}"],
                   stage="Resolve pinned version",timeout_seconds=network_timeout,git_auth=True)
```

clone 仍使用临时目录与 no-hardlinks，checkout 仍固定完整 commit。已安装且缓存可用的 dry-run 不强制新增网络访问。

- [ ] 在安装器主流程添加固定阶段提示，记录“开始目标写入前／后”的边界；异常 JSON 附 phase、targetMayHaveChanged、recovery。不把临时目录下载失败说成目标项目已损坏。verify 每次 validate/resolve/check 前给固定阶段提示；build_cli 仍让 Gradle 流向 stderr，不套 300 秒网络超时。

恢复文案根据阶段区分：

```text
预检失败：目标尚未修改；检查固定版本、访问权限和 profile 参数后重试。
submodule 阶段失败：目标 Git 元数据可能已改变；先检查 git status、.gitmodules 和 tools/FTC-Knowledge-Bank，不执行 reset 或自动删除。
构建失败：已生成配置保留；修复本机 JDK、依赖下载或缓存权限后用同一参数重试。
验收硬违规：安装有效但项目检查未通过；报告违规，不重新下载或改变规则绕过。
清理未确认：安装未完成；先确认本次下载进程状态，不能声称全部已停止。
```

- [ ] 测试 stdout 只有 JSON；原始 stderr 含模拟 `https://user:secret@example.invalid/repo` 时错误日志不包含 user/secret/URL；在目标写入前后的模拟超时分别验证 recovery，不抹掉用户 staged/unstaged 内容。
- [ ] GREEN：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v`。
- [ ] 本地检查点：`feat(integration): show stages and expose bounded network retries`。

## Task 3: 文档、跨平台边界和完整回归

**Files:** Modify docs/project-integration.md、todolist.md、.agents/skills/ftckb-integrate/SKILL.md；Test test_process_runner.py/test_integration.py。

**Interfaces:** 使用现有明确 pin/team/season/profile 参数，仅增加可选 timeout；不改变规则或 kernel schema。

- [ ] 增加安装帮助与新参数验收断言：

```python
def test_help_describes_network_timeout(self):
    result=command(sys.executable,ROOT/BUNDLE/"scripts/integrate.py","--help")
    self.assertIn("--network-timeout",result.stdout)
```

- [ ] 更新文档：默认 300 秒是每个网络操作，不是总安装时间；stderr 阶段不属于 JSON；说明凭据应通过 Git credential manager 配置，不能把 key 拼进 URL；列出任务 2 的恢复边界。修改 Skill 前读取 skill-creator 要求的指令并完成验证。
- [ ] 完整测试：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
FTCKB_REAL_INTEGRATION=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p test_integration.py -k test_real_cli_from_pinned_source -v
git diff --check
```

真实 CLI 测试仍在临时项目执行。无需为了测试联网超时去下载巨大仓库，网络超时路径用受控子进程和模拟网络调用验证。若额外做真实远程下载，仅用公开仓库，记录该单次结果，不声称所有国内网络环境均可达。

- [ ] 验收记录分为 POSIX 真实进程清理、Windows 命令构造/mock、Windows native 三列；未执行的一列写未验证。若某个平台清理不能确认，输出失败并保留限制，不能仅把测试 skip 就宣布该平台完整支持。
- [ ] 本地检查点：`docs(test): document progress timeout and recovery guarantees`。不推送、不创建 tag、不修改真实机器人项目。

## 完成判据

网络阶段不再静默等待；超时、用户中断、启动失败和清理失败都返回明确结果；用户原文件和 staged 状态不受恢复操作破坏；原 fixed-pin/dry-run/幂等/Schema/硬检查用例保持通过。上述结果有测试证据后才勾选路线图，不把本计划本身当作实现完成。
