#!/usr/bin/env python3
"""
The guard of an unattended run (run.sh): asked before every tool call Claude Code makes, the agents of a workflow
included, and able to refuse it. The run has no person watching, a key in its environment, a phone that may be on
the same network and the right to push to a public repository, so what must never happen is refused here by rule
and not left to a prompt.

It is a PreToolUse hook: the call arrives as JSON on stdin ({"tool_name": ..., "tool_input": {...}}), exit 0 lets
it through, exit 2 refuses it and hands what is on stderr to the model. Anything this file fails to understand is
refused: an unattended run that cannot be checked should not go on.

    python3 guard.py --worktree DIR --repo DIR [--emulator SERIAL]     the hook
    python3 guard.py --self-test                                        its own cases
"""
import json
import os
import re
import sys


def parse_args(argv):
    opts = {"worktree": "", "repo": "", "emulator": ""}
    it = iter(argv)
    for a in it:
        if a.startswith("--") and a[2:] in opts:
            opts[a[2:]] = next(it, "")
    return opts


HOME = os.path.expanduser("~")

# Where a run may write: its own worktree, and the machine's scratch places.
SCRATCH = ("/tmp/", "/private/tmp/", "/var/folders/", "/private/var/folders/")

# Files that hold keys, and the places that hold credentials: not read, not written, not named in a command.
SECRET_PATHS = (
    re.compile(r"(^|/)\.env(\.(?!example\b)[\w.-]+)?$"),
    re.compile(r"(^|/)\.(ssh|aws|gnupg|netrc)(/|$)"),
    re.compile(r"(^|/)\.config/(gh|mattssoftware)(/|$)"),
    re.compile(r"(^|/)Library/Keychains(/|$)"),
    re.compile(r"(^|/)\.claude(\.json|/\.credentials\.json)$"),
)


def secret_path(path):
    return any(p.search(path) for p in SECRET_PATHS)


def inside(path, root):
    root = root.rstrip("/")
    return bool(root) and (path == root or path.startswith(root + "/"))


def mentions(command, root):
    """The directory itself named in a command, and not a sibling whose name begins the same way."""
    root = root.rstrip("/")
    return bool(root) and re.search(re.escape(root) + r"(?![\w.-])", command) is not None


def check_file(tool, path, opts):
    if not path:
        return None
    path = os.path.normpath(os.path.expanduser(path))
    if secret_path(path):
        return "that file holds keys or credentials, which a run never opens"
    if inside(path, opts["repo"]):
        return "the main checkout belongs to another session; this run works only in its own worktree " + opts["worktree"]
    if tool in ("Edit", "Write", "NotebookEdit", "MultiEdit"):
        if "/tool/new-tool-pr/" in path + "/":
            return "the run's own scripts are not part of the work"
        if not (inside(path, opts["worktree"]) or path.startswith(SCRATCH)):
            return "a run writes only in its worktree " + opts["worktree"] + " and in /tmp"
    return None


