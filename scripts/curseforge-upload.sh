#!/usr/bin/env bash
# Upload one jar to the CurseForge project.
#
#   CURSEFORGE_TOKEN=xxx CURSEFORGE_PROJECT_ID=123456 \
#     ./scripts/curseforge-upload.sh <jar> [minecraft-version] [changelog-file] [release-type]
#
# Normally run for you by .github/workflows/curseforge.yml when a GitHub release is published, so
# publishing to GitHub publishes to CurseForge too. Runnable by hand for a re-upload.
#
# ONLY JARS GO TO CURSEFORGE. A mod project accepts jar/litemod and nothing else. The upload API
# nonetheless accepts a .zip with HTTP 200 and moderation kills it afterwards, so a zip upload looks
# completely successful until you check the authors file list.
#
# The Minecraft version defaults to `minecraft_version` in gradle.properties, so a single-version
# mod needs no argument and cannot drift from what was actually built.
#
# CurseForge wants numeric game-version IDs rather than names, and those IDs change as new versions
# are added, so they are looked up from the API every run instead of being hardcoded.
#
# Uses python3 rather than jq: jq is not installed on the dev box and python3 is, so this stays
# runnable locally as well as on a CI runner.
#
# NOTE: the upload API can only add files to a project that already exists. Unlike Modrinth there is
# no create-project endpoint — make the project on the website first, then put its numeric ID in
# CURSEFORGE_PROJECT_ID.
#
# API reference: https://support.curseforge.com/en/support/solutions/articles/9000197321
set -euo pipefail

BASE="https://minecraft.curseforge.com"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

JAR="${1:?usage: curseforge-upload.sh <jar> [mc-version] [changelog-file] [release-type]}"
MC_VERSION="${2:-$(sed -n 's/^minecraft_version=\(.*\)$/\1/p' "$HERE/gradle.properties")}"
CHANGELOG_FILE="${3:-}"
RELEASE_TYPE="${4:-release}"

: "${CURSEFORGE_TOKEN:?set CURSEFORGE_TOKEN (create one at https://legacy.curseforge.com/account/api-tokens)}"
: "${CURSEFORGE_PROJECT_ID:?set CURSEFORGE_PROJECT_ID (the numeric ID on the CurseForge project page)}"

[ -f "$JAR" ] || { echo "!! No such file: $JAR" >&2; exit 1; }

api() { curl -sS --max-time 120 -H "X-Api-Token: $CURSEFORGE_TOKEN" "$@"; }

echo ">> Resolving CurseForge version IDs for Minecraft $MC_VERSION"
# Straight to a file, never through a shell variable or an environment one. CurseForge's version
# list is a megabyte or so of JSON, which is comfortably past the environment size limit — passing
# it that way fails with "Argument list too long" from python rather than from the shell, which
# reads like a broken script instead of an oversized value.
VERSIONS_FILE="$(mktemp)"
trap 'rm -f "$VERSIONS_FILE"' EXIT
api "$BASE/api/game/versions" > "$VERSIONS_FILE"

# Everything CurseForge needs to file the upload, worked out in one pass so a missing tag is
# reported once with the near misses listed rather than as a bare rejection later.
# The file path goes in by environment; a pipe would not work at all, because `python3 - <<EOF`
# already uses stdin for the script itself.
IDS="$(MC="$MC_VERSION" VERSIONS_FILE="$VERSIONS_FILE" JAR="$JAR" python3 - <<'PY'
import json, os, sys, pathlib, zipfile
raw = pathlib.Path(os.environ["VERSIONS_FILE"]).read_text()
try:
    versions = json.loads(raw)
except json.JSONDecodeError:
    sys.exit("!! Unexpected response from the versions endpoint — is the token valid?\n"
             + raw[:400])
if not isinstance(versions, list):
    sys.exit("!! Unexpected response from the versions endpoint — is the token valid?")

want = os.environ["MC"]
by_name = {}
for v in versions:
    by_name.setdefault(v.get("name"), v.get("id"))

# Exact match: "1.21.1" must not match "1.21.11".
mc = by_name.get(want)
if mc is None:
    near = sorted(n for n in by_name if n and n.startswith(want.split(".")[0]))
    sys.exit(f"!! CurseForge does not list Minecraft '{want}' yet.\n"
             "!! Closest names it does know:\n     " + "\n     ".join(near[-12:]))

loader = by_name.get("NeoForge")
# CurseForge rejects an upload naming no environment ("You must select at least one version from
# the environment group"). Both, because the mod is server-side but a single-player world is a
# server too, and the jar is perfectly happy installed client-side.
client, server = by_name.get("Client"), by_name.get("Server")
if not client or not server:
    sys.exit("!! Could not find the Client/Server environment tags CurseForge requires.")

# The Java tag is READ OUT OF THE JAR, not hardcoded and not derived from the Minecraft version.
#
# It used to be a literal "Java 21", which was right for the 1.21.11 line and quietly wrong for
# every 26.x jar — those compile against Java 25, and the file page told people 21 would do. A
# lookup table keyed on the Minecraft version would fix today's case and rot the next time a line
# moves its toolchain, because nothing would fail: CurseForge accepts whatever tag it is handed.
#
# A class file's major version is 44 + the Java release, so the jar states its own requirement.
# Taking the highest across our own classes gives the floor a runtime actually has to meet.
# Namespaced to com/sablednah/ deliberately: a jarjar'd dependency compiled at a lower level
# would drag the answer down, and one compiled higher is not our requirement to declare.
def java_release_from(jar_path):
    best = 0
    with zipfile.ZipFile(jar_path) as zf:
        for entry in zf.namelist():
            if not (entry.startswith("com/sablednah/") and entry.endswith(".class")):
                continue
            with zf.open(entry) as f:
                head = f.read(8)
            # cafebabe, minor (2 bytes), major (2 bytes)
            if len(head) >= 8 and head[:4] == b"\xca\xfe\xba\xbe":
                best = max(best, int.from_bytes(head[6:8], "big"))
    return best - 44 if best else None

