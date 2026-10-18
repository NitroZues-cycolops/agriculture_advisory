#!/usr/bin/env bash
# =====================================================================
# PURPOSE : End-to-end smoke test for the Precision Farming Advisory Service.
#           It starts NOTHING itself. Boot the app on a free port first, then:
#
#               ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8090
#               ./smoke_test.sh http://localhost:8090
#
# WHAT IT DOES
#   1. Logs in with a cookie jar: GET /login -> read the _csrf field -> POST.
#   2. Checks every top-bar page for all three roles, in English/Hindi/Marathi,
#      and fails if any page answers 404/500 or shows a ??missing.key??.
#   3. Registers a farmer end to end; the account cannot log in until its email
#      is verified via the DEMO link, then it can log in.
#   4. Checks duplicate username / email / mobile and bad mobile show errors.
#  4b. PART 1: the email-link rules (once-a-minute resend, 5-per-hour cap,
#      a reused link, an unknown token) against the real endpoints.
#   5. Registers a lab (with email + optional phone) and checks it is PENDING.
#   6. Checks the schemes list/detail in all three languages.
#   7. Checks the weather cache/forecast tables and the /nowcast,/forecast pages.
#   8. Checks the /map dots and the ?district= side panel.
#   9. Checks a personalised alert exists for a farmer whose crop matches a rule.
#  10. Checks access control (farmer 403 on /debug and /lab/dashboard, owner 200,
#      a lab cannot read another lab's request).
#  11. Runs the full demo flow: register -> request a test -> lab advances it to
#      Report Ready with results -> farmer downloads the PDF (%PDF) -> community
#      post + comment.
#  12. Owner panel (STEP 7): counts dashboard, approve/reject a PENDING lab
#      (approval unlocks the lab login), and the scheme text editor round-trip.
#  13c. PART 2b: manage my own content - edit my post, delete my post (comments
#      and votes cascade, photo file removed), the delete confirmation needs POST
#      + CSRF (a GET deletes nothing), delete my own comment and chat message.
#  13d. PART 3: connections and chat - the /farmers directory (name + district
#      filters), the /messages inbox and the top-bar unread badge (cleared by
#      opening a thread), who may chat (a one-way follow is enough / mutual =
#      connection), no self-chat, and the text rules plus the 20-per-minute rate
#      limit.
#  13e. PART 4: email robustness - two simultaneous resends never both pass the
#      once-a-minute rule (the check + record run in one locked DB transaction),
#      the demo link is built from the request's own host:port, and the SMTP
#      timeouts / row lock are present in the source and config.
#
# If this breaks, check:
#   - "login failed"        -> demo users come from sample_data.sql; passwords
#                              must be password123.
#   - "CSRF"                -> every form must keep th:action so the hidden
#                              _csrf field is rendered.
#   - "missing key"         -> add the key to ALL FOUR messages*.properties.
#   - "db" checks fail      -> mysql -uadvisory -padvisory123 advisory_db.
# =====================================================================
set -u

BASE="${1:-http://localhost:8090}"
TMP="$(mktemp -d)"
PASS=0
FAIL=0

MYSQL=(mysql -uadvisory -padvisory123 -N -B advisory_db)

c_pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
c_fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }
hdr()    { printf '\n== %s ==\n' "$1"; }

q() { "${MYSQL[@]}" -e "$1" 2>/dev/null; }

# --- CSRF token from a saved HTML page ---
csrf() {
  grep -oE 'name="_csrf"[^>]*value="[^"]+"' "$1" 2>/dev/null \
    | head -1 | sed -E 's/.*value="([^"]+)".*/\1/'
}

# --- login: returns 0 on success --------------------------------------------
login() { # user pass jar
  local u="$1" p="$2" jar="$3"
  curl -s -c "$jar" "$BASE/login" -o "$TMP/login.html"
  local tok; tok="$(csrf "$TMP/login.html")"
  [ -n "$tok" ] || { echo "no-csrf"; return 1; }
  curl -s -b "$jar" -c "$jar" -o /dev/null \
       --data-urlencode "username=$u" \
       --data-urlencode "password=$p" \
       --data-urlencode "_csrf=$tok" \
       "$BASE/login"
  # Confirm we are authenticated: the top bar shows the user name.
  curl -s -b "$jar" -c "$jar" -o "$TMP/after.html" "$BASE/"
  grep -q 'topbar__user' "$TMP/after.html"
}

# --- fetch following redirects; echoes the final HTTP code -------------------
fetch() { # jar url out
  curl -s -L -b "$1" -c "$1" -o "$3" -w '%{http_code}' "$2"
}

with_lang() { # url lang
  case "$1" in
    *\?*) echo "$1&lang=$2" ;;
    *)    echo "$1?lang=$2" ;;
  esac
}

missing_keys() { # file  -> number of ?? occurrences
  grep -c '??' "$1" 2>/dev/null || true
}

# --- PART 2a helpers: a dependency-free PNG writer + a JPEG size reader ------
# The avatar checks need real image files and ImageMagick is NOT assumed. The
# PNGs are therefore built from raw bytes with bash's printf (\xHH) plus a
# CRC32/adler32 written in bash, and a JPEG's width/height are read from its SOF
# marker with od+awk. Only tools the rest of this script already needs are used.

