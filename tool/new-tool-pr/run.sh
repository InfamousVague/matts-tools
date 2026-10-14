#!/usr/bin/env bash
#
# Makes a pull request for a new tool in Matt's Tools, by running Claude Code unattended on a brief.
#
#   tool/new-tool-pr/run.sh                     build the X-ray tool (briefs/xray.md) and open its pull request
#   tool/new-tool-pr/run.sh briefs/other.md     the same for another brief
#   tool/new-tool-pr/run.sh --smoke             one small agent, for cents: proves the key pays, the workflow
#                                               runs and the guard refuses what it should. Do this first.
#   tool/new-tool-pr/run.sh --check             looks at everything a run needs and starts nothing
#   tool/new-tool-pr/run.sh --key               asks the API whose key this is and whether it can spend
#                                               (a request of one word: a hundredth of a cent if it can)
#
# What pays: CLAUDE_KEY, read from the .env at the top of the repository (or from the environment if it is already
# there). It is handed to Claude Code as its API key for this run only, is never printed, and is kept out of the
# environment of every command the run starts. Claude Code is stopped at once if it starts up paying any other way.
#
# What it does: makes a worktree beside this repository on a branch named for the brief, and there runs one saved
# workflow (workflow.js, started with the word "ultracode"): three readers map the repository and the platform, a
# plan is made and attacked, the tool is built in packages, checked, tried on the emulator, reviewed three ways,
# mended, and put up as a pull request. It never merges, never pushes to main, and never touches a phone: guard.py
# is asked before every command and refuses those, whatever a prompt says.
#
# Settings, by environment:
#   BUDGET_USD   the most to spend, in dollars (180; a smoke run 1). The run stops when it gets there.
#   HOURS        the longest it may go on (8).
#   MODEL        the model of the one that starts and waits (sonnet). Each step of the workflow names its own.
#   EMULATOR     the emulator's serial (emulator-5590), and AVD, the one to start if it is not up (frames).
#                With neither, the work is built and reviewed but not tried on a device, and says so.
#   BRANCH, WORKTREE   default to the brief's name: xray, and ../matts-tools-xray.
#   CLAUDE_BIN   a Claude Code to use instead of the newest one found.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The main checkout, whichever copy of this script is run (a worktree has one too).
ROOT="$(dirname "$(git -C "$HERE" rev-parse --path-format=absolute --git-common-dir)")"
GH_REPO="InfamousVague/matts-tools"
LOCK="/tmp/ghost-flutter-build.lock"

say() { printf '%s\n' "$*"; }
die() { printf 'run.sh: %s\n' "$*" >&2; exit 1; }

MODE=run
BRIEF="$HERE/briefs/xray.md"
for arg in "$@"; do
  case "$arg" in
    --smoke) MODE=smoke ;;
    --check) MODE=check ;;
    --key) MODE=key ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "unknown option $arg" ;;
    *) BRIEF="$arg" ;;
  esac
done
[ -f "$BRIEF" ] || { [ -f "$HERE/$BRIEF" ] && BRIEF="$HERE/$BRIEF"; }
[ -f "$BRIEF" ] || die "no brief at $BRIEF"
BRIEF="$(cd "$(dirname "$BRIEF")" && pwd)/$(basename "$BRIEF")"
SLUG="$(basename "$BRIEF" .md | tr -c 'a-zA-Z0-9\n' '-' | tr 'A-Z' 'a-z')"
BRANCH="${BRANCH:-$SLUG}"
WORKTREE="${WORKTREE:-$ROOT-$SLUG}"
EMULATOR="${EMULATOR-emulator-5590}"
AVD="${AVD-frames}"
MODEL="${MODEL:-sonnet}"
if [ "$MODE" = smoke ]; then BUDGET_USD="${BUDGET_USD:-1}"; HOURS="${HOURS:-0.25}"; else BUDGET_USD="${BUDGET_USD:-180}"; HOURS="${HOURS:-8}"; fi
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# ---- the key ---------------------------------------------------------------------------------------------------
# From the environment, or the one line of the .env that names it. The file is not sourced: nothing else in it is
# this script's business. It must be a file git ignores, since this repository is public.
key="${CLAUDE_KEY:-}"
if [ -z "$key" ] && [ -f "$ROOT/.env" ]; then
  git -C "$ROOT" check-ignore -q .env || die "$ROOT/.env is not ignored by git, and this repository is public. Add .env to .gitignore first."
  key="$(sed -n -E 's/^[[:space:]]*(export[[:space:]]+)?CLAUDE_KEY[[:space:]]*=[[:space:]]*//p' "$ROOT/.env" | tail -n 1 | tr -d '\r')"
  key="${key%%[[:space:]]#*}"; key="${key%\"}"; key="${key#\"}"; key="${key%\'}"; key="${key#\'}"
