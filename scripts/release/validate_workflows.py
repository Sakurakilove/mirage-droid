#!/usr/bin/env python3
"""GitHub Actions workflow 静态校验（PyYAML，无第三方依赖）。

用途：CI（host-tests）与本地（Termux）都能跑，捕获 actionlint 之外最常见的
结构性错误：YAML 语法、顶层结构、job/step 必需字段、workflow_dispatch 输入定义、
secrets 出现在 if 条件中（GitHub 不允许）等。

用法：
    python3 scripts/release/validate_workflows.py [--root <仓库根>] [-q]

退出码：0 = 全部通过（可能有 warning）；1 = 有 error。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("需要 PyYAML：python3 -m pip install pyyaml\n")
    raise SystemExit(2)


# GitHub Actions 的 workflow 里 `on` 是关键字，但 YAML 1.1 会把裸 on/off/yes/no
# 解析成 bool。这里去掉这些隐式解析器，让 `on:` 保持字符串，才能正常做结构校验。
class WorkflowLoader(yaml.SafeLoader):  # type: ignore[misc]
    pass


WorkflowLoader.yaml_implicit_resolvers = {
    key: list(resolvers)
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
for _first_char in "oOyY":
    WorkflowLoader.yaml_implicit_resolvers.pop(_first_char, None)
# `n`/`N` 只影响 no 的 bool 解析；保留其余（无需额外处理 null）
WorkflowLoader.yaml_implicit_resolvers.pop("n", None)
WorkflowLoader.yaml_implicit_resolvers.pop("N", None)


VALID_ON_KEYS = {
    "branch_protection_rule",
    "check_run",
    "check_suite",
    "create",
    "delete",
    "deployment",
    "deployment_status",
    "discussion",
    "discussion_comment",
    "fork",
    "gollum",
    "issue_comment",
    "issues",
    "label",
    "merge_group",
    "milestone",
    "page_build",
    "project",
    "project_card",
    "project_column",
    "public",
    "pull_request",
    "pull_request_review",
    "pull_request_review_comment",
    "pull_request_target",
    "push",
    "registry_package",
    "release",
    "repository_dispatch",
    "schedule",
    "status",
    "watch",
    "workflow_call",
    "workflow_dispatch",
    "workflow_run",
}

DISPATCH_INPUT_TYPES = {"string", "choice", "boolean", "environment", "number"}

PERMISSION_LEVELS = {"read", "write", "none"}
PERMISSION_SCOPES = {
    "actions", "attestations", "checks", "contents", "deployments",
    "discussions", "id-token", "issues", "models", "packages", "pages",
    "pull-requests", "repository-projects", "security-events", "statuses",
}

# 已知 action 的本地约定：必须带 @ref，防止误写 floating 引用之外的语法错误。
REQUIRED_ACTION_REF = 1  # owner/repo[/path]@ref


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, where: str, msg: str) -> None:
        self.errors.append(f"{where}: {msg}")

    def warn(self, where: str, msg: str) -> None:
        self.warnings.append(f"{where}: {msg}")


def _scalar_str(value: object) -> bool:
    # block scalar（run: |）天然带尾随换行，只要求是去空白后非空字符串
    return isinstance(value, str) and value.strip() != ""


def check_on(report: Report, where: str, on: object) -> None:
    if isinstance(on, str):
        if on not in VALID_ON_KEYS:
            report.error(where, f"未知的 on 触发器: {on!r}")
        return
    if isinstance(on, list):
        for item in on:
            if not isinstance(item, str):
                report.error(where, f"on 列表元素必须是字符串: {item!r}")
            elif item not in VALID_ON_KEYS:
                report.error(where, f"未知的 on 触发器: {item!r}")
        return
    if not isinstance(on, dict):
        report.error(where, "on 必须是字符串 / 字符串列表 / mapping")
        return

    for trigger, config in on.items():
        if not isinstance(trigger, str):
            report.error(where, f"on 的 key 必须是字符串: {trigger!r}")
            continue
        if trigger not in VALID_ON_KEYS:
            report.error(where, f"未知的 on 触发器: {trigger!r}")
        if trigger == "schedule":
            if not isinstance(config, list) or not config:
                report.error(where, "schedule 必须是非空列表")
                continue
            for entry in config:
                if not isinstance(entry, dict) or not _scalar_str(entry.get("cron")):
                    report.error(where, "schedule 元素必须是带 cron: 的 mapping")
        if trigger == "workflow_dispatch":
            if config is None:
                continue
            if not isinstance(config, dict):
                report.error(where, "workflow_dispatch 必须是 mapping（或留空）")
                continue
            inputs = config.get("inputs")
            if inputs is None:
                continue
            if not isinstance(inputs, dict):
                report.error(where, "workflow_dispatch.inputs 必须是 mapping")
                continue
            for name, spec in inputs.items():
                iw = f"{where} inputs.{name}"
                if not _scalar_str(name):
                    report.error(iw, "输入名必须是非空字符串")
                if not isinstance(spec, dict):
                    report.error(iw, "输入定义必须是 mapping")
                    continue
                itype = spec.get("type")
                if itype is not None:
                    if itype not in DISPATCH_INPUT_TYPES:
                        report.error(iw, f"未知的输入类型: {itype!r}")
                    if itype == "choice":
                        options = spec.get("options")
                        if not isinstance(options, list) or not options:
                            report.error(iw, "choice 类型必须提供非空 options 列表")
                if "required" in spec and not isinstance(spec["required"], bool):
                    report.error(iw, "required 必须是 bool")


def check_permissions(report: Report, where: str, permissions: object) -> None:
    if permissions is None:
        return
    if isinstance(permissions, str):
        if permissions not in {"read-all", "write-all"}:
            report.error(where, f"permissions 简写只能是 read-all/write-all: {permissions!r}")
        return
    if not isinstance(permissions, dict):
        report.error(where, "permissions 必须是字符串或 mapping")
        return
    for scope, level in permissions.items():
        if scope not in PERMISSION_SCOPES:
            report.warn(where, f"未知的 permission scope: {scope!r}")
        if level not in PERMISSION_LEVELS:
            report.error(where, f"permissions.{scope} 必须是 read/write/none: {level!r}")


def check_uses(report: Report, where: str, uses: object) -> None:
    if not _scalar_str(uses):
        report.error(where, "_uses_ 必须是字符串")
        return
    assert isinstance(uses, str)
    if uses.startswith("./") or uses.startswith("docker://"):
        return
    if "@" not in uses:
        report.error(where, f"action 引用缺少 @ref: {uses!r}")
        return
    action, ref = uses.rsplit("@", 1)
    if not ref or not action:
        report.error(where, f"action 引用非法: {uses!r}")
    elif action.count("/") < 1:
        report.error(where, f"action 引用应为 owner/repo[/path]@ref: {uses!r}")


def check_steps(report: Report, where: str, steps: object) -> None:
    if not isinstance(steps, list) or not steps:
        report.error(where, "steps 必须是非空列表")
        return
    for index, step in enumerate(steps):
        sw = f"{where} steps[{index}]"
        if not isinstance(step, dict):
            report.error(sw, "step 必须是 mapping")
            continue
        has_uses = "uses" in step
        has_run = "run" in step
        if has_uses == has_run:
            report.error(sw, "step 必须且只能有 uses 或 run 之一")
        if has_uses:
            check_uses(report, sw, step.get("uses"))
        if has_run and not _scalar_str(step.get("run")):
            report.error(sw, "run 必须是非空字符串")
        if "if" in step and not _scalar_str(step.get("if")):
            report.error(sw, "if 必须是非空字符串")
        if "name" in step and not _scalar_str(step.get("name")):
            report.warn(sw, "step name 建议为非空字符串")


def check_jobs(report: Report, where: str, jobs: object) -> None:
    if not isinstance(jobs, dict) or not jobs:
        report.error(where, "jobs 必须是非空 mapping")
        return
    for job_id, job in jobs.items():
        jw = f"{where} jobs.{job_id}"
        if not _scalar_str(job_id):
            report.error(jw, "job id 必须是非空字符串")
        if not isinstance(job, dict):
            report.error(jw, "job 必须是 mapping")
            continue
        if "uses" in job:  # 可复用 workflow，不需要 runs-on/steps
            check_uses(report, jw, job.get("uses"))
            continue
        if "runs-on" not in job:
            report.error(jw, "缺少 runs-on")
        if "steps" not in job:
            report.error(jw, "缺少 steps")
        if "strategy" in job and not isinstance(job["strategy"], dict):
            report.error(jw, "strategy 必须是 mapping")
        if "needs" in job:
            needs = job["needs"]
            if isinstance(needs, str):
                pass
            elif isinstance(needs, list) and all(isinstance(n, str) for n in needs):
                pass
            else:
                report.error(jw, "needs 必须是字符串或字符串列表")
        check_permissions(report, jw, job.get("permissions"))
        check_steps(report, jw, job.get("steps"))
        if "timeout-minutes" in job and not isinstance(job["timeout-minutes"], int):
            report.error(jw, "timeout-minutes 必须是整数")


def check_workflow(report: Report, path: Path, display: Path | None = None) -> None:
    where = str(display if display is not None else path)
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        report.error(where, f"无法读取: {exc}")
        return
    try:
        doc = yaml.load(text, Loader=WorkflowLoader)
    except yaml.YAMLError as exc:
        report.error(where, f"YAML 语法错误: {exc}")
        return
    if not isinstance(doc, dict):
        report.error(where, "workflow 顶层必须是 mapping")
        return

    if "name" in doc and not _scalar_str(doc["name"]):
        report.error(where, "name 必须是非空字符串")
    if "on" not in doc:
        report.error(where, "缺少 on 触发器")
    else:
        check_on(report, f"{where} [on]", doc["on"])
    if "jobs" not in doc:
        report.error(where, "缺少 jobs")
    else:
        check_jobs(report, where, doc["jobs"])
    check_permissions(report, f"{where} permissions", doc.get("permissions"))

    if "concurrency" in doc:
        concurrency = doc["concurrency"]
        if isinstance(concurrency, str):
            pass
        elif isinstance(concurrency, dict):
            if not _scalar_str(concurrency.get("group")):
                report.error(where, "concurrency.group 必须是非空字符串")
        else:
            report.error(where, "concurrency 必须是字符串或 mapping")

    # GitHub 明确不允许 secrets 上下文出现在 if 条件里；这里的写法在运行期会被
    # 拒绝（或静默失效），是最常见的隐性坏 workflow，所以直接判 error。
    for job_id, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        if isinstance(job.get("if"), str) and "secrets." in job["if"]:
            report.error(f"{where} jobs.{job_id}.if", "if 条件中不能引用 secrets 上下文")
        for index, step in enumerate(job.get("steps") or []):
            if isinstance(step, dict) and isinstance(step.get("if"), str) and "secrets." in step["if"]:
                report.error(
                    f"{where} jobs.{job_id}.steps[{index}].if",
                    "if 条件中不能引用 secrets 上下文（改 job-level env 再判断 env.X）",
                )

    # 常见坑：`on` 被误写成 bool True（手写 YAML 1.1 工具链时会遇到）
    if True in doc:  # type: ignore[operator]
        report.error(where, "顶层出现 bool key True，疑似把 on: 写坏（应为字符串 on）")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="校验 GitHub Actions workflow YAML")
    parser.add_argument(
        "--root",
        default=None,
        help="仓库根目录（默认按本脚本位置推断）",
    )
    parser.add_argument("-q", "--quiet", action="store_true", help="只输出错误摘要")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[2]
    workflows_dir = root / ".github" / "workflows"
    if not workflows_dir.is_dir():
        sys.stderr.write(f"找不到 workflow 目录: {workflows_dir}\n")
        return 2

    paths = sorted(workflows_dir.glob("*.yml")) + sorted(workflows_dir.glob("*.yaml"))
    if not paths:
        sys.stderr.write(f"{workflows_dir} 下没有任何 workflow\n")
        return 2

    report = Report()
    for path in paths:
        check_workflow(report, path, display=path.relative_to(root))

    for warning in report.warnings:
        sys.stderr.write(f"WARN  {warning}\n")
    for error in report.errors:
        sys.stderr.write(f"ERROR {error}\n")

    if not args.quiet:
        print(f"校验 {len(paths)} 个 workflow：{len(report.errors)} error / {len(report.warnings)} warning")
        for path in paths:
            print(f"  - {path.relative_to(root)}")
    if report.errors:
        return 1
    print("OK：所有 workflow 通过结构校验")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
