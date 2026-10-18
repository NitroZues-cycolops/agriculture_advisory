#!/usr/bin/env bash
# =====================================================================
# check_css.sh - guard against a damaged stylesheet.
#
# WHY: the home page once rendered unstyled because style.css was damaged
# (templates kept their class names but the rules were gone). This script
# fails loudly when that happens again, so smoke_test.sh and a human both
# notice immediately.
#
# It FAILS when:
#   1. style.css is missing, empty or suspiciously small;
#   2. the { and } counts differ  (a truncated rule);
#   3. the /* and */ counts differ (an unterminated comment);
#   4. a class the templates actually use (tile, card, topbar,
#      main-container and every layout-* class) has no rule in style.css;
#   5. a page forgets to link /css/style.css.
#
# Usage:  bash check_css.sh
# Exit 0 = healthy, non-zero = a real breakage.
# =====================================================================
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
CSS="$ROOT/src/main/resources/static/css/style.css"
TPL="$ROOT/src/main/resources/templates"

# A healthy style.css is tens of KB; anything this small means the file was
# truncated or overwritten with a stub.
MIN_BYTES=2000

FAIL=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }

printf '\n== check_css.sh ==\n'

# --- 1. file present and not suspiciously small -------------------------
if [ ! -f "$CSS" ]; then
  fail "style.css not found at $CSS"
  printf '\nRESULT: CSS BROKEN\n'
  exit 1
fi
SIZE="$(wc -c < "$CSS" | tr -d ' ')"
if [ "${SIZE:-0}" -lt "$MIN_BYTES" ]; then
  fail "style.css is suspiciously small ($SIZE bytes < $MIN_BYTES) - it was truncated"
else
  pass "style.css is $SIZE bytes (not truncated)"
fi

# --- 2. balanced braces -------------------------------------------------
OPEN_BRACE="$(grep -o '{' "$CSS" | wc -l | tr -d ' ')"
CLOSE_BRACE="$(grep -o '}' "$CSS" | wc -l | tr -d ' ')"
if [ "$OPEN_BRACE" = "$CLOSE_BRACE" ]; then
  pass "braces balanced ({ = } = $OPEN_BRACE)"
else
  fail "unbalanced braces: { = $OPEN_BRACE, } = $CLOSE_BRACE"
fi

# --- 3. balanced comments ----------------------------------------------
OPEN_CMT="$(grep -o '/\*' "$CSS" | wc -l | tr -d ' ')"
CLOSE_CMT="$(grep -o '\*/' "$CSS" | wc -l | tr -d ' ')"
if [ "$OPEN_CMT" = "$CLOSE_CMT" ]; then
  pass "comments balanced (/* = */ = $OPEN_CMT)"
else
  fail "unbalanced comments: /* = $OPEN_CMT, */ = $CLOSE_CMT"
fi

# --- 4. classes the templates USE must be DEFINED in the CSS -------------
# Rule: for each class name we care about, if any template mentions it,
# style.css must contain a selector for it (".name" anywhere in the file).
needs_class() { # name
  local cls="$1"
  grep -rqE "class=\"[^\"]*\b${cls}\b" "$TPL" 2>/dev/null || return 0   # not used -> nothing to check
  if grep -qE "\.${cls}([^a-zA-Z0-9_-]|$)" "$CSS"; then
    pass "class .$cls is used by templates and defined in style.css"
  else
    fail "class .$cls is used in templates but has NO rule in style.css"
  fi
}

for cls in tile card topbar main-container; do
  needs_class "$cls"
done

# every layout-* class used in a template must have a definition
LAYOUTS="$(grep -rhoE 'layout-[a-zA-Z0-9_-]+' "$TPL" 2>/dev/null | sort -u)"
for cls in $LAYOUTS; do
  if grep -qE "\.${cls}([^a-zA-Z0-9_-]|$)" "$CSS"; then
    pass "class .$cls is used by templates and defined in style.css"
  else
    fail "class .$cls is used in templates but has NO rule in style.css"
  fi
done

# --- 5. every page links the single stylesheet ---------------------------
MISSING_LINK=0
for page in $(grep -rl '<head' "$TPL" 2>/dev/null); do
  if ! grep -q '/css/style.css' "$page"; then
    fail "$(basename "$page") does not link /css/style.css"
    MISSING_LINK=1
  fi
done
[ "$MISSING_LINK" = "0" ] && pass "every page links /css/style.css"

# --- header -------------------------------------------------------------
if head -5 "$CSS" | grep -qi '<!doctype\|<html'; then
  fail "style.css contains HTML - the file was overwritten by a page"
else
  pass "style.css contains CSS, not HTML"
fi

printf '\n'
if [ "$FAIL" -eq 0 ]; then
  printf 'RESULT: CSS OK\n'
  exit 0
fi
printf 'RESULT: CSS BROKEN (%d problem(s))\n' "$FAIL"
exit 1
