# DEBUG GUIDE — Precision Farming Advisory Service

A symptom-to-file table. Start from what you **see** in the browser (or the console
log line) and follow the "Where to look" column. Every path is relative to the project
root `/home/prab/Prab/Prabjingo/mini_pjt`.

## Quick health checks

```bash
# is the app up?
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/login      # expect 200

# is the database reachable and seeded?
mysql -uadvisory -padvisory123 advisory_db -e "SELECT COUNT(*) FROM users; SELECT COUNT(*) FROM districts;"

# full end-to-end proof (boot the app on a free port first)
bash smoke_test.sh http://localhost:8091
```

---

## Symptom → where to look

| Symptom (what you see) | Likely cause | Where to look | Fix |
|---|---|---|---|
| A page shows `??some.key??` | A message key is used in a template but missing from one or more bundles | `src/main/resources/messages.properties`, `messages_en/_hi/_mr.properties` | Add the key to **all four** files with real Hindi/Marathi. Then run the audit command (below). |
| Whole app fails to start, `NumberFormatException` about a duration | An `@Scheduled` delay is not ISO-8601 | `service/WeatherService.java` | Use `initialDelayString="PT20S"`, `fixedDelayString="PT3H"` (not `"20s"`). |
| **Every** page returns 500 | The shared top bar threw while rendering (e.g. calling a method on an unmodifiable collection in SpEL) | `templates/fragments/topbar.html` | Do not do `#authentication.authorities.toString()`. Use the security dialect: `sec:authorize="hasRole('OWNER')"`. |
| Login form does nothing / 403 on POST /login | The form lost its `th:action` so the hidden `_csrf` field is missing | `templates/pages/login.html`, `signup.html` | Every `<form>` must keep `th:action="@{...}"`. |
| A new lab account cannot log in | Lab accounts are disabled until the owner approves the lab row **and** the email is verified | `config/SecurityConfig.java` (`loginBlocked`), `repository/LabDao.java` | Set `labs.status='APPROVED'` (owner panel does this) **and** verify the email; the account is enabled only when both hold. |
| A new farmer/lab cannot log in, lands on `/login?disabled` | The email address is not verified yet (`loginBlocked` returns true) | `config/SecurityConfig.java` (`loginBlocked`), message key `auth.login.disabled` | Open the link on `/verify/email?h=...` (DEMO MODE box) or the one in the email, then log in. |
| The verification email never arrives | No SMTP server is configured, so the app is in DEMO MODE (nothing is sent) | `application.properties` (`spring.mail.*`), `service/EmailVerificationService.java` (`realMail()`) | Use the link shown in the DEMO MODE box / the log, or set `spring.mail.host` etc. (see README "Email setup"). |
| "Wait a minute" / "too many links this hour" on resend | The resend rate limit refused it (normal, not an error) | `service/EmailVerificationService.java` (`send`), `email_verification` columns `last_sent_at` / `window_start` / `sends_in_window` | Wait, or adjust `email.resend.seconds` / `email.resend.per.hour`. |
| The verification link says "expired" or "not valid" | The token expired, was already used, or the handle expired | `templates/pages/verify-email-done.html`, `repository/VerificationDao.java`, `email.token.hours` / `verification.handle.hours` | Register again or request a fresh link from `/verify/email`. |
| "Access is denied" (403) on a page you should see | Role rule in SecurityConfig | `config/SecurityConfig.java` | Check the `requestMatchers(...).hasRole(...)` order. `/owner/**` = OWNER, `/lab/dashboard` = LAB, `/debug` = OWNER. |
| A farmer sees another farmer's data | Missing ownership check | `LabController.java`, `ProfileController.java`, `CommunityController.java` | Queries must filter by the current user's id, not just the path id. |
| Photo upload returns 500, log shows `FileNotFoundException: /tmp/tomcat.../uploads/...` | A relative path was resolved against Tomcat's temp dir | `controller/CommunityController.java` (`storeImage`) | Use `.getAbsoluteFile()` on both the directory and the target file. |
| Photo upload rejected | Wrong type or size | `CommunityController.java`, keys `error.image.type` / `error.image.size` | JPG/PNG only, max 2 MB. Served back through `GET /community/image/{id}`. |
| Report download is not a PDF (starts with `<!DO`) | The report file was not generated, or the ownership check failed and an error page was returned | `service/LabReportService.java`, `controller/LabController.java` (report route) | The report is written to `uploads/reports/report-<TRACKING>.pdf` when a request becomes REPORT_READY. Check the file exists and the request belongs to the logged-in farmer. |
| Nowcast/Forecast page is empty or shows only SAMPLE rows | The scraper has not run yet or the source was unreachable | `service/WeatherService.java`, `service/GreenAlertsScraper.java`, `service/OpenMeteoSource.java`, tables `weather_cache` / `weather_forecast` / `weather_scrape_log` | Wait ~20s after boot (or check the owner `/debug` page). Open-Meteo is the fallback; the page reads the cache only and never crashes. |
| "last updated" time never changes | Every source failed, so the honest timestamp is left untouched | `service/WeatherService.java` | Inspect `weather_scrape_log` for the last status. |
| Map dots, all grey / wrong colours | The latest reading per district is missing, or the band class is not applied | `controller/WeatherController.java` (`/map`), `repository/WeatherDao.java` (`latestPerDistrict`), `static/css/style.css` (`.band-0..5`, `.band-none`) | Confirm each district has a `weather_cache` row and the colour band derives from the temperature. |
| Scheme detail page is blank in one language | No `scheme_text` row for that scheme + language | tables `schemes`, `scheme_text` (`UNIQUE KEY uq_scheme_lang`) | Re-run `data_new.sql`. |
| Owner edit of a scheme does not show | The `scheme_text` row was not updated | `controller/OwnerController.java` (`saveScheme`), `repository/SchemeDao.java` (`updateText`) | It is an `INSERT ... ON DUPLICATE KEY UPDATE` on `(scheme_id, lang)`; the empty-name case re-renders (200) without saving. |
| Vote count goes up every click | The vote upsert is broken | `repository/CommunityDao.java` (`vote`) | It must be `INSERT ... ON DUPLICATE KEY UPDATE` keyed on `(post_id, user_id)` = one vote per user per post. |
| `spring.jpa.open-in-view is enabled by default` WARN | Benign Spring Boot notice | `application.properties` | Harmless; leave it (feeds the viva's "what would you change" answer). |
| `Port 8080 was already in use` | A leftover JVM from a previous run | terminal | Stop **your** old run (`Ctrl+C`); for testing use `--server.port=8091`. Never kill a process you did not start. |
| Hindi/Marathi text shows as `???` | A message file was saved in the wrong encoding | `application.properties`, `messages_*.properties` | Keep `spring.messages.encoding=UTF-8` and save the files as UTF-8. |
| Lookup falls back to a system locale | `fallback-to-system-locale` flipped | `application.properties` | Keep `spring.messages.fallback-to-system-locale=false`. |

---

## Missing-translation audit

Run this after any template or message change. It must print a non-zero key count and
no "missing" lines.

```bash
cd /home/prab/Prab/Prabjingo/mini_pjt
keys=$(grep -rhoE '#\{[a-zA-Z0-9_.]+' src/main/resources/templates | sed 's/#{//' | sort -u)
for k in $keys; do
  for f in messages messages_en messages_hi messages_mr; do
    grep -qE "^${k//./\\.}=" "src/main/resources/$f.properties" || echo "MISSING $k in $f"
  done
done
echo "keys used: $(echo "$keys" | wc -l)"
```

---

## Controller map (which class serves which URL)

| URL | Controller |
|---|---|
| `/`, `/login`, `/signup` | `controller/MainController.java` |
| `/register` | `controller/RegisterController.java` |
| `/register/lab` | `controller/LabRegisterController.java` |
| `/verify/email`, `/verify/email/resend`, `/verify/email/confirm`, `/verify/skip` | `controller/VerifyController.java` |
| `/profile` | `controller/ProfileController.java` |
| `/nowcast`, `/forecast`, `/map` | `controller/WeatherController.java` |
| `/lab`, `/lab/request`, `/lab/request/{id}`, `/lab/request/{id}/report` | `controller/LabController.java` |
| `/lab/dashboard`, `/lab/dashboard/advance`, `/lab/dashboard/result` | `controller/LabDashboardController.java` |
| `/schemes`, `/schemes/{key}` | `controller/SchemesController.java` |
| `/community/**` | `controller/CommunityController.java` |
| `/owner/**` | `controller/OwnerController.java` |
| `/debug` | `controller/DebugController.java` |
| `/error` | `controller/CustomErrorController.java` |

---

## If an external site is unreachable

The weather pages read from the database cache, so the demo still works. To check the
live source by hand (from the project root), exactly as the scraper does:

```bash
curl -sS -A "PrecisionFarmingAdvisory/0.1 (college mini project)" \
  https://greenalerts.in/weather-forecast-india/ -o /tmp/ga.html -w '%{http_code}\n'
```

If this fails, the fallback is Open-Meteo:

```bash
curl -sS "https://api.open-meteo.com/v1/forecast?latitude=18.52&longitude=73.86&current=temperature_2m,relative_humidity_2m,wind_speed_10m&timezone=Asia/Kolkata" -w '\n%{http_code}\n'
```