# Each rule: what is looked for in a shell command, and what to say. Looked for anywhere in the command, so a
# compound command is refused for any one part of it.
SHELL_RULES = [
    # keys and the environment
    (r"(^|[\s'\"=/])\.env(?!\.example)(?![\w-])", "a .env file holds a key; never read, copied or named"),
    (r"ANTHROPIC_API_KEY|ANTHROPIC_AUTH_TOKEN|CLAUDE_KEY|CLAUDE_CODE_OAUTH_TOKEN", "the run's key is not to be read or printed"),
    (r"(^|[;&|(]\s*)(env|printenv|set)\s*($|[;&|)>])|\bprintenv\b|\bexport\s+-p\b|\bdeclare\s+-x\b|/proc/\S*/environ|os\.environ\b(?!\.get\(['\"](HOME|PATH|JAVA_HOME|ANDROID_HOME|TMPDIR)['\"])", "the environment holds the run's key; do not print it"),
    (r"\bgh\s+auth\b|\bsecurity\s+(find|dump|export)\b|(^|[\s'\"=])~?/?\.ssh/|id_(rsa|ed25519)\b|\.netrc\b|\.config/gh\b|\.config/mattssoftware\b", "credentials are off limits"),
    # other machines
    (r"(^|[;&|(]\s*)(sudo|ssh|scp|sftp|sshpass|telnet|ftp)\b", "no other machines and no root"),
    (r"\brsync\b[^|;&]*\s\S+@?\S*:[^\s/]", "no copying to other machines"),
    (r"\b(curl|wget)\b[^|;&]*\s(-d|--data\S*|-F|--form|-T|--upload-file|-X\s*(POST|PUT|PATCH|DELETE)|--request\s*(POST|PUT|PATCH|DELETE))\b", "nothing is sent anywhere; fetch pages with WebFetch"),
    # git: what must never be done to a shared, public repository
    (r"\bgit\b[^|;&]*\bpush\b[^|;&]*(\s--force\b|\s-f\b|\s--force-with-lease\b|\s--mirror\b|\s--delete\b|\s--tags\b|\s:\S)", "never force, delete or tag on push"),
    (r"\bgit\b[^|;&]*\bpush\b[^|;&]*(\smain\b|:main\b|\smaster\b|:master\b)", "never push to main: the work goes to its branch and a pull request"),
    (r"\bgit\b[^|;&]*\b(stash|rebase|clean)\b", "no stash, rebase or clean: other agents share this repository"),
    (r"\bgit\b[^|;&]*\breset\b[^|;&]*--hard\b", "no hard reset"),
    (r"\bgit\b[^|;&]*\bcheckout\b[^|;&]*\s--\s", "no throwing changes away with checkout --"),
    (r"\bgit\b[^|;&]*\brestore\b(?![^|;&]*--staged)", "no throwing changes away with restore"),
    (r"\bgit\b[^|;&]*\bcommit\b[^|;&]*(\s--amend\b|\s-a\b|\s-am\b|\s--all\b|\s--no-verify\b)", "commit by path, and never amend: git add <paths>, git commit -m ... -- <paths>"),
    (r"\bgit\b[^|;&]*\badd\b[^|;&]*(\s-A\b|\s--all\b|\s-u\b|\s\.(\s|$))", "add by path, never everything"),
    (r"\bgit\b[^|;&]*\b(tag|worktree\s+(remove|prune|move)|branch\s+-D|remote\s+(add|set-url|remove)|config\s+--(global|system)|filter-branch|gc\b|update-ref)\b", "that changes more than this branch's work"),
    (r"Co-Authored-By|Generated with \[?Claude", "no co-author trailer and no generated-with line, in commits or pull requests"),
    # GitHub: read, and open or edit this pull request; nothing else
    (r"\bgh\s+(pr\s+(merge|close|reopen)|repo\s+(delete|edit|create|rename|archive|fork|sync)|release\b|secret\b|variable\b|ssh-key\b|gpg-key\b|gist\b|workflow\s+run|run\s+(rerun|cancel)|codespace\b|extension\b)", "on GitHub a run only opens and edits its own pull request"),
    (r"\bgh\s+api\b[^|;&]*(\s-X\b|\s--method\b|\s-f\b|\s-F\b|\s--field\b|\s--raw-field\b|\s--input\b)", "gh api is for reading here"),
    # this machine
    (r"(^|[;&|(]\s*)(killall|pkill)\b", "kill by PID only, never by a name"),
    (r"\b(brew\s+(install|upgrade|uninstall|remove|tap)|pip3?\s+install|gem\s+install|npm\s+(i|install)\s+(-g|--global)|flutter\s+(upgrade|downgrade|channel)|sdkmanager\b|avdmanager\s+delete)\b", "nothing is installed, upgraded or removed on this machine by a run"),
    (r"(^|[;&|(]\s*)(osascript|launchctl|defaults\s+write|diskutil|mkfs\S*|shutdown|reboot|crontab)\b|\bdd\b[^|;&]*\bof=", "that changes the machine, not the work"),
    (r"\bprettier\b", "never prettier: it flattens a file"),
    # the phone
    (r"\badb\b[^|;&]*\s(connect|pair|disconnect|kill-server|tcpip|usb|root|unroot|reconnect)\b", "a run never changes which devices adb is joined to"),
    (r"\bANDROID_SERIAL\s*=", "the device is set by the run, not by a command"),
    (r"\badb\b[^|;&]*\s(-d|-e)(\s|$)", "name the emulator by its serial with -s"),
]
SHELL_RULES = [(re.compile(p), why) for p, why in SHELL_RULES]

ADB = re.compile(r"\badb\b((?:\s+-[a-zA-Z]\s+\S+|\s+-[a-zA-Z](?=\s))*)\s+([a-z-]+)")
DELETES = re.compile(r"(^|[;&|(]\s*)(rm|rmdir|mv|chmod|chown)\b([^;&|]*)")