fi
[ -n "$key" ] || die "no CLAUDE_KEY: put a line CLAUDE_KEY=... in $ROOT/.env, or export it."
unset CLAUDE_KEY

# Whose key it is and whether it can spend, asked of the API itself. The key goes to curl on its standard input,
# so that it is in no command line, and nothing of it is printed.
if [ "$MODE" = key ]; then
  case "$key" in
    sk-ant-admin*) say "kind          an admin key: it manages an organization and cannot run Claude. An ordinary API key is needed." ;;
    sk-ant-oat*) say "kind          a sign-in token from \"claude setup-token\", not an API key: it spends a Claude plan, not API credit" ;;
    sk-ant-*) say "kind          an API key" ;;
    *) say "kind          not in the shape of an Anthropic key" ;;
  esac
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  ask() { printf 'header = "x-api-key: %s"\n' "$key" | curl -sS -m 40 -K - -H 'anthropic-version: 2023-06-01' "$@"; }
  code="$(ask -o "$tmp/models" -D "$tmp/models.h" -w '%{http_code}' 'https://api.anthropic.com/v1/models?limit=1' || true)"
  org="$(sed -n -E 's/^anthropic-organization-id:[[:space:]]*//Ip' "$tmp/models.h" 2>/dev/null | tr -d '\r' | tail -1)"
  if [ "$code" = 200 ]; then say "accepted      yes"; else say "accepted      no (the API answered $code)"; fi
  say "organization  ${org:-not said}"
  # Each model the run uses: credit that is given away is sometimes good for some models and not others.
  for model in ${KEY_MODELS:-claude-haiku-4-5-20251001 claude-sonnet-5-5 claude-opus-5-5}; do
    code="$(ask -o "$tmp/message" -w '%{http_code}' -H 'content-type: application/json' \
      -d "{\"model\":\"$model\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"1\"}]}" https://api.anthropic.com/v1/messages || true)"
    if [ "$code" = 200 ]; then say "can spend     yes, on $model"
    else say "can spend     NO, on $model: $(python3 -c 'import json,sys
try:
    e = json.load(open(sys.argv[1])).get("error", {})
    print("%s: %s" % (e.get("type"), e.get("message")))
except Exception:
    print("the API answered " + sys.argv[2])' "$tmp/message" "$code")"; fi
  done
  say
  say "The organization above is the one this key spends from. In console.anthropic.com, Settings, Organization shows"
  say "the id of the organization you are looking at; its Billing page is where the credit must be."
  unset key; exit 0
fi

# ---- Claude Code: the newest there is ----------------------------------------------------------------------------
# The desktop app keeps its own, usually newer than the one on the PATH, and the workflow needs a recent one.
find_claude() {
  if [ -n "${CLAUDE_BIN:-}" ]; then printf '%s\n' "$CLAUDE_BIN"; return; fi
  {
    for c in "$HOME/Library/Application Support/Claude/claude-code"/*/*/claude.app/Contents/MacOS/claude; do [ -x "$c" ] && printf '%s\n' "$c"; done
    command -v claude 2>/dev/null || true
  } | while IFS= read -r c; do
    v="$("$c" --version 2>/dev/null | sed -n -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' | head -1)"
    [ -n "$v" ] && printf '%s\t%s\n' "$v" "$c"
  done | python3 -c '
import sys
rows=[l.rstrip("\n").split("\t",1) for l in sys.stdin if "\t" in l]
rows.sort(key=lambda r: tuple(int(x) for x in r[0].split(".")))
print(rows[-1][1] if rows else "")'
}
CLAUDE="$(find_claude)"
[ -n "$CLAUDE" ] && [ -x "$CLAUDE" ] || die "no Claude Code found. Install it, or set CLAUDE_BIN."
CLAUDE_VERSION="$("$CLAUDE" --version 2>/dev/null | head -1)"

# ---- what a run needs ------------------------------------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }
emulator_up() { [ -n "$EMULATOR" ] && adb devices 2>/dev/null | grep -q "^$EMULATOR[[:space:]]*device$"; }
avd_there() { [ -n "$AVD" ] && have emulator && emulator -list-avds 2>/dev/null | grep -qx "$AVD"; }

report() {
  say "key           found (CLAUDE_KEY), and .env is ignored by git"
  say "Claude Code   $CLAUDE_VERSION"
  say "              $CLAUDE"
  say "brief         $BRIEF"
  say "branch        $BRANCH, in the worktree $WORKTREE$([ -d "$WORKTREE" ] && printf ' (there already)')"
  say "budget        \$$BUDGET_USD at most, $HOURS hours at most, started by $MODEL"
  for tool in git python3 flutter gh adb; do have "$tool" && say "$(printf '%-13s' "$tool") found" || say "$(printf '%-13s' "$tool") MISSING"; done
  [ -d "$JAVA_HOME" ] && say "java          $JAVA_HOME" || say "java          MISSING at $JAVA_HOME (set JAVA_HOME)"
  if emulator_up; then say "emulator      $EMULATOR is up"
  elif avd_there; then say "emulator      $EMULATOR is not up; the AVD \"$AVD\" will be started"
  else say "emulator      none: the work will be built and reviewed, and not tried on a device"; fi
  others="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | grep -v '^emulator-' | tr '\n' ' ' || true)"
  [ -n "$others" ] && say "other devices $others(never touched: the guard refuses any adb command not for the emulator)"
  python3 "$HERE/guard.py" --self-test
}

if [ "$MODE" = check ]; then report; say; say "Nothing was started."; exit 0; fi
for tool in git python3; do have "$tool" || die "$tool is needed"; done
python3 "$HERE/guard.py" --self-test >/dev/null || die "the guard fails its own tests; nothing is started without it"

# ---- where it runs ---------------------------------------------------------------------------------------------
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN="$HERE/runs/$STAMP-$MODE-$SLUG"
mkdir -p "$RUN"

if [ "$MODE" = smoke ]; then
  WORKTREE="/tmp/new-tool-pr-smoke"
  SCRATCH="$WORKTREE"
  mkdir -p "$WORKTREE"
  cp "$HERE/smoke.js" "$SCRATCH/workflow.js"
else
  for tool in flutter gh adb; do have "$tool" || die "$tool is needed for a run"; done
  [ -d "$JAVA_HOME" ] || die "no Java at $JAVA_HOME (set JAVA_HOME)"
  gh auth status >/dev/null 2>&1 || die "gh is not signed in, so no pull request could be opened"
  git -C "$ROOT" fetch -q origin
  if [ ! -d "$WORKTREE" ]; then
    if git -C "$ROOT" show-ref -q --verify "refs/heads/$BRANCH"; then git -C "$ROOT" worktree add -q "$WORKTREE" "$BRANCH"
    elif git -C "$ROOT" show-ref -q --verify "refs/remotes/origin/$BRANCH"; then git -C "$ROOT" worktree add -q -b "$BRANCH" "$WORKTREE" "origin/$BRANCH"
    else git -C "$ROOT" worktree add -q -b "$BRANCH" "$WORKTREE" origin/main; fi
  fi
  [ "$(git -C "$WORKTREE" rev-parse --abbrev-ref HEAD)" = "$BRANCH" ] || die "$WORKTREE is not on the branch $BRANCH"
  # What the build needs and git does not carry.
  [ -f "$WORKTREE/android/local.properties" ] || cp "$ROOT/android/local.properties" "$WORKTREE/android/local.properties" 2>/dev/null || true
  SCRATCH="$WORKTREE/.new-tool-pr"
  git -C "$WORKTREE" check-ignore -q .new-tool-pr || die "$WORKTREE does not ignore .new-tool-pr/ (the branch is older than this script: merge main into it)"
  mkdir -p "$SCRATCH/notes" "$SCRATCH/samples" "$SCRATCH/shots"
  cp "$BRIEF" "$SCRATCH/brief.md"
  rm -rf "$SCRATCH/brief"; mkdir -p "$SCRATCH/brief"
  [ -d "${BRIEF%.md}" ] && cp -R "${BRIEF%.md}/." "$SCRATCH/brief/"
  cp "$HERE/workflow.js" "$SCRATCH/workflow.js"
  # The emulator: up, or started, or done without.
  if ! emulator_up; then
    if avd_there; then
      say "Starting the emulator \"$AVD\"..."
      port="${EMULATOR#emulator-}"
      ( nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu host -port "$port" >"$RUN/emulator.log" 2>&1 & )
      for _ in $(seq 1 60); do emulator_up && [ "$(adb -s "$EMULATOR" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; done
      emulator_up || { say "The emulator did not come up; going on without a device."; EMULATOR=""; }
    else
      EMULATOR=""
    fi
  fi
fi

python3 - "$RUN/args.json" <<EOF
import json, sys
json.dump({
    "slug": "$SLUG", "worktree": "$WORKTREE", "scratch": "$SCRATCH", "brief": "$SCRATCH/brief.md", "briefDir": "$SCRATCH/brief",
    "branch": "$BRANCH", "ghRepo": "$GH_REPO", "lock": "$LOCK", "emulator": "$EMULATOR",
    "javaHome": "$JAVA_HOME", "androidHome": "$ANDROID_HOME", "date": "$STAMP",
}, open(sys.argv[1], "w"), indent=1)
EOF

# The guard, asked before every command the run and its agents make, and what a commit and a pull request are signed
# with (nothing: Matt's rule).
python3 - "$RUN/settings.json" <<EOF
import json, shlex, sys
guard = "python3 " + shlex.quote("$HERE/guard.py") + " --worktree " + shlex.quote("$WORKTREE") + " --repo " + shlex.quote("$ROOT") + " --emulator " + shlex.quote("$EMULATOR")
json.dump({
    "hooks": {"PreToolUse": [
        {"matcher": "Bash", "hooks": [{"type": "command", "command": guard}]},
        {"matcher": "Read|Edit|Write|MultiEdit|NotebookEdit|Grep|Glob", "hooks": [{"type": "command", "command": guard}]},
    ]},
    "attribution": {"commit": "", "pr": ""},
}, open(sys.argv[1], "w"), indent=1)
EOF

cat > "$RUN/prompt.txt" <<EOF
ultracode

You are the starter of an unattended run. One saved workflow does all the work; you start it, wait, and report. Do not do any of the work yourself, do not write another workflow, and do not edit the script.

1. Call the Workflow tool with scriptPath "$SCRATCH/workflow.js" and with args set to exactly this JSON object (as an object, not as a string):

$(cat "$RUN/args.json")

2. The workflow runs in the background. End your turn; you will be told when it finishes. Do nothing else while it runs.

3. When you are told it has finished, reply with its result as JSON and nothing else. If it stopped with an error instead of finishing, say what the error was, then start it once more with the same scriptPath and args and with resumeFromRunId set to the run that failed, and report that result. Do that once only.
EOF

# ---- go --------------------------------------------------------------------------------------------------------
say "Matt's Tools, a new tool by pull request: $MODE"
report
say "log           $RUN"
say

# The key goes to Claude Code by the environment, and Claude Code keeps it from the commands it runs
# (CLAUDE_CODE_SUBPROCESS_ENV_SCRUB). With that set it will run only the tools named below, asking nobody.
# Everything else of Claude's in the environment is taken out first: another way to pay or sign in, and, when this is
# started from inside a Claude session (the desktop app's, say), that session's account and organization, which the
# new one would otherwise take for its own. With those left in, the API refused a key that had credit.
for name in $(compgen -v | grep -E '^(CLAUDE|ANTHROPIC)' | grep -v -x -E 'CLAUDE|CLAUDE_VERSION' || true); do unset "$name"; done
export ANTHROPIC_API_KEY="$key"; unset key
export CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1
# A workflow is background work, and Claude Code stops waiting for that after ten idle minutes unless told not to.
export CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0
# A stray adb command with no -s goes to the emulator, or to nothing: never to a phone.
export ANDROID_SERIAL="${EMULATOR:-no-device-on-this-run}"

cd "$WORKTREE"
# A run is hours long: the computer is kept from sleeping while it goes on.
awake=""; have caffeinate && awake="caffeinate -i"
set +e
$awake python3 "$HERE/watch.py" --log "$RUN" --hours "$HOURS" --budget "$BUDGET_USD" -- \
  "$CLAUDE" -p "$(cat "$RUN/prompt.txt")" \
  --model "$MODEL" \
  --max-budget-usd "$BUDGET_USD" \
  --settings "$RUN/settings.json" \
  --strict-mcp-config \
  --allowedTools "Workflow,Task,Bash,Read,Edit,Write,NotebookEdit,WebFetch,WebSearch,ToolSearch,Monitor,LSP,Skill,TaskCreate,TaskGet,TaskList,TaskUpdate,TaskStop" \
  --name "new-tool-pr $SLUG" \
  --output-format stream-json --verbose
code=$?
set -e
unset ANTHROPIC_API_KEY

say
# The key was refused, or has nothing to spend: watch.py has said which, and there is no more to say.
if [ "$code" -eq 5 ] || [ "$code" -eq 6 ]; then exit "$code"; fi
if [ "$MODE" = smoke ] && [ "$code" -eq 7 ]; then exit "$code"; fi
if [ "$MODE" = smoke ]; then
  if grep -q '"guardHolds"[[:space:]]*:[[:space:]]*true' "$RUN/result.txt" 2>/dev/null; then say "Smoke run passed: the key paid, the workflow ran, and the guard refused what it should."
  else say "Smoke run did NOT pass. Read $RUN/result.txt and $RUN/log.jsonl before starting a real run."; [ "$code" -eq 0 ] && code=1; fi
else
  ahead="$(git -C "$WORKTREE" rev-list --count origin/main..HEAD 2>/dev/null || echo '?')"
  pushed="$(git -C "$WORKTREE" rev-parse -q --verify "refs/remotes/origin/$BRANCH" >/dev/null 2>&1 && git -C "$WORKTREE" rev-list --count "origin/$BRANCH..HEAD" 2>/dev/null || echo 'none of them')"
  say "The branch $BRANCH has $ahead commits that main does not; not yet pushed: $pushed."
  url="$(gh pr list --repo "$GH_REPO" --head "$BRANCH" --state all --json url -q '.[0].url' 2>/dev/null || true)"
  [ -n "$url" ] && say "Pull request: $url" || say "No pull request is open for it. Run this again to go on from where it stopped: what is done is kept."
fi
exit "$code"