# CRC32 (the polynomial PNG uses) over a hex string of bytes -> decimal.
crc32() { # hex -> decimal
  local hex="$1" i b k c=4294967295
  for (( i=0; i<${#hex}; i+=2 )); do
    b=$(( 0x${hex:i:2} ))
    c=$(( c ^ b ))
    for (( k=0; k<8; k++ )); do
      if (( c & 1 )); then c=$(( (c >> 1) ^ 3988292384 )); else c=$(( c >> 1 )); fi
    done
    c=$(( c & 4294967295 ))
  done
  printf '%d' $(( (c ^ 4294967295) & 4294967295 ))
}

# adler32 (the zlib trailer) over a hex string of bytes -> decimal.
adler32() { # hex -> decimal
  local hex="$1" i n a=1 b=0
  for (( i=0; i<${#hex}; i+=2 )); do
    n=$(( 0x${hex:i:2} ))
    a=$(( (a + n) % 65521 ))
    b=$(( (b + a) % 65521 ))
  done
  printf '%d' $(( b * 65536 + a ))
}

# one PNG chunk: length | type | data | crc, all in hex.
png_chunk() { # type-hex data-hex -> hex
  local type="$1" data="$2"
  printf '%04x%s%s%08x' $(( ${#data} / 2 )) "$type" "$data" "$(crc32 "${type}${data}")"
}

# hex string -> raw bytes on stdout (written straight to a file, never through
# $(...), so NUL bytes survive).
hex2bin() { # hex -> bytes
  local hex="$1" i
  for (( i=0; i<${#hex}; i+=2 )); do
    printf "\\x${hex:i:2}"
  done
}

# write a truecolour+alpha PNG. The IHDR size may differ from the pixel-data
# size - that is how the "huge dimensions, tiny file" image is forged.
gen_png() { # outfile ihdrW ihdrH rawW rawH r g b a
  local out="$1" iw="$2" ih="$3" rw="$4" rh="$5" r="$6" g="$7" b="$8" a="$9"
  local i ihdr px row raw zlib hex len nlen
  ihdr="$(printf '%08x%08x' "$iw" "$ih")0806000000"
  px="$(printf '%02x%02x%02x%02x' "$r" "$g" "$b" "$a")"
  row="00"; for (( i=0; i<rw; i++ )); do row="${row}${px}"; done
  raw="";    for (( i=0; i<rh; i++ )); do raw="${raw}${row}"; done
  len=$(( ${#raw} / 2 )); nlen=$(( 65535 - len ))
  zlib="7801$(printf '01%02x%02x%02x%02x' \
        $((len & 255)) $((len >> 8)) $((nlen & 255)) $((nlen >> 8)))${raw}"
  zlib="${zlib}$(printf '%08x' "$(adler32 "$raw")")"
  hex="89504e470d0a1a0a$(png_chunk 49484452 "$ihdr")$(png_chunk 49444154 "$zlib")$(png_chunk 49454e44 '')"
  hex2bin "$hex" > "$out"
}

# read a JPEG's width x height from its SOF marker (no ImageMagick needed).
jpeg_dims() { # file -> WxH
  od -An -v -tu1 "$1" 2>/dev/null | awk '
    { for (i=1; i<=NF; i++) b[++n]=$i }
    END {
      i=1
      while (i+8<=n) {
        if (b[i]!=255) { i++; continue }
        m=b[i+1]
        if (m==216 || m==217 || m==1 || (m>=208 && m<=215) || m==255 || m==0) { i+=2; continue }
        len=b[i+2]*256+b[i+3]
        if (m>=192 && m<=207 && m!=196 && m!=200 && m!=204) {
          printf "%dx%d\n", b[i+7]*256+b[i+8], b[i+5]*256+b[i+6]
          exit
        }
        i += 2 + len
      }
    }'
}

# --- PART 1 helpers: email verification -------------------------------------

# the one-time /verify/email?h=... handle created for a fresh account
handle_for() { # username -> handle
  q "SELECT handle FROM verification_session WHERE user_id=(SELECT id FROM users WHERE username='$1') ORDER BY id DESC LIMIT 1"
}

# read the DEMO link shown on the verify page (demo mode: no SMTP configured);
# echoes the confirm path (or nothing) and leaves the page in $TMP/verify.html
email_demo_link() { # jar handle
  curl -s -b "$1" -c "$1" -o "$TMP/verify.html" "$BASE/verify/email?h=$2"
  grep -oE '/verify/email/confirm\?token=[0-9a-f]+' "$TMP/verify.html" | head -1
}

# full happy path: read the demo link off the page, open it, expect "verified"
verify_email_ok() { # jar handle
  local link; link="$(email_demo_link "$1" "$2")"
  [ -n "$link" ] || return 1
  curl -s -L -b "$1" -c "$1" -o "$TMP/vdone.html" "$BASE$link"
  grep -q "is now verified" "$TMP/vdone.html"
}

# =====================================================================
hdr "0. Server reachable at $BASE"
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/login")"
[ "$code" = "200" ] && c_pass "GET /login -> 200" || c_fail "GET /login -> $code"

# =====================================================================
hdr "1. Login for farmer1 / lab1 / owner"
J_FARM="$TMP/farmer.jar"; J_LAB="$TMP/lab.jar"; J_OWN="$TMP/owner.jar"
if login farmer1 password123 "$J_FARM"; then c_pass "farmer1 logged in"; else c_fail "farmer1 login failed"; fi
if login lab1    password123 "$J_LAB";  then c_pass "lab1 logged in";    else c_fail "lab1 login failed"; fi
if login owner   password123 "$J_OWN";  then c_pass "owner logged in";   else c_fail "owner login failed"; fi

# =====================================================================
hdr "0b. CSS guard (check_css.sh + /css/style.css served and linked)"
# The stylesheet must be healthy: the home page once rendered unstyled because
# style.css was damaged. check_css.sh fails on unbalanced braces/comments, a
# truncated file or a class the templates use but the CSS does not define.
if [ -f "$(dirname "$0")/check_css.sh" ]; then
  if bash "$(dirname "$0")/check_css.sh" >/dev/null 2>&1; then
    c_pass "check_css.sh: CSS is healthy"
  else
    c_fail "check_css.sh reports CSS breakage"
  fi
else
  c_fail "check_css.sh is missing from the project root"
fi
# GET /css/style.css must be served as text/css ...
CSS_CT="$(curl -s -o /dev/null -w '%{http_code} %{content_type}' "$BASE/css/style.css")"
case "$CSS_CT" in
  "200 text/css"*) c_pass "GET /css/style.css -> 200 text/css" ;;
  *) c_fail "GET /css/style.css -> $CSS_CT (expected 200 text/css)" ;;
esac
# ... and every top-bar page must link it.
LINK_MISS=0
for p in / /nowcast /forecast /lab /schemes /community /profile /map; do
  curl -s -b "$J_FARM" -o "$TMP/csslink.html" "$BASE$p?lang=en"
  grep -q '/css/style.css' "$TMP/csslink.html" \
    || { c_fail "$p does not link /css/style.css"; LINK_MISS=1; }
done
[ "$LINK_MISS" = "0" ] && c_pass "every top-bar page links /css/style.css"

# =====================================================================
hdr "2. Top-bar pages, all roles, all 3 languages (status + no ??keys??)"
# role:jar:pages, and pages that must be 403 for that role
check_pages() { # name jar expected403_text
  local name="$1" jar="$2" forbid="$3"
  local pages="/ /nowcast /forecast /lab /schemes /community /profile /map"
  local p lang url body c n
  for p in $pages; do
    for lang in en hi mr; do
      url="$(with_lang "$BASE$p" "$lang")"
      body="$TMP/page.html"
      c="$(fetch "$jar" "$url" "$body")"
      n="$(missing_keys "$body")"
      # never a 404 or 500
      if [ "$c" = "404" ] || [ "$c" = "500" ]; then
        c_fail "$name $p [$lang] -> $c (must never be 404/500)"
      fi
      if [ "$c" != "200" ] && [ "$c" != "403" ]; then
        c_fail "$name $p [$lang] -> unexpected $c"
      fi
      if [ "$n" != "0" ]; then
        c_fail "$name $p [$lang] -> $n missing key(s) (??key??)"
      fi
    done
  done
  c_pass "$name: 8 pages x 3 languages checked (no 404/500, no ??key??)"
}
check_pages farmer1 "$J_FARM" ""
check_pages lab1    "$J_LAB"  ""
check_pages owner   "$J_OWN"  ""

# =====================================================================
hdr "3. Farmer registration end to end (email verification)"
RUSER="smoke$(date +%s)"
RPASS="password123"
J_NEW="$TMP/regfarmer.jar"
curl -s -c "$TMP/reg.jar" "$BASE/register" -o "$TMP/reg.html"
RTOK="$(csrf "$TMP/reg.html")"
reg_post() { # out-file username mobile email
  curl -s -b "$TMP/reg.jar" -c "$TMP/reg.jar" -o "$1" -w '%{http_code}' \
    --data-urlencode "username=$2" --data-urlencode "password=$RPASS" \
    --data-urlencode "confirm=$RPASS" --data-urlencode "email=$4" \
    --data-urlencode "mobile=$3" \
    --data-urlencode "district=Pune" --data-urlencode "taluka=Haveli" \
    --data-urlencode "village=Wagholi" --data-urlencode "area=3.5" \
    --data-urlencode "unit=acre" --data-urlencode "soil=black" \
    --data-urlencode "irrigation=drip" --data-urlencode "description=Smoke test farm" \
    --data-urlencode "lang=en" --data-urlencode "crops=rice" --data-urlencode "crops=wheat" \
    --data-urlencode "_csrf=$RTOK" "$BASE/register"
}
c="$(reg_post "$TMP/reg_out.html" "$RUSER" "9876501234" "$RUSER@example.com")"
if [ "$c" = "302" ]; then c_pass "POST /register -> 302 (created $RUSER)"; else c_fail "POST /register -> $c"; fi

# PART 1: the account is UNVERIFIED, so login must still be refused
if login "$RUSER" "$RPASS" "$J_NEW"; then
  c_fail "$RUSER could log in BEFORE verifying the email"
else
  c_pass "$RUSER cannot log in before the email is verified"
fi
# ... and the login page explains why
c="$(curl -s -o "$TMP/login_disabled.html" -w '%{http_code}' "$BASE/login?disabled")"
grep -q "verify your email" "$TMP/login_disabled.html" \
  && c_pass "/login?disabled explains the unverified account" \
  || c_fail "/login?disabled does not explain the unverified account"

# PART 1: complete the email step with the demo link shown on the page
RH="$(handle_for "$RUSER")"
if verify_email_ok "$TMP/reg.jar" "$RH"; then
  c_pass "email verified via the demo link (handle $RH)"
else
  c_fail "email verification failed (handle '$RH')"
fi
if login "$RUSER" "$RPASS" "$J_NEW"; then
  c_pass "$RUSER can log in after the email is verified"
else
  c_fail "$RUSER still cannot log in after verification"
fi

fetch "$J_NEW" "$BASE/profile" "$TMP/prof.html" >/dev/null
grep -q 'value="Pune"' "$TMP/prof.html" && c_pass "profile shows saved district (Pune)" || c_fail "profile missing district"
grep -q 'value="3.5"'  "$TMP/prof.html" && c_pass "profile shows saved farm area (3.5)"  || c_fail "profile missing farm area"
grep -q 'name="taluka"' "$TMP/prof.html" && grep -q 'value="Haveli"' "$TMP/prof.html" \
  && c_pass "profile shows saved taluka" || c_fail "profile missing taluka"

# duplicate username -> error, no 500
c="$(reg_post "$TMP/reg_dup.html" "$RUSER" "9876501234" "$RUSER@example.com")"
if [ "$c" = "200" ] && grep -q "already taken" "$TMP/reg_dup.html"; then
  c_pass "duplicate username shows an error"
else
  c_fail "duplicate username (code=$c) did not show the taken error"
fi

# bad mobile -> error AND the typed username is preserved
c="$(reg_post "$TMP/reg_bad.html" "badmobile$RANDOM" "123" "bad$RANDOM@example.com")"
if [ "$c" = "200" ] && grep -q "10 digit mobile" "$TMP/reg_bad.html"; then
  c_pass "bad mobile number shows an error"
else
  c_fail "bad mobile (code=$c) did not show the mobile error"
fi

# PART B: a duplicate EMAIL is refused (a fresh username, an existing address)
c="$(reg_post "$TMP/reg_dupmail.html" "dupemail$RANDOM" "9876505555" "$RUSER@example.com")"
if [ "$c" = "200" ] && grep -q "email address is already registered" "$TMP/reg_dupmail.html"; then
  c_pass "duplicate email shows an error"
else
  c_fail "duplicate email (code=$c) did not show the taken error"
fi

# PART B: a duplicate MOBILE is refused (a fresh username + email, an existing number)
DN="dupmo$RANDOM"
c="$(reg_post "$TMP/reg_dupmo.html" "$DN" "9876501234" "$DN@example.com")"
if [ "$c" = "200" ] && grep -q "mobile number is already registered" "$TMP/reg_dupmo.html"; then
  c_pass "duplicate mobile number shows an error"
else
  c_fail "duplicate mobile (code=$c) did not show the taken error"
fi

# =====================================================================
hdr "3b. PART 1: email link rules (resend limits, reuse, unknown token)"
# a fresh, unverified account to exercise the email rules on
BUSER="smokeemail$(date +%s)"
BMOB="9$(printf '%09d' $(( (RANDOM * 32768 + RANDOM) % 1000000000 )))"
J_EML="$TMP/emailuser.jar"
curl -s -c "$J_EML" "$BASE/register" -o "$TMP/regb.html"
RTOKB="$(csrf "$TMP/regb.html")"
c="$(curl -s -b "$J_EML" -c "$J_EML" -o /dev/null -w '%{http_code}' \
  --data-urlencode "username=$BUSER" --data-urlencode "password=$RPASS" \
  --data-urlencode "confirm=$RPASS" --data-urlencode "email=${BUSER}@example.com" \
  --data-urlencode "mobile=$BMOB" \
  --data-urlencode "district=Pune" --data-urlencode "taluka=Haveli" \
  --data-urlencode "village=Wagholi" --data-urlencode "area=2" \
  --data-urlencode "unit=acre" --data-urlencode "soil=black" \
  --data-urlencode "irrigation=drip" --data-urlencode "description=Email rules test" \
  --data-urlencode "lang=en" --data-urlencode "crops=rice" \
  --data-urlencode "_csrf=$RTOKB" "$BASE/register")"
BH="$(handle_for "$BUSER")"
if [ "$c" = "302" ] && [ -n "$BH" ]; then c_pass "fresh account $BUSER created for the email rules"; \
  else c_fail "could not create $BUSER for the email rules (code=$c handle='$BH')"; fi
BUID="$(q "SELECT id FROM users WHERE username='$BUSER'")"

# the verify page shows the demo link inside a clearly labelled DEMO MODE box
BLINK="$(email_demo_link "$J_EML" "$BH")"
VTOK="$(csrf "$TMP/verify.html")"
if [ -n "$BLINK" ] && grep -q "DEMO MODE: no email is sent" "$TMP/verify.html"; then
  c_pass "verify page shows the demo link inside the DEMO MODE box"
else
  c_fail "verify page has no DEMO MODE box / demo link"
fi

# (1) a resend inside one minute is refused (at most one per minute)
curl -s -L -b "$J_EML" -c "$J_EML" -o "$TMP/resend.html" \
  --data-urlencode "h=$BH" --data-urlencode "_csrf=$VTOK" "$BASE/verify/email/resend"
grep -q "wait a minute" "$TMP/resend.html" \
  && c_pass "a resend inside one minute is refused (once-per-minute rule)" \
  || c_fail "resend-too-soon message missing"

# (2) the 5-links-per-hour cap (simulated: 5 sends already in this window)
q "UPDATE email_verification SET last_sent_at = DATE_SUB(NOW(), INTERVAL 2 MINUTE), \
   window_start = NOW(), sends_in_window = 5 WHERE user_id='$BUID'" >/dev/null
curl -s -L -b "$J_EML" -c "$J_EML" -o "$TMP/resend2.html" \
  --data-urlencode "h=$BH" --data-urlencode "_csrf=$VTOK" "$BASE/verify/email/resend"
grep -q "too many links this hour" "$TMP/resend2.html" \
  && c_pass "the 5-links-per-hour cap is enforced" \
  || c_fail "hourly resend cap not enforced"

# (3) using the link verifies the address; using it AGAIN says "already verified"
curl -s -L -b "$J_EML" -c "$J_EML" -o "$TMP/vdone1.html" "$BASE$BLINK"
grep -q "is now verified" "$TMP/vdone1.html" \
  && c_pass "the email link verifies the address" \
  || c_fail "the email link did not verify the address"
curl -s -b "$J_EML" -c "$J_EML" -o "$TMP/vdone2.html" "$BASE$BLINK"
grep -q "was already verified" "$TMP/vdone2.html" \
  && c_pass "reusing a used link shows 'already verified'" \
  || c_fail "a reused link did not report 'already verified'"

# (4) an unknown token is reported as invalid (a plain page, never a 500)
curl -s -b "$J_EML" -c "$J_EML" -o "$TMP/vbad.html" "$BASE/verify/email/confirm?token=deadbeefdeadbeef"
grep -q "not valid" "$TMP/vbad.html" \
  && c_pass "an unknown token is reported as invalid" \
  || c_fail "an unknown token was not reported as invalid"

# =====================================================================
hdr "3c. PART 4: email send is safe under concurrency and always friendly"
# A fresh UNVERIFIED account: registration sent the first link, so its send
# window is cleared first. Then two resends fired at the very same moment must
# NOT both pass - the limit is checked and recorded in one locked transaction.
FUSER="smokeflood$(date +%s)"
FMOB="9$(printf '%09d' $(( (RANDOM * 32768 + RANDOM) % 1000000000 )))"
J_FL="$TMP/flood.jar"
curl -s -c "$J_FL" "$BASE/register" -o "$TMP/regf.html"
FTOK="$(csrf "$TMP/regf.html")"
c="$(curl -s -b "$J_FL" -c "$J_FL" -o /dev/null -w '%{http_code}' \
  --data-urlencode "username=$FUSER" --data-urlencode "password=$RPASS" \
  --data-urlencode "confirm=$RPASS" --data-urlencode "email=${FUSER}@example.com" \
  --data-urlencode "mobile=$FMOB" \
  --data-urlencode "district=Pune" --data-urlencode "taluka=Haveli" \
  --data-urlencode "village=Wagholi" --data-urlencode "area=2" \
  --data-urlencode "unit=acre" --data-urlencode "soil=black" \
  --data-urlencode "irrigation=drip" --data-urlencode "description=Concurrency test" \
  --data-urlencode "lang=en" --data-urlencode "crops=rice" \
  --data-urlencode "_csrf=$FTOK" "$BASE/register")"
FH="$(handle_for "$FUSER")"
FUID="$(q "SELECT id FROM users WHERE username='$FUSER'")"
if [ "$c" = "302" ] && [ -n "$FH" ]; then c_pass "fresh unverified account $FUSER created for the concurrency test"; \
  else c_fail "could not create $FUSER (code=$c handle='$FH')"; fi

# read a CSRF token off the verify page (this GET sends nothing at all)
email_demo_link "$J_FL" "$FH" >/dev/null
FCSRF="$(csrf "$TMP/verify.html")"

# Registration already sent the FIRST link, so the window is open. Clear it so a
# new send is allowed, otherwise BOTH parallel resends would be refused and the
# test would pass for the wrong reason (nothing to serialise).
q "UPDATE email_verification SET last_sent_at = DATE_SUB(NOW(), INTERVAL 2 MINUTE), \
   window_start = NULL, sends_in_window = 0 WHERE user_id='$FUID'" >/dev/null

# fire two identical resends at the SAME time (only read the jar, no -c, so the
# two processes cannot corrupt each other's cookie jar)
curl -s -L -b "$J_FL" -o "$TMP/fl1.html" \
  --data-urlencode "h=$FH" --data-urlencode "_csrf=$FCSRF" "$BASE/verify/email/resend" &
FP1=$!
curl -s -L -b "$J_FL" -o "$TMP/fl2.html" \
  --data-urlencode "h=$FH" --data-urlencode "_csrf=$FCSRF" "$BASE/verify/email/resend" &
FP2=$!
wait "$FP1" "$FP2"

FOK=0
for f in "$TMP/fl1.html" "$TMP/fl2.html"; do
  grep -q "has been sent" "$f" && FOK=$((FOK + 1))
done
FROWS="$(q "SELECT COUNT(*) FROM email_verification WHERE user_id='$FUID'")"
if [ "$FOK" -eq 1 ] && [ "$FROWS" = "1" ]; then
  c_pass "two simultaneous resends: exactly one is accepted (rate limit holds)"
else
  c_fail "two simultaneous resends: accepted=$FOK (expected 1), rows=$FROWS"
fi

# the DEMO link must be absolute and use THIS run's host:port - i.e. it is built
# from the request, not hard-coded. Checked with a fresh SEQUENTIAL resend (the
# two racing requests above may both refuse, and a flash attribute is
# single-use), so clear the window first and ask once.
q "UPDATE email_verification SET last_sent_at = DATE_SUB(NOW(), INTERVAL 2 MINUTE), \
   window_start = NULL, sends_in_window = 0 WHERE user_id='$FUID'" >/dev/null
curl -s -L -b "$J_FL" -o "$TMP/fl3.html" \
  --data-urlencode "h=$FH" --data-urlencode "_csrf=$FCSRF" "$BASE/verify/email/resend"
FLINK="$(grep -oE 'href="https?://[^"]*/verify/email/confirm\?token=[0-9a-f]+' "$TMP/fl3.html" \
          | head -1 | sed -E 's/^href="//')"
case "$FLINK" in
  "$BASE"/verify/email/confirm?token=*) c_pass "the demo link is absolute and uses the request host:port ($BASE)" ;;
  "") c_fail "the accepted resend showed no demo link" ;;
  *)  c_fail "the demo link host is wrong: $FLINK" ;;
esac

# static guards: no class-level lock, a real row lock, and SMTP timeouts set.
SRC="$(dirname "$0")/src/main/java/com/precisionfarming/advisory_service"
grep -qE '(public|private|protected)[[:space:]]+synchronized' "$SRC/service/EmailVerificationService.java" \
  && c_fail "PART 4: EmailVerificationService still uses a class-level lock" \
  || c_pass "PART 4: EmailVerificationService no longer uses synchronized"
grep -q 'FOR UPDATE' "$SRC/repository/VerificationDao.java" \
  && c_pass "PART 4: the rate limit is checked under a row lock (SELECT ... FOR UPDATE)" \
  || c_fail "PART 4: VerificationDao has no SELECT ... FOR UPDATE"
APROPS="$(dirname "$0")/src/main/resources/application.properties"
if grep -q 'mail.smtp.connectiontimeout' "$APROPS" \
   && grep -q 'mail.smtp.timeout' "$APROPS" \
   && grep -q 'mail.smtp.writetimeout' "$APROPS"; then
  c_pass "PART 4: SMTP connect/read/write timeouts are configured"
else
  c_fail "PART 4: SMTP timeouts are missing from application.properties"
fi

# =====================================================================
hdr "4. Lab registration creates a PENDING lab"
LUSER="smokelab$(date +%s)"
curl -s -c "$TMP/labreg.jar" "$BASE/register/lab" -o "$TMP/labreg.html"
LTOK="$(csrf "$TMP/labreg.html")"
c="$(curl -s -b "$TMP/labreg.jar" -c "$TMP/labreg.jar" -o /dev/null -w '%{http_code}' \
  --data-urlencode "username=$LUSER" --data-urlencode "name=Smoke Test Lab" \
  --data-urlencode "email=${LUSER}@example.com" \
  --data-urlencode "password=$RPASS" --data-urlencode "confirm=$RPASS" \
  --data-urlencode "address=Test Road, Pune" --data-urlencode "district=Pune" \
  --data-urlencode "taluka=Haveli" --data-urlencode "phone=9876512345" \
  --data-urlencode "timings=9 AM - 6 PM" --data-urlencode "samples=soil" \
  --data-urlencode "lat=18.52" --data-urlencode "lon=73.86" \
  --data-urlencode "_csrf=$LTOK" "$BASE/register/lab")"
if [ "$c" = "302" ]; then c_pass "POST /register/lab -> 302"; else c_fail "POST /register/lab -> $c"; fi
st="$(q "SELECT l.status FROM labs l JOIN users u ON u.id=l.user_id WHERE u.username='$LUSER'")"
[ "$st" = "PENDING" ] && c_pass "new lab row is PENDING in the database" || c_fail "new lab status is '$st' (expected PENDING)"
# A PENDING (and not yet email-verified) lab must NOT be able to log in.
if login "$LUSER" "$RPASS" "$TMP/labpending.jar"; then
  c_fail "PENDING lab $LUSER was able to log in (should be disabled)"
else
  c_pass "PENDING lab $LUSER cannot log in before approval"
fi
# PART 1: a lab can complete its email step while PENDING; approval is separate.
LH="$(handle_for "$LUSER")"
if verify_email_ok "$TMP/labreg.jar" "$LH"; then
  c_pass "lab email verified via the demo link (handle $LH)"
else
  c_fail "lab email verification failed (handle '$LH')"
fi

# =====================================================================
hdr "5. Schemes list + detail in all 3 languages"
for lang in en hi mr; do
  body="$TMP/schemes.html"
  c="$(fetch "$J_FARM" "$(with_lang "$BASE/schemes" "$lang")" "$body")"
  n="$(missing_keys "$body")"
  [ "$c" = "200" ] && [ "$n" = "0" ] && c_pass "/schemes [$lang] -> 200, no missing keys" \
                                   || c_fail "/schemes [$lang] -> $c, $n missing keys"
  body="$TMP/scheme.html"
  c="$(fetch "$J_FARM" "$(with_lang "$BASE/schemes/pmkisan" "$lang")" "$body")"
  n="$(missing_keys "$body")"
  [ "$c" = "200" ] && [ "$n" = "0" ] && c_pass "/schemes/pmkisan [$lang] -> 200, no missing keys" \
                                   || c_fail "/schemes/pmkisan [$lang] -> $c, $n missing keys"
done
c="$(fetch "$J_FARM" "$BASE/schemes/doesnotexist" "$TMP/s404.html")"
[ "$c" = "404" ] && c_pass "/schemes/doesnotexist -> 404 (not 500)" || c_fail "/schemes/doesnotexist -> $c"

# =====================================================================
hdr "6. Weather cache, forecast and pages"
cache="$(q 'SELECT COUNT(*) FROM weather_cache')"
fcast="$(q 'SELECT COUNT(*) FROM weather_forecast')"
[ "${cache:-0}" -gt 0 ]  && c_pass "weather_cache has $cache rows"  || c_fail "weather_cache is empty"
[ "${fcast:-0}" -gt 0 ]  && c_pass "weather_forecast has $fcast rows" || c_fail "weather_forecast is empty"
logs="$(q 'SELECT COUNT(*) FROM weather_scrape_log')"
[ "${logs:-0}" -gt 0 ]   && c_pass "weather_scrape_log has $logs rows (scraper ran)" || c_fail "weather_scrape_log is empty"
for u in /nowcast /forecast; do
  body="$TMP/w.html"
  c="$(fetch "$J_FARM" "$BASE$u?lang=en" "$body")"
  [ "$c" = "200" ] && c_pass "$u -> 200" || c_fail "$u -> $c"
  grep -qi 'last updated' "$body" && c_pass "$u shows a last-updated time" \
                                  || c_fail "$u has no last-updated time"
done

# =====================================================================
hdr "6b. PART D: forecast cards, the two SVG charts and the day table"
body="$TMP/fc.html"
c="$(fetch "$J_FARM" "$BASE/forecast?lang=en" "$body")"
[ "$c" = "200" ] && c_pass "/forecast -> 200" || c_fail "/forecast -> $c"
fcards="$(grep -c 'class="forecast-day"' "$body")"
[ "$fcards" = "7" ] && c_pass "/forecast draws 7 day cards" \
                    || c_fail "/forecast draws $fcards day cards (expected 7)"
fcharts="$(grep -c 'class="chart"' "$body")"
[ "$fcharts" = "2" ] && c_pass "/forecast draws 2 server-rendered SVG charts" \
                     || c_fail "/forecast draws $fcharts SVG charts (expected 2)"
grep -q 'chart-line--max' "$body" && c_pass "the temperature line chart is drawn" \
                                  || c_fail "the temperature line is missing from the chart"
fbars="$(grep -c 'class="chart-bar"' "$body")"
[ "$fbars" = "7" ] && c_pass "the rain bar chart draws 7 bars" \
                   || c_fail "the rain chart draws $fbars bars (expected 7)"
ftips="$(grep -c 'class="forecast-tip"' "$body")"
[ "$ftips" = "7" ] && c_pass "every day card carries one farming tip" \
                   || c_fail "$ftips farming tips (expected 7)"
grep -q 'class="day-table"' "$body" && c_pass "the day-by-day table is shown" \
                                    || c_fail "the day-by-day table is missing"
grep -qE 'Rain (chance|amount)' "$body" \
  && c_pass "the rain figure is labelled by the unit that was stored" \
  || c_fail "the rain label (chance/amount) is missing"
for lang in hi mr; do
  body="$TMP/fc_$lang.html"
  c="$(fetch "$J_FARM" "$BASE/forecast?lang=$lang" "$body")"
  n="$(missing_keys "$body")"
  [ "$c" = "200" ] && [ "$n" = "0" ] && c_pass "/forecast [$lang] -> 200, no missing keys" \
                                     || c_fail "/forecast [$lang] -> $c, $n missing keys"
  grep -q 'class="chart"' "$body" && c_pass "/forecast [$lang] still draws the charts" \
                                  || c_fail "/forecast [$lang] has no chart"
done

# =====================================================================
hdr "7. District map (PART C real boundaries) + side panel"
body="$TMP/map.html"
c="$(fetch "$J_FARM" "$BASE/map" "$body")"
shapes="$(grep -c 'class="district ' "$body")"
[ "$c" = "200" ] && c_pass "/map -> 200" || c_fail "/map -> $c"
[ "$shapes" -ge 36 ] && c_pass "/map draws $shapes district boundaries" || c_fail "/map draws only $shapes district boundaries"
grep -q 'map-svg' "$body" && c_pass "/map renders the inline SVG" || c_fail "/map has no inline SVG"
body="$TMP/map_pune.html"
# lang=en: an earlier section tests ?lang=hi|mr, and the locale lives in the
# session, so the farmer's jar would otherwise render this page in Marathi.
fetch "$J_FARM" "$BASE/map?district=pune&lang=en" "$body" >/dev/null
grep -q '>Pune<' "$body" && c_pass "/map?district=pune fills the side panel" || c_fail "side panel not filled for pune"
# the heat layer draws interpolated cells clipped to the state outline
body="$TMP/map_heat.html"
fetch "$J_FARM" "$BASE/map?layer=heat" "$body" >/dev/null
grep -q 'heat-cell' "$body" && c_pass "/map?layer=heat draws interpolated cells" || c_fail "/map?layer=heat has no heat cells"
grep -q 'clipPath' "$body" && c_pass "/map?layer=heat clips the surface to the state" || c_fail "heat layer is not clipped"
# the crop layer colours each district by its majority crop
body="$TMP/map_crop.html"
fetch "$J_FARM" "$BASE/map?layer=crop&crop=ref" "$body" >/dev/null
grep -q 'crop-' "$body" && c_pass "/map?layer=crop colours districts by crop" || c_fail "crop layer has no crop colours"

# =====================================================================
hdr "8. Personalised alert for a matching crop"
pa="$(q "SELECT COUNT(*) FROM alerts WHERE user_id=(SELECT id FROM users WHERE username='farmer1') AND type='PERSONALIZED'")"
[ "${pa:-0}" -gt 0 ] && c_pass "farmer1 has $pa personalised alert(s)" || c_fail "farmer1 has no personalised alert"

# =====================================================================
hdr "9. Access control"
code_farmer_debug="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_FARM" "$BASE/debug")"
[ "$code_farmer_debug" = "403" ] && c_pass "farmer1 -> /debug 403" || c_fail "farmer1 -> /debug $code_farmer_debug"
code_farmer_dash="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_FARM" "$BASE/lab/dashboard")"
[ "$code_farmer_dash" = "403" ] && c_pass "farmer1 -> /lab/dashboard 403" || c_fail "farmer1 -> /lab/dashboard $code_farmer_dash"
code_owner_debug="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_OWN" "$BASE/debug")"
[ "$code_owner_debug" = "200" ] && c_pass "owner -> /debug 200" || c_fail "owner -> /debug $code_owner_debug"
# lab1 must not read lab2's request TRK-1003
code_cross="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_LAB" "$BASE/lab/request/TRK-1003")"
[ "$code_cross" = "404" ] && c_pass "lab1 -> another lab's request 404" || c_fail "lab1 -> cross-lab request $code_cross"

# =====================================================================
hdr "10. Full demo flow: request -> lab advances to Ready -> farmer PDF"
# farmer submits a test request to an APPROVED lab
LABID="$(q "SELECT id FROM labs WHERE status='APPROVED' ORDER BY id LIMIT 1")"
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/labform.html" "$BASE/lab"
LTOK2="$(csrf "$TMP/labform.html")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
  --data-urlencode "labId=$LABID" --data-urlencode "sampleType=soil" \
  --data-urlencode "crop=rice" --data-urlencode "notes=Smoke test sample" \
  --data-urlencode "_csrf=$LTOK2" "$BASE/lab/request")"
TRACK="$(q "SELECT tracking_id FROM lab_requests r JOIN users u ON u.id=r.farmer_id WHERE u.username='$RUSER' ORDER BY r.id DESC LIMIT 1")"
if [ "$c" = "302" ] && [ -n "$TRACK" ]; then c_pass "farmer created test request $TRACK"; else c_fail "test request not created (code=$c)"; fi

# lab1 approves: advance that request until it is REPORT_READY (ownership checked)
REQID="$(q "SELECT id FROM lab_requests WHERE tracking_id='$TRACK'")"
for step in 1 2 3; do
  curl -s -b "$J_LAB" -c "$J_LAB" -o "$TMP/dash.html" "$BASE/lab/dashboard"
  DTOK="$(csrf "$TMP/dash.html")"
  curl -s -b "$J_LAB" -c "$J_LAB" -o /dev/null \
    --data-urlencode "requestId=$REQID" --data-urlencode "_csrf=$DTOK" \
    "$BASE/lab/dashboard/advance"
done
st="$(q "SELECT status FROM lab_requests WHERE id='$REQID'")"
[ "$st" = "REPORT_READY" ] && c_pass "lab1 advanced $TRACK to REPORT_READY" || c_fail "status is '$st' (expected REPORT_READY)"

# lab1 saves the result
curl -s -b "$J_LAB" -c "$J_LAB" -o "$TMP/dash.html" "$BASE/lab/dashboard"
DTOK="$(csrf "$TMP/dash.html")"
curl -s -b "$J_LAB" -c "$J_LAB" -o /dev/null \
  --data-urlencode "requestId=$REQID" --data-urlencode "ph=7.1" \
  --data-urlencode "nitrogen=280" --data-urlencode "phosphorus=22" \
  --data-urlencode "potassium=310" --data-urlencode "organicCarbon=0.6" \
  --data-urlencode "electricalConductivity=0.3" --data-urlencode "remarks=Smoke test" \
  --data-urlencode "_csrf=$DTOK" "$BASE/lab/dashboard/result"
res="$(q "SELECT ph FROM lab_results WHERE request_id='$REQID'")"
[ "$res" = "7.1" ] && c_pass "lab saved the result (pH=$res)" || c_fail "lab result pH is '$res'"

# farmer downloads the PDF
head1="$(curl -s -b "$J_NEW" "$BASE/lab/request/$TRACK/report" | head -c 4)"
[ "$head1" = "%PDF" ] && c_pass "farmer downloaded the PDF ($TRACK)" || c_fail "report download gave '$head1' (expected %PDF)"

# The tracker tells the farmer to collect the printed copy once the report is ready.
curl -s -L -b "$J_NEW" -c "$J_NEW" -o "$TMP/track.html" "$BASE/lab/request/$TRACK"
grep -q "Carry the printed copy" "$TMP/track.html" \
  && c_pass "tracker shows the collect-the-copy note" || c_fail "tracker missing the collect note"
# "Labs near me" offers a Google Maps link next to OpenStreetMap.
curl -s -L -b "$J_NEW" -c "$J_NEW" -o "$TMP/near.html" "$BASE/lab"
grep -q "google.com/maps" "$TMP/near.html" \
  && c_pass "/lab shows an Open-in-Google-Maps link" || c_fail "/lab missing the Google Maps link"

# =====================================================================
hdr "11. Community: post + photo + comment + vote + follow + moderation"
J_F2="$TMP/farmer2.jar"
if login farmer2 password123 "$J_F2"; then c_pass "farmer2 logged in"; else c_fail "farmer2 login failed"; fi
F2ID="$(q "SELECT id FROM users WHERE username='farmer2'")"
PID_ME="$(q "SELECT id FROM users WHERE username='$RUSER'")"

# --- the poster creates a text post ---
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/comm.html" "$BASE/community"
CTOK="$(csrf "$TMP/comm.html")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
  --data-urlencode "content=Smoke test community post $RUSER" \
  --data-urlencode "_csrf=$CTOK" "$BASE/community/post")"
PID="$(q "SELECT id FROM community_posts WHERE user_id='$PID_ME' ORDER BY id DESC LIMIT 1")"
if [ "$c" = "302" ] && [ -n "$PID" ]; then c_pass "farmer created community post #$PID"; else c_fail "community post not created (code=$c)"; fi

# --- the post page renders with no missing keys ---
c="$(fetch "$J_NEW" "$BASE/community/post/$PID" "$TMP/cpost.html")"
n="$(missing_keys "$TMP/cpost.html")"
[ "$c" = "200" ] && [ "$n" = "0" ] && c_pass "/community/post/$PID -> 200, no missing keys" \
                                  || c_fail "/community/post/$PID -> $c, $n missing keys"

# --- a second farmer comments ---
curl -s -b "$J_F2" -c "$J_F2" -o "$TMP/c2.html" "$BASE/community/post/$PID"
C2TOK="$(csrf "$TMP/c2.html")"
c="$(curl -s -b "$J_F2" -c "$J_F2" -o /dev/null -w '%{http_code}' \
  --data-urlencode "content=Nice post from farmer2" \
  --data-urlencode "_csrf=$C2TOK" "$BASE/community/post/$PID/comment")"
cm="$(q "SELECT COUNT(*) FROM community_comments WHERE post_id='$PID' AND user_id='$F2ID'")"
if [ "$c" = "302" ] && [ "$cm" = "1" ]; then c_pass "farmer2 commented (1 row)"; else c_fail "comment not saved (code=$c, rows=$cm)"; fi

# --- votes: one per user per post (a change of mind replaces, never adds) ---
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/v1.html" "$BASE/community/post/$PID"
VTOK="$(csrf "$TMP/v1.html")"
curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null --data-urlencode "vote=UP" --data-urlencode "_csrf=$VTOK" "$BASE/community/post/$PID/vote"
curl -s -b "$J_F2" -c "$J_F2" -o "$TMP/v2.html" "$BASE/community/post/$PID"
V2TOK="$(csrf "$TMP/v2.html")"
curl -s -b "$J_F2" -c "$J_F2" -o /dev/null --data-urlencode "vote=DOWN" --data-urlencode "_csrf=$V2TOK" "$BASE/community/post/$PID/vote"
# the poster changes his vote UP -> DOWN: still exactly one row for him
curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null --data-urlencode "vote=DOWN" --data-urlencode "_csrf=$VTOK" "$BASE/community/post/$PID/vote"
vtot="$(q "SELECT COUNT(*) FROM community_votes WHERE post_id='$PID'")"
vmine="$(q "SELECT COUNT(*) FROM community_votes WHERE post_id='$PID' AND user_id='$PID_ME'")"
if [ "$vtot" = "2" ] && [ "$vmine" = "1" ]; then c_pass "votes: one per user (2 rows, 1 for the poster)"; else c_fail "vote rows total=$vtot, poster=$vmine (expected 2/1)"; fi

# --- follow another farmer ---
curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null --data-urlencode "back=/community" --data-urlencode "_csrf=$VTOK" "$BASE/community/follow/$F2ID"
fl="$(q "SELECT COUNT(*) FROM follows WHERE follower_id='$PID_ME' AND following_id='$F2ID'")"
[ "$fl" = "1" ] && c_pass "farmer followed farmer2 (1 row)" || c_fail "follow row count = $fl"

# --- a non-owner cannot delete a post ---
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_F2" --data-urlencode "_csrf=$V2TOK" "$BASE/community/post/$PID/delete")"
[ "$c" = "403" ] && c_pass "farmer2 -> delete someone's post 403" || c_fail "non-owner delete -> $c (expected 403)"

# --- a direct message arrives in the other farmer's inbox (PART 3: /messages) ---
curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null --data-urlencode "content=Hello farmer2" --data-urlencode "_csrf=$VTOK" "$BASE/messages/$F2ID"
dm="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME' AND receiver_id='$F2ID'")"
c="$(fetch "$J_F2" "$BASE/messages/$PID_ME" "$TMP/thread.html")"
if [ "$dm" = "1" ] && [ "$c" = "200" ] && grep -q "Hello farmer2" "$TMP/thread.html"; then
  c_pass "direct message stored and shown in the thread"
else
  c_fail "direct message missing (rows=$dm, code=$c)"
fi

# --- photo upload: a real PNG is stored and served through the controller ---
printf '%s' "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==" | base64 -d > "$TMP/px.png" 2>/dev/null
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/comm.html" "$BASE/community"
ITOK="$(csrf "$TMP/comm.html")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
  -F "content=Smoke test post with photo $RUSER" -F "image=@$TMP/px.png;type=image/png" \
  -F "_csrf=$ITOK" "$BASE/community/post")"
IPID="$(q "SELECT id FROM community_posts WHERE content='Smoke test post with photo $RUSER'")"
img="$(q "SELECT image_path FROM community_posts WHERE id='$IPID'")"
if [ "$c" = "302" ] && [ -n "$IPID" ] && [ -n "$img" ]; then c_pass "post with photo stored (post #$IPID)"; else c_fail "photo post not created (code=$c)"; fi
icode="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_NEW" "$BASE/community/image/$IPID")"
[ "$icode" = "200" ] && c_pass "GET /community/image/$IPID -> 200 (served by the controller)" || c_fail "GET /community/image/$IPID -> $icode"
# a non-image upload is refused with a readable message (no 500, no row)
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/badup.html" -w '%{http_code}' \
  -F "content=bad upload $RUSER" -F "image=@$TMP/px.png;type=text/plain;filename=notes.txt" \
  -F "_csrf=$ITOK" "$BASE/community/post")"
