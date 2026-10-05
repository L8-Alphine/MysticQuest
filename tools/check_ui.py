"""Checks MysticQuests' UI documents against what the Hytale client actually accepts.

One bad value in any shipped .ui document can stop the client loading custom UI for every mod on
the server, and nothing can be seen without a client. So before a build ships, every document is
checked four ways against a corpus of known-good markup — the base game's own custom-UI documents
(from the client's Assets.zip) and every other mod's the client has cached:

1. vocabulary: every element tag, property name and bare enum value appears in >= 3 corpus documents;
2. pairs: every property appears on that element in >= 3 corpus documents;
3. duplicates: no block sets a property twice (the parser rejects it);
4. references: every $Alias.@Name resolves, in Theme.ui or the base game's Common.ui.

The corpus is extracted once into build/ui-corpus/ (delete it to rebuild after a game update).

usage: python tools/check_ui.py            (from the project root; exits 1 on any problem)
"""
import os
import pickle
import re
import sys
import zipfile
from collections import Counter

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
CUSTOM = os.path.join(ROOT, "src", "main", "resources", "Common", "UI", "Custom")
CORPUS = os.path.join(ROOT, "build", "ui-corpus")
APPDATA = os.environ.get("APPDATA", "")
CACHED_ASSETS = os.path.join(APPDATA, "Hytale", "UserData", "CachedAssets")
ASSETS_ZIP = os.path.join(APPDATA, "Hytale", "install", "release", "package", "game", "latest", "Assets.zip")
MIN_DOCS = 3

TAG = re.compile(r"(?m)^\s*([A-Z][A-Za-z0-9]*)\s*(?:#[A-Za-z0-9]+\s*)?\{")
PROP = re.compile(r"\b([A-Z][A-Za-z0-9]*)\s*:\s*")
ENUM = re.compile(r"\b([A-Z][A-Za-z0-9]*)\s*:\s*([A-Za-z][A-Za-z0-9]*)\s*[;,)\n]")
BLOCK_OPEN = re.compile(r"(?<![A-Za-z0-9@.$#])([A-Z][A-Za-z0-9]*)\s*(#[A-Za-z0-9]+)?\s*\{$")
STATEMENT = re.compile(r"^\s*([A-Z][A-Za-z0-9]*)\s*:")


def strip_comments(text):
    return re.sub(r"//[^\n]*", "", text)


def ours():
    documents = []
    for folder, _, names in os.walk(os.path.join(CUSTOM, "mysticquests")):
        documents += [os.path.join(folder, name) for name in names if name.endswith(".ui")]
    hud = os.path.join(CUSTOM, "Hud")
    documents += [os.path.join(hud, name) for name in os.listdir(hud) if name.startswith("MysticQuests") and name.endswith(".ui")]
    return sorted(documents)


# --- corpus ---

def extract_corpus():
    """Copies every known-good UI document into build/ui-corpus/{cached,vanilla}."""
    cached = os.path.join(CORPUS, "cached")
    vanilla = os.path.join(CORPUS, "vanilla")
    os.makedirs(cached, exist_ok=True)
    os.makedirs(vanilla, exist_ok=True)
    if os.path.isdir(CACHED_ASSETS):
        for top in os.listdir(CACHED_ASSETS):
            folder = os.path.join(CACHED_ASSETS, top)
            if not os.path.isdir(folder):
                continue
            for name in os.listdir(folder):
                path = os.path.join(folder, name)
                try:
                    if os.path.getsize(path) > 2_000_000:
                        continue
                    with open(path, "rb") as handle:
                        if b"\x00" in handle.read(4096):
                            continue
                    with open(path, "rb") as handle:
                        text = handle.read().decode("utf-8")
                except (OSError, UnicodeDecodeError):
                    continue
                stripped = text.lstrip()
                if "{" not in text or stripped.startswith("{") or stripped.startswith("["):
                    continue
                if not ("Anchor:" in text or "LayoutMode:" in text or "Style:" in text):
                    continue
                if "mysticquests" in text.lower() or "$MQ" in text:
                    continue  # our own documents, including old broken ones the client cached
                with open(os.path.join(cached, top + name + ".ui"), "w", encoding="utf-8") as out:
                    out.write(text)
    if os.path.isfile(ASSETS_ZIP):
        with zipfile.ZipFile(ASSETS_ZIP) as pack:
            for name in pack.namelist():
                if name.startswith("Common/UI/Custom/") and name.endswith(".ui"):
                    target = os.path.join(vanilla, name[len("Common/UI/Custom/"):])
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    with open(target, "wb") as out:
                        out.write(pack.read(name))


def corpus_files():
    files = []
    for folder, _, names in os.walk(CORPUS):
        files += [os.path.join(folder, name) for name in names if name.endswith(".ui")]
    return files