def check_shell(command, opts):
    for pattern, why in SHELL_RULES:
        if pattern.search(command):
            return why
    if mentions(command, opts["repo"]):
        return "the main checkout " + opts["repo"] + " belongs to another session; work only in " + opts["worktree"]
    if "tool/new-tool-pr/" in command and re.search(r"(>|\btee\b|\brm\b|\bmv\b|\bsed\s+-i|\bgit\s+(add|rm|mv))", command):
        return "the run's own scripts are not part of the work"
    # The phone: every adb command is the emulator's, by name. (Matt's phone may be on the network.)
    for m in ADB.finditer(command):
        flags, verb = m.group(1), m.group(2)
        if verb in ("devices", "version", "start-server", "help"):
            continue
        serial = re.search(r"-s\s+(\S+)", flags or "")
        if not opts["emulator"]:
            return "this run has no emulator, so nothing is done with adb"
        if serial is None:
            return "every adb command names the emulator: adb -s " + opts["emulator"] + " ..."
        if serial.group(1).strip("'\"") != opts["emulator"]:
            return "only the emulator " + opts["emulator"] + " is this run's; never another device"
    # Deleting and moving: only inside the worktree and scratch, never by a path that climbs out.
    for m in DELETES.finditer(command):
        for word in re.findall(r"""(?:[^\s'"]+|'[^']*'|"[^"]*")+""", m.group(3)):
            word = word.strip("'\"")
            if word.startswith("-") or not word:
                continue
            if "$" in word or "`" in word:
                return "delete and move by a path written out, not one worked out by the shell"
            if ".." in word.split("/"):
                return "no paths that climb out with .."
            if word.startswith(("/", "~")):
                path = os.path.normpath(os.path.expanduser(word))
                if not (inside(path, opts["worktree"]) or path.startswith(SCRATCH) or path in ("/tmp", "/private/tmp")):
                    return "a run deletes and moves only inside its worktree and /tmp"
    return None


def decide(call, opts):
    tool = call.get("tool_name", "")
    given = call.get("tool_input") or {}
    if tool == "Bash":
        return check_shell(str(given.get("command", "")), opts)
    if tool in ("Read", "Edit", "Write", "NotebookEdit", "MultiEdit"):
        return check_file(tool, str(given.get("file_path") or given.get("notebook_path") or ""), opts)
    if tool in ("Grep", "Glob"):
        where = str(given.get("path") or "")
        looked = str(given.get("pattern") or "") + " " + str(given.get("glob") or "")
        if re.search(r"(^|[/*\s])\.env(?!\.example)", looked):
            return "a .env file holds a key; never searched for or in"
        return check_file(tool, where, opts) if where else None
    return None