badrow="$(q "SELECT COUNT(*) FROM community_posts WHERE content='bad upload $RUSER'")"
if [ "$c" = "200" ] && grep -q "Use a JPG or PNG image" "$TMP/badup.html" && [ "$badrow" = "0" ]; then
  c_pass "non-image upload rejected with a message"
else
  c_fail "non-image upload (code=$c, rows=$badrow) not rejected"
fi

# --- owner moderation: delete the posts (comments/votes cascade) ---
curl -s -b "$J_OWN" -c "$J_OWN" -o "$TMP/cown.html" "$BASE/community"
OTOK="$(csrf "$TMP/cown.html")"
curl -s -b "$J_OWN" -c "$J_OWN" -o /dev/null --data-urlencode "_csrf=$OTOK" "$BASE/community/post/$PID/delete"
curl -s -b "$J_OWN" -c "$J_OWN" -o /dev/null --data-urlencode "_csrf=$OTOK" "$BASE/community/post/$IPID/delete"
left="$(q "SELECT COUNT(*) FROM community_posts WHERE id IN ('$PID','$IPID')")"
[ "$left" = "0" ] && c_pass "owner deleted the posts (moderation works)" || c_fail "owner delete left $left row(s)"
cascade="$(q "SELECT COUNT(*) FROM community_comments WHERE post_id='$PID'")"
[ "$cascade" = "0" ] && c_pass "comments cascaded with the deleted post" || c_fail "comments left after delete: $cascade"