def blocks(text):
    """Yields (tag, own properties in order) for each element block; macro instances are skipped."""
    text = strip_comments(text)
    stack, paren, index, seen = [], 0, 0, set()
    while index < len(text):
        char = text[index]
        if char == '"':
            end = index + 1
            while end < len(text) and not (text[end] == '"' and text[end - 1] != "\\"):
                end += 1
            index = end + 1
            continue
        if char in "([":
            paren += 1
        elif char in ")]":
            paren -= 1
        elif char == "{" and paren == 0:
            match = BLOCK_OPEN.search(text[max(0, index - 80):index + 1])
            stack.append([match.group(1) if match else None, []])
        elif char == "}" and paren == 0 and stack:
            yield tuple(stack.pop())
        if paren == 0 and stack and (index == 0 or text[index - 1] in "\n{;"):
            match = STATEMENT.match(text[index:index + 60])
            if match and index + match.start(1) not in seen:
                seen.add(index + match.start(1))
                stack[-1][1].append(match.group(1))
        index += 1


def scan(text):
    text = strip_comments(text)
    enums = {(key, value) for key, value in ENUM.findall(text) if value not in ("true", "false")}
    return set(TAG.findall(text)), set(PROP.findall(text)), enums


def corpus_index():
    cache = os.path.join(CORPUS, "index.pickle")
    if os.path.exists(cache):
        with open(cache, "rb") as handle:
            return pickle.load(handle)
    if not corpus_files():
        extract_corpus()
    files = corpus_files()
    if not files:
        raise SystemExit("No corpus: run once on a machine with the Hytale client installed.")
    tags, props, enums, pairs = Counter(), Counter(), Counter(), Counter()
    for path in files:
        try:
            text = open(path, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        found_tags, found_props, found_enums = scan(text)
        tags.update(found_tags)
        props.update(found_props)
        enums.update(found_enums)
        pairs.update({(tag, prop) for tag, own in blocks(text) if tag for prop in own})
    index = {"docs": len(files), "tags": tags, "props": props, "enums": enums, "pairs": pairs}
    with open(cache, "wb") as handle:
        pickle.dump(index, handle)
    return index


# --- checks ---

def definitions(text):
    return set(re.findall(r"^\s*@([A-Za-z][A-Za-z0-9]*)\s*=", text, re.M))


def check(path, index, vanilla_common):
    problems = []
    text = open(path, encoding="utf-8").read()
    tags, props, enums = scan(text)
    known_tags = {k for k, n in index["tags"].items() if n >= MIN_DOCS}
    known_props = {k for k, n in index["props"].items() if n >= MIN_DOCS}
    known_enums = {k for k, n in index["enums"].items() if n >= MIN_DOCS}
    enum_keys = {key for key, _ in known_enums}
    problems += ["unknown tag " + tag for tag in sorted(tags - known_tags)]
    problems += ["unknown property " + prop for prop in sorted(props - known_props)]
    for key, value in sorted(enums):
        if key in enum_keys and (key, value) not in known_enums and value not in known_tags:
            problems.append(f"value {key}: {value} never appears in the corpus")
    for tag, own in blocks(text):
        if not tag:
            continue
        for prop in sorted(set(own)):
            if index["pairs"].get((tag, prop), 0) < MIN_DOCS:
                problems.append(f"{tag}.{prop} appears on {tag} in fewer than {MIN_DOCS} corpus documents")
        twice = sorted({prop for prop in own if own.count(prop) > 1})
        if twice:
            problems.append(f"{tag} sets {', '.join(twice)} twice")
    clean = strip_comments(text)
    imports = dict(re.findall(r'^\$([A-Za-z]+)\s*=\s*"([^"]+)"', clean, re.M))
    for alias, name in sorted(set(re.findall(r"\$([A-Za-z]+)\.@([A-Za-z0-9]+)", clean))):
        target = imports.get(alias)
        if target is None:
            problems.append(f"${alias} is used but not imported")
            continue
        resolved = os.path.normpath(os.path.join(os.path.dirname(path), target))
        if os.path.relpath(resolved, CUSTOM).replace(os.sep, "/") == "Common.ui":
            defined = definitions(vanilla_common)
        elif os.path.isfile(resolved):
            defined = definitions(open(resolved, encoding="utf-8").read())
        else:
            problems.append(f"{target} does not exist")
            continue
        if name not in defined:
            problems.append(f"${alias}.@{name} is not defined in {target}")
    return problems


def main():
    index = corpus_index()
    common = os.path.join(CORPUS, "vanilla", "Common.ui")
    vanilla_common = open(common, encoding="utf-8").read() if os.path.isfile(common) else ""
    print(f"corpus: {index['docs']} documents")
    total = 0
    for path in ours():
        problems = check(path, index, vanilla_common)
        name = os.path.relpath(path, CUSTOM)
        for problem in problems:
            print(f"{name}: {problem}")
        total += len(problems)
    print("OK" if total == 0 else f"{total} problem(s)")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