release = java_release_from(os.environ["JAR"])
if release is None:
    print("!! No com/sablednah/ classes in the jar — is this the right file?", file=sys.stderr)
    sys.exit(1)

# Optional, unlike the environment: right after a new Java ships CurseForge may not list it yet,
# and that must not block a release. Saying which one was missed matters — a silently absent tag
# is how the wrong one survived in the first place.
java_name = f"Java {release}"
java = by_name.get(java_name)

ids = [mc] + ([loader] if loader else []) + [client, server] + ([java] if java else [])
print(json.dumps(ids))
print(f"   Minecraft {want} = {mc}"
      + (f", NeoForge = {loader}" if loader else ", NeoForge = MISSING")
      + f", Client = {client}, Server = {server}"
      + (f", {java_name} = {java}" if java
         else f", {java_name} = not listed by CurseForge, omitted"),
      file=sys.stderr)
PY
)"
GAME_VERSIONS="$IDS"

# "Diorama 1.0.0 / MC 26.2" reads far better in the file list than the raw filename. The mod
# version comes out of the filename, falling back to the whole basename if a jar is ever named
# differently. The file itself keeps its original name either way.
MOD_VERSION="$(basename "$JAR" .jar | sed -n 's/^diorama-\(.*\)+mc.*$/\1/p')"
if [ -n "$MOD_VERSION" ]; then
    DISPLAY_NAME="Diorama $MOD_VERSION / MC $MC_VERSION"
else
    DISPLAY_NAME="$(basename "$JAR" .jar)"
fi

METADATA="$(CHANGELOG_FILE="$CHANGELOG_FILE" DISPLAY_NAME="$DISPLAY_NAME" \
    RELEASE_TYPE="$RELEASE_TYPE" GAME_VERSIONS="$GAME_VERSIONS" \
    DEP="${CURSEFORGE_REQUIRED_DEPENDENCY:-}" python3 - <<'PY'
import json, os, pathlib
path = os.environ["CHANGELOG_FILE"]
changelog = pathlib.Path(path).read_text() if path and os.path.exists(path) else ""
meta = {
    "changelog": changelog,
    "changelogType": "markdown",
    "displayName": os.environ["DISPLAY_NAME"],
    "releaseType": os.environ["RELEASE_TYPE"],
    "gameVersions": json.loads(os.environ["GAME_VERSIONS"]),
}
# Optional, because a wrong slug fails the whole upload and CurseForge 403s any attempt to look one
# up from a script. Set CURSEFORGE_REQUIRED_DEPENDENCY to the dependency's project slug.
dep = os.environ.get("DEP", "").strip()
if dep:
    meta["relations"] = {"projects": [{"slug": dep, "type": "requiredDependency"}]}
print(json.dumps(meta))
PY
)"

if [ -n "${CURSEFORGE_DEBUG:-}" ]; then
    # The metadata carries no credentials, so it is safe to print when diagnosing a rejection.
    echo ">> metadata:"
    python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin), indent=2))' <<<"$METADATA" | sed 's/^/     /'
fi

echo ">> Uploading $(basename "$JAR") to project $CURSEFORGE_PROJECT_ID ($RELEASE_TYPE)"
# --form-string, not -F: curl gives ';', a leading '@' and a leading '<' special meaning inside an
# -F value, and a changelog containing any of them silently mangles the JSON. CurseForge then
# answers "Invalid JSON", which reads like a bug in the JSON we built. The jar still needs -F,
# since @ there is the point.
RESPONSE="$(curl -sS --max-time 600 -w '\n%{http_code}' \
    -H "X-Api-Token: $CURSEFORGE_TOKEN" \
    --form-string "metadata=$METADATA" \
    -F "file=@$JAR" \
    "$BASE/api/projects/$CURSEFORGE_PROJECT_ID/upload-file")"

STATUS="$(tail -n1 <<<"$RESPONSE")"
BODY="$(sed '$d' <<<"$RESPONSE")"

if [ "$STATUS" = "200" ]; then
    FILE_ID="$(python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("id",""))
except Exception: pass' <<<"$BODY" 2>/dev/null || true)"
    echo ">> Uploaded${FILE_ID:+ as file $FILE_ID}"
    # A 200 means CurseForge accepted the file, NOT that it is published. Moderation runs afterwards
    # and can still reject it — most often as a duplicate, because CurseForge dedupes by file
    # content and will not host the same jar twice on one project. Re-running an upload for a
    # release already up therefore produces rejections, not duplicates, and rejected files are
    # hidden from the authors list by default, so they look like they never arrived.
    echo ">> Note: moderation runs after this. Check the project's file list if it does not appear:"
    echo "   https://authors.curseforge.com/#/projects/$CURSEFORGE_PROJECT_ID/files"
    exit 0
fi

echo "!! CurseForge rejected the upload (HTTP $STATUS)" >&2
echo "$BODY" >&2
exit 1