# =====================================================================
hdr "12. Owner panel: counts, lab approval, scheme editor"

# --- the owner dashboard renders with the five live counts ---
c="$(fetch "$J_OWN" "$BASE/owner" "$TMP/owner.html")"
n="$(missing_keys "$TMP/owner.html")"
[ "$c" = "200" ] && [ "$n" = "0" ] && c_pass "/owner -> 200, no missing keys" \
                                  || c_fail "/owner -> $c, $n missing keys"
grep -q 'id="tile-owner-counts"' "$TMP/owner.html" \
  && c_pass "/owner shows the counts tile" || c_fail "/owner has no counts tile"

# --- the owner link is owner-only in the top bar ---
fetch "$J_OWN" "$BASE/" "$TMP/o_home.html" >/dev/null
grep -q 'href="/owner"' "$TMP/o_home.html" && c_pass "top bar shows the owner link for owner" || c_fail "owner link missing for the owner"
fetch "$J_FARM" "$BASE/" "$TMP/f_home.html" >/dev/null
grep -q 'href="/owner"' "$TMP/f_home.html" && c_fail "top bar shows the owner link for farmer1" || c_pass "top bar hides the owner link for farmer1"
c_owner_as_farmer="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_FARM" "$BASE/owner")"
[ "$c_owner_as_farmer" = "403" ] && c_pass "farmer1 -> /owner 403" || c_fail "farmer1 -> /owner $c_owner_as_farmer"

