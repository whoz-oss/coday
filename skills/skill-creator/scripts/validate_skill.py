#!/usr/bin/env python3
"""Validate a Coday skill directory."""

import os
import re
import shutil
import sys
import yaml

MAX_SKILL_FILE_BYTES = 102400  # 100 KB
MAX_NAME_LENGTH = 64
HYPHEN_CASE_RE = re.compile(r'^[a-z][a-z0-9]*(-[a-z0-9]+)*$')

def error(msg):
    print(f"❌ {msg}")
    return False

def warn(msg):
    print(f"⚠️  {msg}")

def ok(msg):
    print(f"✅ {msg}")

def validate_skill(skill_dir):
    errors = []
    warnings = []

    # Check directory exists
    if not os.path.isdir(skill_dir):
        print(error(f"Directory not found: {skill_dir}"))
        return 1

    # Check SKILL.md exists
    skill_md = os.path.join(skill_dir, 'SKILL.md')
    if not os.path.isfile(skill_md):
        print(f"❌ SKILL.md not found in {skill_dir}")
        return 1
    ok("SKILL.md exists")

    # Check file size
    size = os.path.getsize(skill_md)
    if size > MAX_SKILL_FILE_BYTES:
        errors.append(f"SKILL.md too large: {size} bytes (max {MAX_SKILL_FILE_BYTES})")
    else:
        ok(f"SKILL.md size OK ({size} bytes)")

    # Read and parse frontmatter
    with open(skill_md, 'r', encoding='utf-8') as f:
        content = f.read()

    content = content.replace('\r\n', '\n')

    if not content.startswith('---\n'):
        errors.append("SKILL.md must start with '---' (YAML frontmatter)")
        print_results(errors, warnings)
        return 1 if errors else 0

    second = content.find('\n---\n', 4)
    if second == -1:
        errors.append("SKILL.md frontmatter not closed (missing second '---')")
        print_results(errors, warnings)
        return 1 if errors else 0

    fm_raw = content[4:second]
    try:
        fm = yaml.safe_load(fm_raw)
    except yaml.YAMLError as e:
        errors.append(f"Invalid YAML in frontmatter: {e}")
        print_results(errors, warnings)
        return 1 if errors else 0

    if not isinstance(fm, dict):
        errors.append("Frontmatter must be a YAML mapping")
        print_results(errors, warnings)
        return 1 if errors else 0

    ok("Frontmatter YAML is valid")

    # Check name
    name = fm.get('name')
    if not name or not isinstance(name, str) or not name.strip():
        errors.append("'name' is required and must be a non-empty string")
    else:
        if len(name) > MAX_NAME_LENGTH:
            errors.append(f"'name' too long: {len(name)} chars (max {MAX_NAME_LENGTH})")
        if not HYPHEN_CASE_RE.match(name):
            warnings.append(f"'name' should be hyphen-case: '{name}'")
        else:
            ok(f"name: '{name}'")

    # Check description
    desc = fm.get('description')
    if not desc or not isinstance(desc, str) or not desc.strip():
        errors.append("'description' is required and must be a non-empty string")
    else:
        ok(f"description: '{desc[:60]}...'")

    # Check entrypoint
    entrypoint = fm.get('entrypoint')
    if entrypoint:
        ep_path = os.path.join(skill_dir, entrypoint)
        if not os.path.isfile(ep_path):
            errors.append(f"entrypoint '{entrypoint}' not found at {ep_path}")
        else:
            ok(f"entrypoint: '{entrypoint}' exists")

    # Check requires
    requires = fm.get('requires')
    if requires and isinstance(requires, dict):
        bins = requires.get('bins', [])
        if isinstance(bins, list):
            for b in bins:
                if shutil.which(b):
                    ok(f"requires.bins: '{b}' found in PATH")
                else:
                    warnings.append(f"requires.bins: '{b}' NOT found in PATH")

        envs = requires.get('env', [])
        if isinstance(envs, list):
            for e in envs:
                if os.environ.get(e):
                    ok(f"requires.env: '{e}' is set")
                else:
                    warnings.append(f"requires.env: '{e}' NOT set")

    # Check directory structure
    for subdir in ['scripts', 'references']:
        d = os.path.join(skill_dir, subdir)
        if os.path.isdir(d):
            ok(f"{subdir}/ directory exists")

    print_results(errors, warnings)
    return 1 if errors else 0


def print_results(errors, warnings):
    print()
    if errors:
        print("ERRORS:")
        for e in errors:
            print(f"  ❌ {e}")
    if warnings:
        print("WARNINGS:")
        for w in warnings:
            print(f"  ⚠️  {w}")
    if not errors and not warnings:
        print("🎉 Skill is valid!")
    elif not errors:
        print("✅ Skill is valid (with warnings)")


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <skill-directory>")
        sys.exit(1)
    sys.exit(validate_skill(sys.argv[1]))
