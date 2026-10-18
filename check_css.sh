#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
CSS="src/main/resources/static/css/style.css"
TEMPLATES="src/main/resources/templates"
MIN_BYTES=20000
failed=0

if [[ ! -f "$CSS" ]]; then
  printf 'FAIL CSS file is missing: %s\n' "$CSS"
  exit 1
fi

open_braces="$(tr -cd '{' < "$CSS" | wc -c)"
close_braces="$(tr -cd '}' < "$CSS" | wc -c)"
open_comments="$(grep -o '/\*' "$CSS" | wc -l)"
close_comments="$(grep -o '\*/' "$CSS" | wc -l)"
bytes="$(wc -c < "$CSS")"

if [[ "$open_braces" != "$close_braces" ]]; then
  printf 'FAIL CSS brace count differs: {=%s }=%s\n' "$open_braces" "$close_braces"
  failed=1
else
  printf 'PASS CSS braces balanced (%s pairs)\n' "$open_braces"
fi

if [[ "$open_comments" != "$close_comments" ]]; then
  printf 'FAIL CSS comment count differs: /*=%s */=%s\n' "$open_comments" "$close_comments"
  failed=1
else
  printf 'PASS CSS comments balanced (%s pairs)\n' "$open_comments"
fi

if (( bytes < MIN_BYTES )); then
  printf 'FAIL CSS file unexpectedly small: %s bytes (minimum %s)\n' "$bytes" "$MIN_BYTES"
  failed=1
else
  printf 'PASS CSS size is %s bytes\n' "$bytes"
fi

has_selector() {
  local selector="$1"
  grep -Eq "(^|[},[:space:]])\\.${selector}([.#:[[:space:]{,]|$)" "$CSS"
}

for core in tile topbar main-container; do
  if has_selector "$core"; then
    printf 'PASS template selector .%s exists\n' "$core"
  else
    printf 'FAIL template selector .%s is missing\n' "$core"
    failed=1
  fi
done

mapfile -t card_classes < <(grep -rhoE 'class="[^"]+"' "$TEMPLATES" \
  | sed -E 's/^class="//;s/"$//' | tr ' ' '\n' | grep '^card(__|--)' | sort -u || true)
for card_class in "${card_classes[@]}"; do
  if has_selector "$card_class"; then
    printf 'PASS template selector .%s exists\n' "$card_class"
  else
    printf 'FAIL template selector .%s is missing\n' "$card_class"
    failed=1
  fi
done

mapfile -t layouts < <(grep -rhoE 'th:replace="~\{layouts/main :: main\([^\"]*'"'"'layout-[A-Za-z0-9_-]+'"'"'' "$TEMPLATES" \
  | grep -oE 'layout-[A-Za-z0-9_-]+' | sort -u)
for layout in "${layouts[@]}"; do
  if has_selector "$layout"; then
    printf 'PASS template layout selector .%s exists\n' "$layout"
  else
    printf 'FAIL template layout selector .%s is missing\n' "$layout"
    failed=1
  fi
done

exit "$failed"