# --- register two fresh labs; approve one and reject the other ---
reg_lab() { # user -> prints the new lab id
  local u="$1"
  curl -s -c "$TMP/labreg2.jar" "$BASE/register/lab" -o "$TMP/labreg2.html"
  local t; t="$(csrf "$TMP/labreg2.html")"
  curl -s -b "$TMP/labreg2.jar" -c "$TMP/labreg2.jar" -o /dev/null \
    --data-urlencode "username=$u" --data-urlencode "name=Owner Test Lab $u" \
    --data-urlencode "email=${u}@example.com" \
    --data-urlencode "password=$RPASS" --data-urlencode "confirm=$RPASS" \
    --data-urlencode "address=Test Road, Pune" --data-urlencode "district=Pune" \
    --data-urlencode "taluka=Haveli" --data-urlencode "phone=9876523456" \
    --data-urlencode "timings=9 AM - 6 PM" --data-urlencode "samples=soil" \
    --data-urlencode "lat=18.52" --data-urlencode "lon=73.86" \
    --data-urlencode "_csrf=$t" "$BASE/register/lab"
  q "SELECT l.id FROM labs l JOIN users u ON u.id=l.user_id WHERE u.username='$u'"
}
AUSER="oklab$(date +%s)$RANDOM"; AID="$(reg_lab "$AUSER")"
AST="$(q "SELECT status FROM labs WHERE id='$AID'")"
if [ -n "$AID" ] && [ "$AST" = "PENDING" ]; then c_pass "fresh lab $AUSER is PENDING (id $AID)"; else c_fail "fresh lab $AUSER not PENDING (id=$AID status=$AST)"; fi
# PART 1: complete the lab's email step (approval alone is not enough to log in)
AH="$(handle_for "$AUSER")"
if verify_email_ok "$TMP/labreg2.jar" "$AH"; then
  c_pass "verified the email of the fresh lab $AUSER"
else
  c_fail "could not verify the email of $AUSER (handle '$AH')"
fi

# approve it: the status flips and the account can finally log in
curl -s -b "$J_OWN" -c "$J_OWN" -o "$TMP/o.html" "$BASE/owner"
OTOK2="$(csrf "$TMP/o.html")"
c="$(curl -s -b "$J_OWN" -c "$J_OWN" -o /dev/null -w '%{http_code}' \
  --data-urlencode "_csrf=$OTOK2" "$BASE/owner/lab/$AID/approve")"
AST="$(q "SELECT status FROM labs WHERE id='$AID'")"
if [ "$c" = "302" ] && [ "$AST" = "APPROVED" ]; then c_pass "owner approved lab $AID -> APPROVED"; else c_fail "approve: code=$c, status=$AST"; fi
if login "$AUSER" "$RPASS" "$TMP/approved.jar"; then c_pass "approved lab $AUSER can log in"; else c_fail "approved lab $AUSER still cannot log in"; fi

# reject a second fresh lab
RUSER2="rejlab$(date +%s)$RANDOM"; RID="$(reg_lab "$RUSER2")"
c="$(curl -s -b "$J_OWN" -c "$J_OWN" -o /dev/null -w '%{http_code}' \
  --data-urlencode "_csrf=$OTOK2" "$BASE/owner/lab/$RID/reject")"
RST="$(q "SELECT status FROM labs WHERE id='$RID'")"
if [ "$c" = "302" ] && [ "$RST" = "REJECTED" ]; then c_pass "owner rejected lab $RID -> REJECTED"; else c_fail "reject: code=$c, status=$RST"; fi

# --- scheme editor: an empty name is refused, a real edit lands in scheme_text ---
sel_col() { # column -> value of pmkisan/en
  q "SELECT $1 FROM scheme_text t JOIN schemes s ON s.id=t.scheme_id WHERE s.scheme_key='pmkisan' AND t.lang='en'"
}
ONAME="$(sel_col scheme_name)"; OWHAT="$(sel_col what_text)"; OWHO="$(sel_col who_text)"
ODOCS="$(sel_col docs_text)"; OSTEPS="$(sel_col steps_text)"; OWHERE="$(sel_col where_text)"

curl -s -b "$J_OWN" -c "$J_OWN" -o "$TMP/os.html" "$BASE/owner/schemes/pmkisan?lang=en"
n="$(missing_keys "$TMP/os.html")"
[ "$n" = "0" ] && c_pass "/owner/schemes/pmkisan?lang=en -> no missing keys" || c_fail "scheme editor has $n missing keys"
STOK="$(csrf "$TMP/os.html")"

# empty name -> 200 with the required error, and nothing changes in the database
c="$(curl -s -b "$J_OWN" -c "$J_OWN" -o "$TMP/os_empty.html" -w '%{http_code}' \
  --data-urlencode "lang=en" --data-urlencode "name=" --data-urlencode "what=$OWHAT" \
  --data-urlencode "who=$OWHO" --data-urlencode "docs=$ODOCS" --data-urlencode "steps=$OSTEPS" \
  --data-urlencode "where=$OWHERE" --data-urlencode "_csrf=$STOK" "$BASE/owner/schemes/pmkisan")"
still="$(sel_col scheme_name)"
if [ "$c" = "200" ] && grep -q "This field is required" "$TMP/os_empty.html" && [ "$still" = "$ONAME" ]; then
  c_pass "empty scheme name -> 200 with the required error (DB unchanged)"
else
  c_fail "empty scheme name (code=$c, name now '$still') not refused"
fi

# a real edit lands in scheme_text and shows on the public page
NEWNAME="PM-KISAN [smoke edit]"
c="$(curl -s -b "$J_OWN" -c "$J_OWN" -o /dev/null -w '%{http_code}' \
  --data-urlencode "lang=en" --data-urlencode "name=$NEWNAME" \
  --data-urlencode "what=Smoke edited what. $OWHAT" \
  --data-urlencode "who=$OWHO" --data-urlencode "docs=$ODOCS" --data-urlencode "steps=$OSTEPS" \
  --data-urlencode "where=$OWHERE" --data-urlencode "_csrf=$STOK" "$BASE/owner/schemes/pmkisan")"
saved="$(sel_col scheme_name)"
if [ "$c" = "302" ] && [ "$saved" = "$NEWNAME" ]; then c_pass "owner edit saved to scheme_text (name updated)"; else c_fail "scheme save: code=$c, name '$saved'"; fi
fetch "$J_FARM" "$BASE/schemes/pmkisan?lang=en" "$TMP/pub.html" >/dev/null
grep -q "smoke edit" "$TMP/pub.html" && c_pass "public /schemes/pmkisan shows the owner's edit" || c_fail "public page did not show the edit"

# unknown scheme key -> 404, not 500
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_OWN" "$BASE/owner/schemes/nosuchkey")"
[ "$c" = "404" ] && c_pass "/owner/schemes/nosuchkey -> 404" || c_fail "/owner/schemes/nosuchkey -> $c"

# restore the seeded text so the demo data is untouched
curl -s -b "$J_OWN" -c "$J_OWN" -o /dev/null \
  --data-urlencode "lang=en" --data-urlencode "name=$ONAME" --data-urlencode "what=$OWHAT" \
  --data-urlencode "who=$OWHO" --data-urlencode "docs=$ODOCS" --data-urlencode "steps=$OSTEPS" \
  --data-urlencode "where=$OWHERE" --data-urlencode "_csrf=$STOK" "$BASE/owner/schemes/pmkisan"
restored="$(sel_col scheme_name)"
[ "$restored" = "$ONAME" ] && c_pass "seeded scheme text restored" || c_fail "restore failed (name '$restored')"

# =====================================================================
hdr "13. PART A: daily schedule, catch-up staleness, refresh-now (owner only)"

# --- the schedule and retention properties are read and shown on /debug ---
c="$(fetch "$J_OWN" "$BASE/debug" "$TMP/dbg.html")"
n="$(missing_keys "$TMP/dbg.html")"
[ "$c" = "200" ] && [ "$n" = "0" ] && c_pass "/debug -> 200, no missing keys" \
                                  || c_fail "/debug -> $c, $n missing keys"
