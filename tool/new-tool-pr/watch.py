#!/usr/bin/env python3
"""
Runs Claude Code for run.sh and tells what it is doing, a line at a time, while keeping everything it says in a
log. It also stops the run at once if Claude Code starts up paying from anywhere but the key it was handed (a
subscription's sign-in on this machine, say), or without the Workflow tool the run is built on.

    python3 watch.py --log DIR [--hours N] [--budget DOLLARS] -- claude -p ... --output-format stream-json --verbose
"""
import json
import os
import signal
import subprocess
import sys
import threading
import time

KEY_SOURCES = ("ANTHROPIC_API_KEY", "apiKeyHelper")


def short(text, most=220):
    text = " ".join(str(text).split())
    return text if len(text) <= most else text[: most - 1] + "…"


def tool_line(block):
    name, given = block.get("name", "?"), block.get("input") or {}
    if name == "Bash":
        return "$ " + short(given.get("command", ""), 160)
    if name == "Workflow":
        return "Workflow: " + short(given.get("scriptPath") or given.get("name") or "inline script", 160)
    if name in ("Read", "Edit", "Write"):
        return name + " " + short(given.get("file_path", ""), 160)
    return name


def main():
    argv = sys.argv[1:]
    cut = argv.index("--")
    mine, command = argv[:cut], argv[cut + 1:]
    log_dir = mine[mine.index("--log") + 1]
    hours = float(mine[mine.index("--hours") + 1]) if "--hours" in mine else 8.0
    budget = float(mine[mine.index("--budget") + 1]) if "--budget" in mine else None
    os.makedirs(log_dir, exist_ok=True)

    started = time.time()
    child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=open(os.path.join(log_dir, "claude.err"), "w"), text=True, bufsize=1)

    def stop(why, code):
        print("\nStopped: " + why, flush=True)
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
            if child.poll() is not None:
                break
            child.send_signal(sig)
            try:
                child.wait(timeout=20)
            except subprocess.TimeoutExpired:
                pass
        sys.exit(code)

    # A run that has gone on past its hours is stopped, whatever it is doing: nobody is watching it.
    def clock():
        while child.poll() is None:
            if time.time() - started > hours * 3600:
                print("\nStopped: the run passed its %.1f hours." % hours, flush=True)
                child.send_signal(signal.SIGTERM)
                return
            time.sleep(20)
    threading.Thread(target=clock, daemon=True).start()
    signal.signal(signal.SIGINT, lambda *_: stop("interrupted.", 130))
    signal.signal(signal.SIGTERM, lambda *_: stop("terminated.", 143))

    last = None
    seen = {}
    with open(os.path.join(log_dir, "log.jsonl"), "w") as log:
        for line in child.stdout:
            log.write(line)
            log.flush()
            try:
                event = json.loads(line)
            except ValueError:
                continue
            kind, sub = event.get("type"), event.get("subtype")
            stamp = time.strftime("%H:%M:%S")
            if kind == "system" and sub == "init":
                source = event.get("apiKeySource")
                tools = event.get("tools") or []
                print("%s Claude Code %s, model %s, paying with %s, %d tools, permission mode %s" % (
                    stamp, event.get("claude_code_version"), event.get("model"), source, len(tools), event.get("permissionMode")), flush=True)
                if source not in KEY_SOURCES:
                    stop("Claude Code is not paying with the key it was handed (it says: %s). Nothing was asked of it." % source, 3)
                if "Workflow" not in tools:
                    stop("this Claude Code has no Workflow tool; a newer one is needed (set CLAUDE_BIN).", 4)
            elif kind == "assistant" and not event.get("parent_tool_use_id"):
                refused = str(event.get("error") or "")
                if refused == "billing_error":
                    stop("the key was accepted, but the organization it belongs to has no credit to spend (the API says: "
                         "credit balance is too low). Nothing was spent. In console.anthropic.com, look at Billing for the "
                         "organization this key is from, or put a key from the organization that holds the credit in .env.", 5)
                if "auth" in refused or "invalid_api_key" in refused:
                    stop("the API did not accept the key (%s). Nothing was spent. Check the CLAUDE_KEY line in .env." % refused, 6)
                for block in (event.get("message") or {}).get("content") or []:
                    if block.get("type") == "text" and block.get("text", "").strip():
                        print("%s %s" % (stamp, short(block["text"], 600)), flush=True)
                    elif block.get("type") == "tool_use":
                        print("%s → %s" % (stamp, tool_line(block)), flush=True)
            elif kind == "system" and sub == "task_progress":
                # One line each time the workflow moves on to something else, not one for every step an agent takes.
                doing = str(event.get("description") or "")
                if doing and doing not in seen.setdefault(event.get("task_id"), set()):
                    seen[event.get("task_id")].add(doing)
                    print("%s · %s" % (stamp, short(doing, 200)), flush=True)
            elif kind == "system" and sub in ("task_started", "task_notification"):
                print("%s · %s: %s" % (stamp, "started" if sub == "task_started" else event.get("status", "ended"),
                                       short(event.get("description") or event.get("summary") or event.get("task_id"), 200)), flush=True)
            elif kind == "system" and sub in ("background_tasks_changed", "task_updated", "session_title_changed"):
                pass
            elif kind == "system":
                rest = {k: v for k, v in event.items() if k not in ("type", "subtype", "uuid", "session_id")}
                print("%s · %s %s" % (stamp, sub, short(json.dumps(rest), 200)), flush=True)
            elif kind == "result":
                last = event
                cost = event.get("total_cost_usd")
                print("%s turn ended (%s%s), $%s so far" % (
                    stamp, sub, ", error" if event.get("is_error") else "", "%.2f" % cost if isinstance(cost, (int, float)) else "?"), flush=True)
    code = child.wait()

    minutes = (time.time() - started) / 60
    if last is None:
        print("\nClaude Code ended with nothing to say (exit %d). See %s/claude.err" % (code, log_dir))
        sys.exit(code or 1)
    with open(os.path.join(log_dir, "result.txt"), "w") as out:
        out.write(str(last.get("result", "")))
    cost = last.get("total_cost_usd")
    print("\nDone in %.0f minutes, $%s. The last thing it said is in %s/result.txt:\n" % (
        minutes, "%.2f" % cost if isinstance(cost, (int, float)) else "?", log_dir))
    print(str(last.get("result", ""))[:4000])
    # At the cap Claude Code stops the workflow and ends quietly, with whatever it last said: say so for it.
    if budget and isinstance(cost, (int, float)) and cost >= budget * 0.98:
        print("\nStopped at the spending cap of $%.2f: the work was cut off where it was." % budget)
        sys.exit(7)
    sys.exit(1 if last.get("is_error") else code)


if __name__ == "__main__":
    main()