def self_test():
    opts = {"worktree": "/w/matts-tools-xray", "repo": "/w/matts-tools", "emulator": "emulator-5590"}
    sh = lambda c: decide({"tool_name": "Bash", "tool_input": {"command": c}}, opts)
    f = lambda t, p: decide({"tool_name": t, "tool_input": {"file_path": p}}, opts)
    let_through = [
        sh("cd /w/matts-tools-xray && git status --short"),
        sh("git add android/app/src/main/kotlin/x/A.kt && git commit -m 'A thing, and why' -- android/app/src/main/kotlin/x/A.kt"),
        sh("git push -u origin xray"),
        sh("git fetch origin && git merge origin/main"),
        sh("adb -s emulator-5590 shell dumpsys SurfaceFlinger --list"),
        sh("adb devices"),
        sh("export JAVA_HOME=/opt/x && flutter build apk --release --no-pub --target-platform android-arm64"),
        sh("until mkdir /tmp/ghost-flutter-build.lock 2>/dev/null; do sleep 15; done"),
        sh("rm -rf /w/matts-tools-xray/build/tmp && rmdir /tmp/ghost-flutter-build.lock"),
        sh("rm -f build/app/outputs/flutter-apk/app-release.apk"),
        sh("gh pr create --draft --title 'X-ray' --body-file /tmp/body.md"),
        sh("gh pr view --json url"),
        sh("cat tool/new-tool-pr/briefs/xray.md"),
        sh("kill 4312"),
        sh("grep -rn 'environment' lib/"),
        sh("cp .env.example /tmp/x"),
        f("Read", "/w/matts-tools-xray/lib/home.dart"),
        f("Read", "/opt/homebrew/share/flutter/packages/flutter/lib/widgets.dart"),
        f("Edit", "/w/matts-tools-xray/lib/home.dart"),
        f("Write", "/tmp/notes.md"),
        decide({"tool_name": "Grep", "tool_input": {"pattern": "class Dock", "path": "/w/matts-tools-xray"}}, opts),
        decide({"tool_name": "WebFetch", "tool_input": {"url": "https://developer.android.com"}}, opts),
    ]
    refused = [
        sh("cat .env"), sh("cat /w/matts-tools/.env"), sh("grep CLAUDE_KEY x"), sh("echo $ANTHROPIC_API_KEY"),
        sh("env"), sh("printenv HOME"), sh("cd x && env | sort"), sh("python3 -c 'import os; print(os.environ)'"),
        sh("ssh box true"), sh("scp a b:c"), sh("sudo ls"), sh("curl -X POST https://x -d @f"),
        sh("git push --force origin xray"), sh("git push -f"), sh("git push origin main"), sh("git push origin HEAD:main"),
        sh("git stash"), sh("git stash list"), sh("git reset --hard HEAD~1"), sh("git rebase origin/main"), sh("git clean -fd"),
        sh("git commit --amend -m x"), sh("git commit -am x"), sh("git add -A"), sh("git add ."), sh("git checkout -- lib/a.dart"),
        sh("git tag v2"), sh("git push --tags"), sh("git worktree remove x"), sh("git config --global user.name x"),
        sh("git commit -m 'x\n\nCo-Authored-By: Claude <noreply@anthropic.com>' -- a"),
        sh("gh pr merge 3"), sh("gh release create v2 a.apk"), sh("gh repo edit --visibility private"), sh("gh auth token"),
        sh("gh api repos/x/y/pulls -f title=x"),
        sh("adb shell input tap 1 1"), sh("adb -s 192.168.18.93:33597 shell ls"), sh("adb connect 192.168.18.93:5555"),
        sh("adb -s emulator-5586 install a.apk"), sh("ANDROID_SERIAL=x adb shell ls"), sh("adb kill-server"), sh("adb -d shell ls"),
        sh("cd /w/matts-tools && git status"), sh("git -C /w/matts-tools log"), sh("ls /w/matts-tools/lib"),
        sh("rm -rf ~/Development"), sh("rm -rf /"), sh("rm -rf ../matts-tools"), sh("rm -rf $HOME/x"), sh("mv /etc/hosts /tmp/x"),
        sh("pkill -f gradle"), sh("killall java"), sh("brew install jq"), sh("pip install x"), sh("npx prettier --write ."),
        sh("osascript -e 'x'"), sh("echo x > tool/new-tool-pr/guard.py"),
        f("Read", "/w/matts-tools/.env"), f("Read", "/w/matts-tools-xray/.env"), f("Read", HOME + "/.ssh/id_ed25519"),
        f("Read", "/w/matts-tools/lib/home.dart"), f("Edit", "/w/matts-tools/lib/home.dart"),
        f("Write", HOME + "/Development/Apps/glyph/x.md"), f("Edit", "/w/matts-tools-xray/tool/new-tool-pr/guard.py"),
        decide({"tool_name": "Grep", "tool_input": {"pattern": "KEY", "glob": "**/.env"}}, opts),
        decide({"tool_name": "Grep", "tool_input": {"pattern": "x", "path": "/w/matts-tools"}}, opts),
    ]
    wrong = [("let through but refused: " + str(r)) for r in let_through if r is not None]
    wrong += ["refused case %d was let through" % i for i, r in enumerate(refused) if r is None]
    print("guard self-test: %d let through, %d refused, %d wrong" % (len(let_through), len(refused), len(wrong)))
    for w in wrong:
        print("  " + w)
    return 1 if wrong else 0


def main():
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    opts = parse_args(sys.argv[1:])
    try:
        call = json.load(sys.stdin)
        why = decide(call, opts)
    except Exception as error:  # Not understood is not allowed.
        sys.stderr.write("Refused by the run's guard: it could not read this call (%s). Report this and stop.\n" % error)
        sys.exit(2)
    if why:
        sys.stderr.write("Refused by the run's guard: %s. Do not look for a way round it: do the allowed thing, or say in your result that this was refused.\n" % why)
        sys.exit(2)
    sys.exit(0)


if __name__ == "__main__":
    main()