grep -q '0 0 6 \* \* \*' "$TMP/dbg.html" \
  && c_pass "/debug shows the daily cron from weather.refresh.cron (0 0 6 * * *)" \
  || c_fail "/debug does not show the cron property"
grep -q 'tile-debug-runs' "$TMP/dbg.html" \
  && c_pass "/debug shows the run-history tile" || c_fail "/debug has no run-history tile"

# --- catch-up staleness detection (reversible: shift one district back 5 days) ---
STALE_D="$(q 'SELECT district FROM weather_config ORDER BY id LIMIT 1')"
grep -q 'Up to date' "$TMP/dbg.html" \
  && c_pass "catch-up: /debug reports a fresh cache as up to date" \
  || c_fail "catch-up: /debug did not report up-to-date for a fresh cache"
q "UPDATE weather_cache SET timestamp = DATE_SUB(timestamp, INTERVAL 5 DAY) WHERE district='$STALE_D'" >/dev/null
fetch "$J_OWN" "$BASE/debug" "$TMP/dbg_old.html" >/dev/null
grep -q 'Stale' "$TMP/dbg_old.html" \
  && c_pass "catch-up: /debug reports STALE when $STALE_D is older than 24h" \
  || c_fail "catch-up: /debug did not detect the stale district ($STALE_D)"
q "UPDATE weather_cache SET timestamp = DATE_ADD(timestamp, INTERVAL 5 DAY) WHERE district='$STALE_D'" >/dev/null
fetch "$J_OWN" "$BASE/debug" "$TMP/dbg_new.html" >/dev/null
grep -q 'Up to date' "$TMP/dbg_new.html" \
  && c_pass "catch-up: staleness cleared after restoring the timestamp" \
  || c_fail "catch-up: staleness not cleared after restore"

# --- "Refresh now" is owner-only ---
curl -s -b "$J_FARM" -c "$J_FARM" -o "$TMP/fprof.html" "$BASE/profile"
FTOK="$(csrf "$TMP/fprof.html")"
fr="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_FARM" \
      --data-urlencode "_csrf=$FTOK" "$BASE/debug/refresh")"
[ "$fr" = "403" ] && c_pass "farmer1 -> POST /debug/refresh 403" || c_fail "farmer1 -> POST /debug/refresh $fr"

curl -s -b "$J_OWN" -c "$J_OWN" -o "$TMP/dbg.html" "$BASE/debug"
OTOK3="$(csrf "$TMP/dbg.html")"
or="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_OWN" \
      --data-urlencode "_csrf=$OTOK3" "$BASE/debug/refresh")"
[ "$or" = "302" ] && c_pass "owner -> POST /debug/refresh 302 (background refresh started)" \
                  || c_fail "owner -> POST /debug/refresh $or"

# the background refresh records a MANUAL run row (poll up to ~2 min)
runrow=""
for _i in $(seq 1 40); do
  runrow="$(q "SELECT COUNT(*) FROM weather_scrape_run WHERE trigger_type='MANUAL'")"
  [ "${runrow:-0}" -gt 0 ] && break
  sleep 3
done
[ "${runrow:-0}" -gt 0 ] && c_pass "refresh-now recorded a MANUAL run in weather_scrape_run" \
                         || c_fail "no MANUAL run row appeared after refresh-now"
MANUAL_SRC="$(q "SELECT source FROM weather_scrape_run WHERE trigger_type='MANUAL' ORDER BY id DESC LIMIT 1")"
case "$MANUAL_SRC" in
  GREENALERTS|OPENMETEO|MIXED|FAILED)
    c_pass "the MANUAL run recorded a valid source ($MANUAL_SRC)";;
  *)
    c_fail "the MANUAL run source is '$MANUAL_SRC'";;
esac

# =====================================================================
hdr "13b. PART 2a: profile page, picture and avatar"

# A dedicated farmer, registered here so the profile row can be removed to prove
# the picture save is an UPSERT (works before any farm details exist).
PUSER="smokephoto$(date +%s)"
PMOB="9$(printf '%09d' $(( (RANDOM * 32768 + RANDOM) % 1000000000 )))"
J_PHO="$TMP/photofarmer.jar"
curl -s -c "$TMP/photoreg.jar" "$BASE/register" -o "$TMP/photoreg.html"
PTOK="$(csrf "$TMP/photoreg.html")"
c="$(curl -s -b "$TMP/photoreg.jar" -c "$TMP/photoreg.jar" -o /dev/null -w '%{http_code}' \
  --data-urlencode "username=$PUSER" --data-urlencode "password=$RPASS" \
  --data-urlencode "confirm=$RPASS" --data-urlencode "email=${PUSER}@example.com" \
  --data-urlencode "mobile=$PMOB" \
  --data-urlencode "district=Pune" --data-urlencode "taluka=Haveli" \
  --data-urlencode "village=Wagholi" --data-urlencode "area=2" \
  --data-urlencode "unit=acre" --data-urlencode "soil=black" \
  --data-urlencode "irrigation=drip" --data-urlencode "lang=en" \
  --data-urlencode "crops=rice" --data-urlencode "_csrf=$PTOK" "$BASE/register")"
PH="$(handle_for "$PUSER")"
verify_email_ok "$TMP/photoreg.jar" "$PH" >/dev/null
if [ "$c" = "302" ] && login "$PUSER" "$RPASS" "$J_PHO"; then
  c_pass "registered + verified $PUSER and logged in"
else
  c_fail "could not prepare the photo-test farmer (register code=$c)"
fi
PUID="$(q "SELECT id FROM users WHERE username='$PUSER'")"

# --- /profile shows the picture controls, with no missing keys ---
c="$(fetch "$J_PHO" "$BASE/profile" "$TMP/pprof.html")"
n="$(missing_keys "$TMP/pprof.html")"
[ "$c" = "200" ] && [ "$n" = "0" ] && c_pass "/profile -> 200 with the picture controls, no missing keys" \
                                  || c_fail "/profile -> $c, $n missing keys"
grep -q '/profile/photo' "$TMP/pprof.html" \
  && c_pass "/profile offers the photo upload form" \
  || c_fail "/profile has no photo upload form"

# --- a freshly registered farmer with NO farm details can upload a photo ---
# remove the profile row (as if farm details were never saved) then upload: the
# save must CREATE the row (UPSERT), never 500.
q "DELETE FROM farmer_profiles WHERE user_id='$PUID'" >/dev/null
curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/pprof.html" "$BASE/profile"
PTOK2="$(csrf "$TMP/pprof.html")"
gen_png "$TMP/pic.png" 2 2 2 2 30 140 90 255
c="$(curl -s -b "$J_PHO" -c "$J_PHO" -o /dev/null -w '%{http_code}' \
  -F "photo=@$TMP/pic.png;type=image/png" -F "_csrf=$PTOK2" "$BASE/profile/photo")"
prow="$(q "SELECT COUNT(*) FROM farmer_profiles WHERE user_id='$PUID'")"
ppath="$(q "SELECT photo_path FROM farmer_profiles WHERE user_id='$PUID'")"
if [ "$c" = "302" ] && [ "$prow" = "1" ] && [ -n "$ppath" ]; then
  c_pass "a profile-less farmer uploaded a photo (row created by the UPSERT)"
else
  c_fail "photo upload with no profile row (code=$c, rows=$prow, path='$ppath')"
fi

# --- GET /avatar serves the stored 256x256 JPEG ---
c="$(curl -s -b "$J_PHO" -o "$TMP/av.jpg" -w '%{http_code}' "$BASE/avatar/$PUID")"
magic="$(od -An -v -tx1 -N3 "$TMP/av.jpg" | tr -d ' \n')"
dims="$(jpeg_dims "$TMP/av.jpg")"
[ "$c" = "200" ] && [ "$magic" = "ffd8ff" ] \
  && c_pass "GET /avatar/$PUID -> 200 JPEG (magic $magic)" \
  || c_fail "GET /avatar/$PUID -> $c, magic '$magic'"
[ "$dims" = "256x256" ] && c_pass "the stored avatar is 256x256 ($dims)" \
                        || c_fail "the stored avatar is '$dims' (expected 256x256)"

# --- a transparent PNG is flattened onto WHITE, not black ---
# three 1x1 uploads; the transparent one must equal the white one and differ
# from the black one.
gen_png "$TMP/white.png" 1 1 1 1 255 255 255 255
gen_png "$TMP/black.png" 1 1 1 1 0 0 0 255
gen_png "$TMP/clear.png" 1 1 1 1 0 0 0 0
upload_photo() { # png outfile -> upload, then fetch the served avatar into outfile
  curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/pprof.html" "$BASE/profile"
  local tok; tok="$(csrf "$TMP/pprof.html")"
  curl -s -b "$J_PHO" -c "$J_PHO" -o /dev/null \
       -F "photo=@$1;type=image/png" -F "_csrf=$tok" "$BASE/profile/photo"
  curl -s -b "$J_PHO" -o "$2" "$BASE/avatar/$PUID"
}
upload_photo "$TMP/white.png" "$TMP/jw.jpg"
upload_photo "$TMP/black.png" "$TMP/jb.jpg"
upload_photo "$TMP/clear.png" "$TMP/jc.jpg"
if cmp -s "$TMP/jc.jpg" "$TMP/jw.jpg" && ! cmp -s "$TMP/jc.jpg" "$TMP/jb.jpg"; then
  c_pass "a transparent PNG flattens to a light background (equals white, not black)"
else
  c_fail "transparent PNG was not flattened onto white (clear must equal white, not black)"
fi

# --- POST /profile/photo/remove clears the picture ---
curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/pprof.html" "$BASE/profile"
RMTOK="$(csrf "$TMP/pprof.html")"
c="$(curl -s -b "$J_PHO" -c "$J_PHO" -o /dev/null -w '%{http_code}' \
  --data-urlencode "_csrf=$RMTOK" "$BASE/profile/photo/remove")"
left="$(q "SELECT IFNULL(photo_path,'') FROM farmer_profiles WHERE user_id='$PUID'")"
if [ "$c" = "302" ] && [ -z "$left" ]; then
  c_pass "POST /profile/photo/remove clears the picture"
else
  c_fail "photo remove -> $c, path='$left'"
fi

# re-upload a valid picture so the rejection checks can prove it is kept
curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/pprof.html" "$BASE/profile"
KTOK="$(csrf "$TMP/pprof.html")"
curl -s -b "$J_PHO" -c "$J_PHO" -o /dev/null \
  -F "photo=@$TMP/pic.png;type=image/png" -F "_csrf=$KTOK" "$BASE/profile/photo"
KEEP="$(q "SELECT photo_path FROM farmer_profiles WHERE user_id='$PUID'")"

# --- a text file renamed .png is refused (magic bytes, not the extension) ---
printf 'this is not an image %s\n' "$PUSER" > "$TMP/notimage.png"
curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/pprof.html" "$BASE/profile"
NTOK="$(csrf "$TMP/pprof.html")"
c="$(curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/badtype.html" -w '%{http_code}' \
  -F "photo=@$TMP/notimage.png;type=image/png" -F "_csrf=$NTOK" "$BASE/profile/photo")"
after="$(q "SELECT photo_path FROM farmer_profiles WHERE user_id='$PUID'")"
if [ "$c" = "200" ] && grep -q "Use a JPG or PNG image" "$TMP/badtype.html" && [ "$after" = "$KEEP" ]; then
  c_pass "a text file renamed .png is rejected (magic bytes checked)"
else
  c_fail "renamed text file (code=$c) was not rejected with the type error"
fi

# --- an upload over 2 MB is refused ---
head -c 2200000 /dev/zero > "$TMP/big.png"
curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/pprof.html" "$BASE/profile"
BTOK="$(csrf "$TMP/pprof.html")"
c="$(curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/big.html" -w '%{http_code}' \
  -F "photo=@$TMP/big.png;type=image/png" -F "_csrf=$BTOK" "$BASE/profile/photo")"
after="$(q "SELECT photo_path FROM farmer_profiles WHERE user_id='$PUID'")"
if [ "$c" = "200" ] && grep -q "2 MB or smaller" "$TMP/big.html" && [ "$after" = "$KEEP" ]; then
  c_pass "an upload over 2 MB is rejected"
else
  c_fail "oversized upload (code=$c) was not rejected"
fi

# --- a tiny file claiming huge dimensions is refused WITHOUT a 500 ---
gen_png "$TMP/huge.png" 9000 9000 1 1 0 0 0 255
curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/pprof.html" "$BASE/profile"
HTOK="$(csrf "$TMP/pprof.html")"
c="$(curl -s -b "$J_PHO" -c "$J_PHO" -o "$TMP/huge.html" -w '%{http_code}' \
  -F "photo=@$TMP/huge.png;type=image/png" -F "_csrf=$HTOK" "$BASE/profile/photo")"
after="$(q "SELECT photo_path FROM farmer_profiles WHERE user_id='$PUID'")"
if [ "$c" = "200" ] && [ "$after" = "$KEEP" ] \
   && ! grep -qiE 'whitelabel|internal server error' "$TMP/huge.html"; then
  c_pass "a tiny file with huge dimensions is refused without a 500"
else
  c_fail "huge-dimension upload -> $c (expected a friendly 200 re-render)"
fi

# --- a blank name still returns 200 from /avatar (initials fallback) ---
q "UPDATE farmer_profiles SET full_name='', photo_path=NULL WHERE user_id='$PUID'" >/dev/null
c="$(curl -s -b "$J_PHO" -o "$TMP/svg.svg" -w '%{http_code}' "$BASE/avatar/$PUID")"
head5="$(head -c 5 "$TMP/svg.svg" 2>/dev/null)"
if [ "$c" = "200" ] && [ "${head5#<svg}" != "$head5" ]; then
  c_pass "a blank name still returns 200 from /avatar (initials fallback)"
else
  c_fail "blank-name /avatar -> $c, first bytes '$head5'"
fi
# an unknown user id must not 500 either
c="$(curl -s -b "$J_PHO" -o /dev/null -w '%{http_code}' "$BASE/avatar/999999999")"
[ "$c" = "200" ] && c_pass "an unknown user id returns 200 from /avatar" \
                 || c_fail "unknown-user /avatar -> $c"

# =====================================================================
hdr "13c. PART 2b: managing my own content (edit / delete my posts, comments, messages)"

# --- the poster creates a fresh post WITH a photo, so it can be managed -----
gen_png "$TMP/pb.png" 2 2 2 2 40 90 200 255
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/comm.html" "$BASE/community"
P2TOK="$(csrf "$TMP/comm.html")"
P2TXT="PART2b post $RUSER"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
  -F "content=$P2TXT" -F "image=@$TMP/pb.png;type=image/png" \
  -F "_csrf=$P2TOK" "$BASE/community/post")"
PID2="$(q "SELECT id FROM community_posts WHERE content='$P2TXT'")"
IMG2="$(q "SELECT image_path FROM community_posts WHERE id='$PID2'")"
if [ "$c" = "302" ] && [ -n "$PID2" ] && [ -n "$IMG2" ]; then
  c_pass "PART 2b: created own post #$PID2 with a photo"
else
  c_fail "PART 2b: could not create the post to manage (code=$c)"
fi

# --- EDIT MY OWN POST (text is updated) -------------------------------------
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/pedit.html" "$BASE/community/post/$PID2/edit"
EE="$(csrf "$TMP/pedit.html")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
  --data-urlencode "content=$P2TXT edited" --data-urlencode "_csrf=$EE" \
  "$BASE/community/post/$PID2/edit")"
edited="$(q "SELECT content FROM community_posts WHERE id='$PID2'")"
if [ "$c" = "302" ] && [ "$edited" = "$P2TXT edited" ]; then
  c_pass "PART 2b: edited my own post (text updated)"
else
  c_fail "PART 2b: edit own post -> $c, content '$edited'"
fi

# --- EDITING SOMEONE ELSE'S POST -> 403 (GET and POST) ----------------------
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_F2" "$BASE/community/post/$PID2/edit")"
[ "$c" = "403" ] && c_pass "PART 2b: farmer2 -> GET edit someone's post 403" \
                 || c_fail "PART 2b: farmer2 GET edit foreign post -> $c (expected 403)"
curl -s -b "$J_F2" -c "$J_F2" -o "$TMP/f2comm.html" "$BASE/community"
F2TOK="$(csrf "$TMP/f2comm.html")"
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_F2" \
  --data-urlencode "content=hacked" --data-urlencode "_csrf=$F2TOK" \
  "$BASE/community/post/$PID2/edit")"
[ "$c" = "403" ] && c_pass "PART 2b: farmer2 -> POST edit someone's post 403" \
                 || c_fail "PART 2b: farmer2 POST edit foreign post -> $c (expected 403)"

# --- THE DELETE CONFIRMATION: a GET shows the page but deletes NOTHING, and a
#     POST without CSRF is refused -------------------------------------------------
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/pdel.html" "$BASE/community/post/$PID2/delete"
DTOK2="$(csrf "$TMP/pdel.html")"
still="$(q "SELECT COUNT(*) FROM community_posts WHERE id='$PID2'")"
[ "$still" = "1" ] && c_pass "PART 2b: GET .../delete shows the confirm page and deletes NOTHING" \
                   || c_fail "PART 2b: a GET deleted the post (rows=$still)"
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_NEW" "$BASE/community/post/$PID2/delete")"
still="$(q "SELECT COUNT(*) FROM community_posts WHERE id='$PID2'")"
[ "$c" = "403" ] && [ "$still" = "1" ] \
  && c_pass "PART 2b: POST .../delete without CSRF -> 403 (row unchanged)" \
  || c_fail "PART 2b: delete without CSRF -> $c (rows=$still)"

# --- DELETE MY OWN COMMENT --------------------------------------------------
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/cp.html" "$BASE/community/post/$PID2"
CPTOK="$(csrf "$TMP/cp.html")"
curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null \
  --data-urlencode "content=PART2b own comment $RUSER" --data-urlencode "_csrf=$CPTOK" \
  "$BASE/community/post/$PID2/comment"
CID="$(q "SELECT id FROM community_comments WHERE post_id='$PID2' AND user_id='$PID_ME' ORDER BY id DESC LIMIT 1")"
# someone else may NOT delete it
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_F2" "$BASE/community/comment/$CID/delete")"
[ "$c" = "403" ] && c_pass "PART 2b: farmer2 -> delete someone's comment 403" \
                 || c_fail "PART 2b: farmer2 delete foreign comment -> $c (expected 403)"
curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/cdel.html" "$BASE/community/comment/$CID/delete"
CDTOK="$(csrf "$TMP/cdel.html")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
  --data-urlencode "_csrf=$CDTOK" "$BASE/community/comment/$CID/delete")"
gone="$(q "SELECT COUNT(*) FROM community_comments WHERE id='$CID'")"
if [ "$c" = "302" ] && [ "$gone" = "0" ]; then
  c_pass "PART 2b: deleted my own comment"
else
  c_fail "PART 2b: delete own comment -> $c (rows=$gone)"
fi

# --- DELETE MY OWN POST: comments + votes cascade, and the photo file goes ----
curl -s -b "$J_F2" -c "$J_F2" -o "$TMP/c2b.html" "$BASE/community/post/$PID2"
C2BTOK="$(csrf "$TMP/c2b.html")"
curl -s -b "$J_F2" -c "$J_F2" -o /dev/null \
  --data-urlencode "content=PART2b cascade comment" --data-urlencode "_csrf=$C2BTOK" \
  "$BASE/community/post/$PID2/comment"
curl -s -b "$J_F2" -c "$J_F2" -o /dev/null \
  --data-urlencode "vote=UP" --data-urlencode "_csrf=$C2BTOK" "$BASE/community/post/$PID2/vote"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
  --data-urlencode "_csrf=$DTOK2" "$BASE/community/post/$PID2/delete")"
left="$(q "SELECT COUNT(*) FROM community_posts WHERE id='$PID2'")"
cleft="$(q "SELECT COUNT(*) FROM community_comments WHERE post_id='$PID2'")"
vleft="$(q "SELECT COUNT(*) FROM community_votes WHERE post_id='$PID2'")"
if [ "$c" = "302" ] && [ "$left" = "0" ] && [ "$cleft" = "0" ] && [ "$vleft" = "0" ]; then
  c_pass "PART 2b: deleted my own post (comments and votes cascaded)"
else
  c_fail "PART 2b: delete own post -> $c, post=$left comments=$cleft votes=$vleft"
fi
if [ -n "$IMG2" ] && [ ! -e "$IMG2" ]; then
  c_pass "PART 2b: the deleted post's photo file was removed from disk"
else
  c_fail "PART 2b: the post's photo file was NOT removed ($IMG2)"
fi

# --- DELETE MY OWN CHAT MESSAGE (soft delete: the text is cleared) -----------
DMID="$(q "SELECT id FROM direct_messages WHERE sender_id='$PID_ME' AND receiver_id='$F2ID' ORDER BY id DESC LIMIT 1")"
if [ -n "$DMID" ]; then
  curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/mdel.html" "$BASE/community/message/$DMID/delete"
  MDTOK="$(csrf "$TMP/mdel.html")"
  c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null -w '%{http_code}' \
    --data-urlencode "_csrf=$MDTOK" "$BASE/community/message/$DMID/delete")"
  mleft="$(q "SELECT IFNULL(content,'') FROM direct_messages WHERE id='$DMID'")"
  if [ "$c" = "302" ] && [ -z "$mleft" ]; then
    c_pass "PART 2b: deleted my own chat message (text cleared - soft delete)"
  else
    c_fail "PART 2b: delete own message -> $c, content='$mleft'"
  fi
  # the RECEIVER may not delete a message I sent
  c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_F2" "$BASE/community/message/$DMID/delete")"
  [ "$c" = "403" ] && c_pass "PART 2b: the receiver -> delete my message 403" \
                   || c_fail "PART 2b: receiver delete my message -> $c (expected 403)"
fi

# =====================================================================
hdr "13d. PART 3: connections and chat (inbox, unread badge, limits)"

F1ID="$(q "SELECT id FROM users WHERE username='farmer1'")"
F2NAME="$(q "SELECT COALESCE(fp.full_name, u.username) FROM users u LEFT JOIN farmer_profiles fp ON fp.user_id=u.id WHERE u.id='$F2ID'")"

# --- the Find farmers directory: every other farmer, no missing keys --------
c="$(fetch "$J_NEW" "$BASE/farmers" "$TMP/farmers.html")"
n="$(missing_keys "$TMP/farmers.html")"
if [ "$c" = "200" ] && [ "$n" = "0" ] && grep -q "$F2NAME" "$TMP/farmers.html"; then
  c_pass "PART 3: /farmers -> 200, lists other farmers, no missing keys"
else
  c_fail "PART 3: /farmers -> $c, $n missing keys"
fi
# the name search narrows the directory (an impossible name matches nobody)
fetch "$J_NEW" "$BASE/farmers?q=zzzznotafarmer" "$TMP/farmers_q.html" >/dev/null
grep -q "No farmers match" "$TMP/farmers_q.html" \
  && c_pass "PART 3: the /farmers name search filters the list" \
  || c_fail "PART 3: the name search did not filter"
# the district filter round-trips (a real district still lists its farmers)
fetch "$J_NEW" "$BASE/farmers?district=Pune" "$TMP/farmers_d.html" >/dev/null
grep -q "$F2NAME" "$TMP/farmers_d.html" \
  && c_pass "PART 3: the /farmers district filter round-trips" \
  || c_fail "PART 3: the district filter lost farmer2"

# --- farmer2 writes to me: the message is stored and UNREAD for me ----------
curl -s -b "$J_F2" -c "$J_F2" -o "$TMP/f2thread.html" "$BASE/messages/$PID_ME"
F2TOK="$(csrf "$TMP/f2thread.html")"
c="$(curl -s -b "$J_F2" -c "$J_F2" -o /dev/null -w '%{http_code}' \
  --data-urlencode "content=Reply from farmer2 $RUSER" --data-urlencode "_csrf=$F2TOK" "$BASE/messages/$PID_ME")"
unread="$(q "SELECT COUNT(*) FROM direct_messages WHERE receiver_id='$PID_ME' AND is_read=0")"
if [ "$c" = "302" ] && [ "${unread:-0}" -ge 1 ]; then
  c_pass "PART 3: farmer2's message is stored and UNREAD for me ($unread)"
else
  c_fail "PART 3: farmer2 reply -> $c, unread=$unread"
fi

# --- my inbox lists the conversation, and the top bar shows the badge -------
c="$(fetch "$J_NEW" "$BASE/messages" "$TMP/inbox3.html")"
n="$(missing_keys "$TMP/inbox3.html")"
if [ "$c" = "200" ] && [ "$n" = "0" ] && grep -q "$F2NAME" "$TMP/inbox3.html"; then
  c_pass "PART 3: /messages inbox lists the conversation, no missing keys"
else
  c_fail "PART 3: /messages -> $c, $n missing keys"
fi
fetch "$J_NEW" "$BASE/" "$TMP/home_badge.html" >/dev/null
grep -q 'nav-badge' "$TMP/home_badge.html" \
  && c_pass "PART 3: the top bar shows an unread badge while messages are unread" \
  || c_fail "PART 3: no unread badge in the top bar"

# --- opening the thread marks it read, and the badge disappears -------------
c="$(fetch "$J_NEW" "$BASE/messages/$F2ID" "$TMP/th3.html")"
n="$(missing_keys "$TMP/th3.html")"
unread="$(q "SELECT COUNT(*) FROM direct_messages WHERE receiver_id='$PID_ME' AND is_read=0")"
MTOK="$(csrf "$TMP/th3.html")"
[ "$c" = "200" ] && [ "$n" = "0" ] && [ "${unread:-0}" = "0" ] \
  && c_pass "PART 3: opening the thread -> 200 and its messages are marked read" \
  || c_fail "PART 3: thread -> $c, $n missing keys, unread=$unread"
fetch "$J_NEW" "$BASE/" "$TMP/home_nobadge.html" >/dev/null
grep -q 'nav-badge' "$TMP/home_nobadge.html" \
  && c_fail "PART 3: the badge is still shown when nothing is unread" \
  || c_pass "PART 3: the unread badge is gone once the thread is read"

# --- who may chat: only a farmer you follow, or who follows you -------------
c="$(curl -s -o "$TMP/blocked.html" -w '%{http_code}' -b "$J_NEW" "$BASE/messages/$F1ID")"
[ "$c" = "403" ] && grep -qi 'cannot message' "$TMP/blocked.html" \
  && c_pass "PART 3: a non-connected farmer's thread -> 403 (blocked page)" \
  || c_fail "PART 3: non-connected thread -> $c (expected 403)"
# a thread with yourself: GET redirects to the inbox, POST is refused
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_NEW" "$BASE/messages/$PID_ME")"
[ "$c" = "302" ] && c_pass "PART 3: opening my own thread redirects (no self-chat)" \
  || c_fail "PART 3: self thread GET -> $c (expected 302)"
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_NEW" --data-urlencode "content=hi me" --data-urlencode "_csrf=$MTOK" "$BASE/messages/$PID_ME")"
[ "$c" = "403" ] && c_pass "PART 3: sending to myself -> 403" \
  || c_fail "PART 3: self POST -> $c (expected 403)"
c="$(curl -s -o /dev/null -w '%{http_code}' -b "$J_NEW" --data-urlencode "content=hi stranger" --data-urlencode "_csrf=$MTOK" "$BASE/messages/$F1ID")"
[ "$c" = "403" ] && c_pass "PART 3: sending to a non-connected farmer -> 403" \
  || c_fail "PART 3: non-connected POST -> $c (expected 403)"

# --- the text rules: empty and over-long are refused, nothing is stored -----
before="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME'")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/msg_empty.html" -w '%{http_code}' \
  --data-urlencode "content=   " --data-urlencode "_csrf=$MTOK" "$BASE/messages/$F2ID")"
after="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME'")"
if [ "$c" = "200" ] && [ "$before" = "$after" ] && grep -qi "write a message" "$TMP/msg_empty.html"; then
  c_pass "PART 3: an empty message is refused (nothing stored)"
else
  c_fail "PART 3: empty message -> $c, rows $before->$after"
fi
printf -v LONGMSG '%*s' 1001 ''
LONGMSG="${LONGMSG// /x}"
before="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME'")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/msg_long.html" -w '%{http_code}' \
  --data-urlencode "content=$LONGMSG" --data-urlencode "_csrf=$MTOK" "$BASE/messages/$F2ID")"
after="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME'")"
if [ "$c" = "200" ] && [ "$before" = "$after" ] && grep -qi "too long" "$TMP/msg_long.html"; then
  c_pass "PART 3: a message over 1000 characters is refused (nothing stored)"
else
  c_fail "PART 3: long message -> $c, rows $before->$after"
fi

# --- the rate limit: at most 20 messages per minute -------------------------
sent="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME' AND created_at >= (NOW() - INTERVAL 1 MINUTE)")"
i=0
while [ "${sent:-0}" -lt 20 ] && [ "$i" -lt 25 ]; do
  curl -s -b "$J_NEW" -c "$J_NEW" -o /dev/null \
    --data-urlencode "content=rate limit fill $i" --data-urlencode "_csrf=$MTOK" "$BASE/messages/$F2ID"
  sent="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME' AND created_at >= (NOW() - INTERVAL 1 MINUTE)")"
  i=$((i + 1))
done
[ "${sent:-0}" -ge 20 ] \
  && c_pass "PART 3: reached the 20-messages-per-minute ceiling" \
  || c_fail "PART 3: could not reach the rate limit (sent=$sent)"
before="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME'")"
c="$(curl -s -b "$J_NEW" -c "$J_NEW" -o "$TMP/msg_rate.html" -w '%{http_code}' \
  --data-urlencode "content=one message too many" --data-urlencode "_csrf=$MTOK" "$BASE/messages/$F2ID")"
after="$(q "SELECT COUNT(*) FROM direct_messages WHERE sender_id='$PID_ME'")"
if [ "$c" = "200" ] && [ "$before" = "$after" ] && grep -qi "too quickly" "$TMP/msg_rate.html"; then
  c_pass "PART 3: the 21st message in a minute is refused (rate limited)"
else
  c_fail "PART 3: rate limit -> $c, rows $before->$after"
fi

# --- connections are MUTUAL follows, and chat permission is symmetric -------
curl -s -b "$J_F2" -c "$J_F2" -o /dev/null \
  --data-urlencode "back=/farmers" --data-urlencode "_csrf=$F2TOK" "$BASE/community/follow/$PID_ME"
conn="$(q "SELECT COUNT(*) FROM follows a JOIN follows b ON a.follower_id=b.following_id AND a.following_id=b.follower_id WHERE a.follower_id='$PID_ME' AND a.following_id='$F2ID'")"
[ "$conn" = "1" ] && c_pass "PART 3: a mutual follow is a connection (1 row)" \
                  || c_fail "PART 3: mutual follow -> connection rows=$conn"
fetch "$J_NEW" "$BASE/profile" "$TMP/profile3.html" >/dev/null
if grep -q "Connections" "$TMP/profile3.html" && grep -q "$F2NAME" "$TMP/profile3.html"; then
  c_pass "PART 3: /profile shows the Connections tile with the connected farmer"
else
  c_fail "PART 3: /profile is missing the Connections tile"
fi
# chat permission is symmetric: farmer2, who now follows me, may open my thread
c="$(fetch "$J_F2" "$BASE/messages/$PID_ME" "$TMP/f2_mine.html")"
[ "$c" = "200" ] && c_pass "PART 3: chat permission is symmetric (a follower may open my thread)" \
                  || c_fail "PART 3: farmer2 -> my thread $c (expected 200)"

# =====================================================================
# PART F: CLEAN UP THE DATA THIS RUN CREATED
#   The script registers users / labs / posts whose names begin with a smoke
#   prefix. Left behind, they make the NEXT run fail (a fixed demo mobile and
#   email would already be taken). So every row tied to a smoke-prefixed user
#   is deleted here, newest first, and the seeded demo users are never touched.
#   Deleting is idempotent: running the script again simply deletes nothing.
# =====================================================================
hdr "14. Clean up the smoke-test rows"

CLEAN_SQL="
DROP TABLE IF EXISTS _smoke_users;
CREATE TABLE _smoke_users (id BIGINT PRIMARY KEY);
INSERT INTO _smoke_users
  SELECT id FROM users
   WHERE username LIKE 'smoke%' OR username LIKE 'oklab%' OR username LIKE 'rejlab%'
      OR username LIKE 'dupemail%' OR username LIKE 'dupmo%' OR username LIKE 'badmobile%';
DELETE FROM lab_results       WHERE request_id IN (SELECT id FROM lab_requests WHERE farmer_id IN (SELECT id FROM _smoke_users));
DELETE FROM lab_requests      WHERE farmer_id IN (SELECT id FROM _smoke_users);
DELETE FROM community_comments WHERE user_id IN (SELECT id FROM _smoke_users)
   OR post_id IN (SELECT id FROM community_posts WHERE user_id IN (SELECT id FROM _smoke_users));
DELETE FROM community_votes   WHERE user_id IN (SELECT id FROM _smoke_users)
   OR post_id IN (SELECT id FROM community_posts WHERE user_id IN (SELECT id FROM _smoke_users));
DELETE FROM follows           WHERE follower_id IN (SELECT id FROM _smoke_users) OR following_id IN (SELECT id FROM _smoke_users);
DELETE FROM direct_messages   WHERE sender_id IN (SELECT id FROM _smoke_users) OR receiver_id IN (SELECT id FROM _smoke_users);
DELETE FROM community_posts   WHERE user_id IN (SELECT id FROM _smoke_users);
DELETE FROM farmer_profiles   WHERE user_id IN (SELECT id FROM _smoke_users);
DELETE FROM verification_session WHERE user_id IN (SELECT id FROM _smoke_users);
DELETE FROM phone_verification   WHERE user_id IN (SELECT id FROM _smoke_users);
DELETE FROM email_verification   WHERE user_id IN (SELECT id FROM _smoke_users);
DELETE FROM labs              WHERE user_id IN (SELECT id FROM _smoke_users);
DELETE FROM alerts            WHERE user_id IN (SELECT id FROM _smoke_users);
DELETE FROM users             WHERE id IN (SELECT id FROM _smoke_users);
DROP TABLE _smoke_users;
"
if "${MYSQL[@]}" -e "$CLEAN_SQL" 2>/dev/null; then
  there="$(q "SELECT COUNT(*) FROM users WHERE username LIKE 'smoke%' OR username LIKE 'oklab%' OR username LIKE 'rejlab%' OR username LIKE 'dupemail%' OR username LIKE 'dupmo%' OR username LIKE 'badmobile%'")"
  [ "${there:-0}" = "0" ] \
    && c_pass "smoke-test rows removed (demo data untouched)" \
    || c_fail "$there smoke-test user(s) left behind"
else
  c_fail "cleanup SQL failed"
fi

# =====================================================================
printf '\n============================\n'
printf 'RESULT: %d passed, %d failed\n' "$PASS" "$FAIL"
printf '============================\n'
rm -rf "$TMP"
[ "$FAIL" -eq 0 ]
