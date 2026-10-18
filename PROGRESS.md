# PROGRESS — Precision Farming Advisory Service

Legend: [x] done · [~] in progress · [ ] remaining

## STEP 0 — Missing-translation audit
- [x] Command: `grep -rhoE "#\{[a-zA-Z0-9._-]+\}" src/main/resources/templates | sed 's/#{//;s/}//' | sort -u`
- [x] Compared against messages.properties / _en / _hi / _mr. Result: **0 missing keys** (63 keys used).
- [x] Re-run at the end of every step (script in "Reusable commands" below).

## STEP 1 — Design — DONE
- [x] style.css rewritten: 1 accent (#19d3b0), near-black navy #0a1020 + radial glows + dot grid,
      card #111a2e 18px radius, 76px emoji circle, full-width pill buttons, centred flex-wrap layouts.
- [x] topbar.html rewritten: centred title, centred uppercase workspace nav 1-7 (accesskey 1-7),
      language switcher EN|हिं|मरा, login/logout right. **LIGHT theme toggle removed.**
- [x] home.html rewritten: header tile + 6 workspace cards, numbers 2-7 match the top bar.
- [x] login.html, signup.html (chooser), coming-soon.html, error/403,404,500.html, debug.html
      restyled in the same look.
- [x] Removed `#PFAS`, digit-square icons, random accents, big empty tiles.
- [x] Added missing keys `auth.login.registered`, `auth.signup.choose|farmer|lab` to all four files.
- [x] Removed unused `theme.*` keys from all four files.
- [x] Fixed error pages referencing renamed CSS vars (`--warning-amber`/`--severe-red` -> `--warn`/`--danger`).
- [x] `./mvnw -q compile` -> EXIT 0. Audit -> 0 missing keys.

## STEP 2 — Registration and profile — DONE
- [x] schema_new.sql (NEW, re-runnable): districts, lab_status_history, follows, weather_forecast,
      scheme_text, district_offices tables + guarded ADD COLUMN for labs.taluka/status,
      farmer_profiles.village/irrigation/preferred_lang, weather_config.source, weather_cache.*,
      schemes.scheme_key/sort_order. Applied once (34 statements).
- [x] data_new.sql (NEW, re-runnable): 36 Maharashtra districts (en/hi/mr + approx HQ lat/long),
      labs set to APPROVED, lab talukas. Applied once.
- [x] Models: District (record + nameFor(lang)), FarmerProfile, Lab (plain carriers),
      ReferenceData (crop/soil/irrigation/unit/lang/sample code lists).
- [x] DAOs (JdbcTemplate): DistrictDao, FarmerProfileDao (update guarded by WHERE user_id=?),
      LabDao (insert forces PENDING; findApprovedByDistrict).
- [x] Controllers: RegisterController (GET/POST /register), LabRegisterController
      (GET/POST /register/lab), ProfileController (GET/POST /profile, ownership by current user).
- [x] MainController: removed the dangerous POST /signup (let a visitor make an OWNER).
- [x] ComingSoonController: removed GET /profile (no duplicate mapping).
- [x] SecurityConfig: permitAll for /register and /register/lab.
- [x] Templates: pages/register.html (3 tiles), register-lab.html (3 tiles), profile.html (3 tiles).
      Multi-tile single form via hidden <form th:action> + HTML5 form="id" (no JS).
- [x] style.css: added .tile--form (620px, full width under 800px).
- [x] Messages: sections 13-19 added to ALL FOUR bundles (en/hi/mr real text).
- [x] `./mvnw -q compile` -> EXIT 0. Audit -> **0 missing keys** in all four files.
Assumptions (also noted for the viva):
- `districts` table holds all 36 Maharashtra districts (en/hi/mr) with approximate HQ lat/long
  (reused by STEP 4's SVG map).
- Farmer profile stores crops as a comma-separated codes column (simple to explain).
- New registrations: role FARMER / LAB, enabled=true; LAB status = PENDING until owner approves.

## STEP 3 — Government schemes and registration guide — DONE
- [x] data_new.sql extended (sections 3-5, re-runnable, applied once): 7 schemes with
      scheme_key + sort_order (pmkisan 1 .. farmerid 7), 21 scheme_text rows (7 schemes x
      en/hi/mr, real Hindi + Marathi prose), 36 district_offices rows (SAMPLE).
- [x] Models: Scheme (plain carrier), DistrictOffice (record).
- [x] DAOs: SchemeDao (LEFT JOIN scheme_text ON lang -> a scheme still renders if one
      language row is missing), DistrictOfficeDao.findByDistrictCode.
- [x] Controller: SchemesController — GET /schemes (list + "find my district office" tile),
      GET /schemes/{key} (unknown key -> 404, not 500).
- [x] ComingSoonController: removed GET /schemes (a duplicate mapping would stop the app).
- [x] Templates: pages/schemes.html (header tile + office tile + one card per scheme),
      pages/scheme-detail.html (6 tiles: what / who / documents / steps / where / official).
- [x] Messages: section 20 "SCHEMES" added to ALL FOUR bundles.
- [x] Cleanup: every literal `??key??` in template comments replaced with "a missing key"
      (14 templates) so no `??` can ever reach the rendered HTML.
- [x] `./mvnw -q compile` -> EXIT 0. Audit -> 0 missing keys (132 keys used).
- [x] Live smoke test (port 8082, logged in as farmer1): /schemes 200, /schemes/pmkisan 200,
      /schemes/farmerid 200, /schemes/doesnotexist 404, `??` count in HTML = 0.
Assumption (viva note): the readable scheme text lives in the `scheme_text` DB table (not in
message files) so the OWNER can edit it in STEP 7 without a rebuild; only the surrounding
labels are message keys.

## STEP 4 — Weather, nowcast, forecast, map — DONE
- [x] Fetched and READ the real greenalerts.in HTML with curl (robots.txt checked:
      no rule blocks /weather-forecast-india/). Real selectors taken from the live pages:
        state page   : ul.ga-gp__list li a                      -> district links
        district page: .today-snapshot h2 (heading + date), p.ts-lead (summary),
                       .ts-chip span/b (temp chip), .ga-source__meta dd (source = IMD),
                       ul.ga-gp__list li a                      -> block (taluka) links
        block page   : article.ga-day (id day-YYYY-MM-DD), h3.ga-day__date time[datetime],
                       span.ga-day__chip (sky), ul.ga-day__tiles li ->
                       span.ga-day__tkey ("Max temp"/"Min temp"/"Rainfall"/"Humidity"/
                       "Cloud"/"Wind") + span.ga-day__tval (value + <small> unit)
- [x] Verified Open-Meteo needs no key; real fields used:
      current=temperature_2m,relative_humidity_2m,wind_speed_10m
      daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,
            wind_speed_10m_max  (timezone Asia/Kolkata, forecast_days=7)
- [x] schema_new.sql section 8 (applied once): weather_scrape_log table + guarded
      alerts.dedupe_key column + unique key uq_alert_dedupe(user_id, dedupe_key).
- [x] data_new.sql section 6 (applied once): 6 real weather_config block URLs
      (raigad/panvel, pune/haveli, pune/baramati, nashik/igatpuri, nagpur/kamptee,
      kolhapur/hatkanangale) + 36 SAMPLE weather_cache rows (one per district) +
      SAMPLE weather_forecast rows for the six watched blocks, via WITH RECURSIVE d7.
- [x] Models: WeatherReading, WeatherDay, WeatherConfig, ScrapeLog, Alert (severityClass()),
      MapPoint (label()).
- [x] DAOs (JdbcTemplate): WeatherDao (configs, insertReading, latestForDistrict/Location,
      latestPerDistrict, upsertForecast, forecastForDistrict/Location, findAllLogs, upsertLog,
      countCacheRows, countSampleRows), AlertDao (insertIfNew = INSERT IGNORE, findForUser, countAll).
- [x] WeatherSource interface + GreenAlertsScraper (Jsoup, polite UA/timeouts) +
      OpenMeteoSource (java.net.http + Jackson). fetch() never throws, so one dead source
      cannot stop the job.
- [x] WeatherService: @Scheduled(initialDelayString="PT20S", fixedDelayString="PT3H"),
      writes weather_cache/weather_forecast/weather_scrape_log; on total failure it keeps the
      old rows and only leaves last_success_at untouched ("last updated" stays honest);
      then 4 crop+forecast alert rules (heat / heavy rain / irrigation / fungal-spray).
- [x] WeatherController: GET /nowcast, GET /forecast, GET /map. Pages read the cache ONLY -
      the controller has no HTTP client at all. Map: lat/lon -> inline SVG done in Java
      (cos(midLat) correction), 6 temperature bands + a "no reading" band, layer switching by
      links with a whitelist (a hand-typed ?layer= falls back to temp).
- [x] ComingSoonController: /nowcast and /forecast removed (duplicate mapping would stop the app).
- [x] Templates: pages/nowcast.html, forecast.html, map.html; pages/debug.html rewritten
      (real watch list + cache/sample/alert counts); DebugController now takes WeatherDao+AlertDao.
- [x] style.css: .band-0..band-5 / .band-none (one --band custom property drives both the SVG
      dot fill and the HTML legend swatch), .map-svg, .district-dot, .map-legend, .forecast-strip,
      .stepper, .table-wrap, .table__url.
- [x] Messages: section 21 (21a nowcast / 21b forecast / 21c map / 21d alerts / 21e debug)
      added to ALL FOUR bundles with real Hindi and Marathi text.
- [x] `./mvnw -q compile` -> EXIT 0. Audit -> **0 missing keys** in all four files (186 keys used).
- [x] Live smoke test (port 8083, farmer1 / owner / lab1): /, /nowcast, /forecast, /lab,
      /schemes, /community, /profile, /register, /register/lab, /debug all **200** in
      en + hi + mr, `??` count = 0 in every page; /debug as farmer1 -> 403; /map = 36 dots +
      7 legend swatches; /map?layer=rain, /map?district=raigad, /map?layer=bogus all 200.
- [x] Live scraping verified: after the first scheduled run weather_cache held 9 GREENALERTS
      rows and all 7 weather_scrape_log rows read OK; weather_forecast held 70 real rows
      (the SAMPLE rows were replaced in place, never duplicated).

Bugs found and fixed while smoke-testing STEP 4:
- `@Scheduled(initialDelayString = "20s")` stopped the WHOLE app from starting
  (`NumberFormatException`). Both delays must be ISO-8601: `PT20S` / `PT3H`.
- `/profile` returned 500: the template reads `errors['field']` but the GET handler never
  added an `errors` map. Same latent bug in `/register` and `/register/lab`. All three GET
  handlers now add an empty `Map.of()`.
- /nowcast showed `??Heavy Rain Warning_en??`: sample_data.sql had seeded the `alerts` table
  with plain English sentences, but the template resolves each row with `#messages.msg(...)`.
  data_new.sql section 7 (NEW) re-points any non-key row at `alert.general.demo.*`.

Assumptions (viva notes):
- greenalerts publishes RAINFALL in mm (not a "rain chance %"), so the Rain cell stores
  whatever unit the source gives ("0 mm" from greenalerts, "19 %" from Open-Meteo) and the UI
  shows the value with its unit. No number is invented.
- greenalerts' district page shows today's MAXIMUM as the "current" reading - it is published
  as such by the source, and the page is labelled "current conditions".
- SAMPLE rows exist so the demo runs with the college wifi off; they carry is_sample=1 /
  source='SAMPLE' and the UI shows the SAMPLE badge + note.
- Alerts rows store message KEYS (not sentences) so the same row reads correctly in all three
  languages; dedupe_key (user_id, date, rule) makes the 3-hourly job idempotent per day.

## PART 1 — PROVE WHAT EXISTS (smoke_test.sh) — DONE
- [x] `smoke_test.sh` written in the project root. Starts nothing itself; takes a base URL
      (`bash smoke_test.sh http://localhost:8090`), logs in with a cookie jar (GET /login ->
      read `_csrf` -> POST), and checks farmer1 / lab1 / owner.
- [x] Sections: 0 reachable; 1 logins; 2 the 8 top-bar pages x 3 languages (200/403, no 404/500,
      no `??key??`); 3 farmer registration end to end + duplicate username + bad mobile;
      4 lab registration -> PENDING + PENDING lab cannot log in; 5 schemes list/detail x3 langs;
      6 weather cache/forecast/scrape_log rows + last-updated on /nowcast,/forecast;
      7 /map 36 dots + ?district=pune side panel; 8 personalised alert; 9 access control;
      10 full demo flow (request -> lab advances -> result -> PDF `%PDF` -> collect note ->
      Google Maps link); 11 community.
- [x] Result on port 8090: **45 passed, 0 failed**.
- Bugs found and fixed: `/forecast` had no last-updated stamp (WeatherDay/WeatherDao/
      WeatherController + forecast.html now show it); smoke taluka assertion relaxed to the
      real rendered attribute order.

## STEP 5 — Lab workflow — DONE
- [x] Already implemented (farmer request form with district APPROVED labs, sample type, crop,
      notes; tracking id; "My requests"; CSS 5-stage stepper; lab_status_history timestamps;
      lab dashboard queue + advance + results form with validation; OpenPDF report on disk
      outside static, downloaded through an ownership-checked controller; "labs near me" with
      OpenStreetMap iframe).
- [x] Added the missing "Open in Google Maps" link (`lab.near.gmaps` in all four bundles).
- [x] Added the "collect the printed copy at the lab" note on both tracker views when ready
      (reused `lab.report.collect_note`).
- [x] Lab accounts can now log in ONLY after the owner approves them: SecurityConfig's
      UserDetailsService disables a LAB user whose lab row is not APPROVED.
- [x] smoke_test.sh: +3 checks (PENDING lab cannot log in, collect note, Google Maps link).
- [x] `./mvnw -q compile` -> EXIT 0. Smoke: 45 passed, 0 failed.

## STEP 6 — Community — DONE
- [x] Models: Post (with getScore/hasImage), Comment, Message, Member (record, displayName()),
      Conversation (record).
- [x] CommunityDao (JdbcTemplate): community_posts/comments/votes/follows/direct_messages.
      The feed SELECT computes up/down votes, comment count and the viewer's own vote in one
      query; vote() uses INSERT ... ON DUPLICATE KEY UPDATE => one vote per user per post.
- [x] CommunityController: GET /community (feed + new-post form + farmer directory),
      POST /community/post (text <=1000 + optional photo), GET /community/post/{id},
      POST /community/post/{id}/comment (<=500), POST /community/post/{id}/vote (UP|DOWN),
      POST /community/post/{id}/delete (OWNER only -> 403 otherwise),
      POST /community/follow/{id} + /unfollow/{id} (safeBack), GET /community/profile/{id},
      GET /community/messages (inbox), GET+POST /community/messages/{id} (thread),
      GET /community/image/{id} (streams the stored file).
- [x] Uploads: JPG/PNG only, <= 2 MB, stored under <uploads>/community (OUTSIDE static) and
      served back through the controller. Image type/size errors are message keys.
- [x] Templates: pages/community.html, community-post.html, community-profile.html,
      community-inbox.html, community-thread.html. Reuses the existing tile/lab-card/post CSS
      (no CSS redesign; no JavaScript - votes/follow/comments are small POST forms).
- [x] ComingSoonController deleted (it only served /community; every top-bar link is now real).
- [x] Messages: community.* + error.image.type/size in ALL FOUR bundles (real Hindi/Marathi);
      audit -> **0 missing keys** in all four files (292 keys used).
- [x] smoke_test.sh section 11: post -> comment (farmer2) -> vote (one per user) -> follow ->
      direct message -> photo upload + serve -> non-image rejected -> non-owner delete 403 ->
      owner deletes (comments cascade). Result: **58 passed, 0 failed** on port 8091.
- [x] `./mvnw -q compile` -> EXIT 0.

Bugs found and fixed while smoke-testing STEP 6:
- Photo upload returned 500: `MultipartFile.transferTo()` resolves a RELATIVE path against
  the servlet container temp dir (`/tmp/tomcat.../uploads/...`), not the project dir. Fixed
  by using `new File(uploadDir, "community").getAbsoluteFile()` (and the same on the target).

## STEP 7 — Owner panel + finish — DONE
- [x] OwnerController: GET /owner (five live counts + PENDING lab list),
      POST /owner/lab/{id}/approve, POST /owner/lab/{id}/reject (404 if unknown),
      GET /owner/schemes, GET /owner/schemes/{key}?lang= (404 unknown key),
      POST /owner/schemes/{key} (empty name -> 200 re-render with the required error).
- [x] DAO additions: UserRepository.countByRole, LabDao.countAll/countByStatus,
      CommunityDao.countPosts, SchemeDao.updateText (INSERT ... ON DUPLICATE KEY UPDATE
      on the unique key uq_scheme_lang).
- [x] SecurityConfig: `/owner`, `/owner/**` -> hasRole("OWNER").
- [x] Templates: pages/owner.html, owner-schemes.html, owner-scheme.html (reuse the existing
      layout-* / tile / .table / .info-list CSS; no CSS redesign, no JavaScript).
- [x] topbar.html: owner-only shortcut to /owner.
- [x] Messages: owner.* section 30 added to ALL FOUR bundles (real Hindi + Marathi);
      audit -> **0 missing keys** in all four files (335 keys used).
- [x] ComingSoonController already gone; the unused placeholder
      templates/pages/coming-soon.html deleted. Every top-bar link is a real page.
- [x] smoke_test.sh section 12: /owner 200 + counts tile, owner link visible to owner only,
      farmer -> /owner 403, a fresh lab is PENDING, approve -> APPROVED + the approved lab can
      then log in, reject -> REJECTED, scheme editor empty name refused, a real edit lands in
      scheme_text and shows on the public /schemes/pmkisan, unknown key 404, seeded text
      restored. Result: **73 passed, 0 failed** on port 8091.
- [x] `./mvnw -q compile` -> EXIT 0.
- [x] README.md + DEBUG_GUIDE.md written.

Bugs found and fixed while smoke-testing STEP 7:
- EVERY page returned 500 once the owner link was added: the top bar tested the role with
  `#authentication.authorities.toString().contains('OWNER')`, and calling `toString()` on the
  unmodifiable authorities list throws `InaccessibleObjectException` (java.base does not open
  java.util to the unnamed module). Fixed by switching to the Spring Security Thymeleaf dialect:
  `sec:authorize="hasRole('OWNER')"` (thymeleaf-extras-springsecurity6 was already on the
  classpath). All 8 pages x 3 languages recovered.


## PART A — Daily scraping (once a day + catch-up + run history) — DONE
- [x] `application.properties` (datasource UNCHANGED, `spring.sql.init.mode=never` kept):
      ADDED `weather.refresh.cron=0 0 6 * * *`, `weather.history.days=30`, `weather.catchup.hours=24`.
- [x] `WeatherService`: the old 3-hourly `@Scheduled(fixedDelay)` replaced by
      `@Scheduled(cron="${weather.refresh.cron:0 0 6 * * *}", zone="Asia/Kolkata")` -> once a day
      at 06:00 IST. Cron is read from the property (no code change to change the time).
- [x] Start-up CATCH-UP: `@EventListener(ApplicationReadyEvent.class)` submits a background job
      (a single daemon "weather-refresh" thread) that calls `anyDistrictStale()`: if any watched
      district has no reading or one older than `weather.catchup.hours` (24 h) it runs a full
      refresh. Boot is never blocked - the listener returns at once. Verified in the log:
      "[WEATHER] Catch-up not needed: every watched district has a reading newer than 24 h".
- [x] Owner "Refresh now": `POST /debug/refresh` (OWNER only, CSRF via `th:action`) calls
      `refreshInBackground("MANUAL")` and redirects back to `/debug?refreshed`.
- [x] Run history: new re-runnable table `weather_scrape_run` (schema_new.sql section 9). Every
      refresh writes one row (trigger, source, districts_ok/total, status, message). `/debug`
      tile [5] lists the last 20 (time, trigger, source, districts, status, detail).
- [x] Fallback kept: GreenAlerts -> Open-Meteo -> if BOTH fail the previous cache rows stay and
      "last updated" simply gets older (never crash, never delete).
- [x] History kept + RETENTION: readings are appended, never overwritten; after each refresh
      `retention(weather.history.days)` deletes `weather_cache`/`weather_forecast`/
      `weather_scrape_run` rows older than 30 days.
- [x] Source shown on every weather page: new `weather.source` row on `/nowcast` and `/forecast`
      (WeatherController now also passes `source` for nowcast).
- [x] `/debug` tile [3] now shows the cron in force, the retention window and the live
      stale/up-to-date flag; tile [5] is the run-history table.
- [x] Messages: 16 new PART A keys added to ALL FOUR bundles with real Hindi + Marathi;
      the stale `debug.scraper.none` wording (20 s / every 3 hours) corrected to
      "start-up when stale, then daily at 06:00 IST".
- [x] `./mvnw -q compile` -> EXIT 0.
- [x] smoke_test.sh section 13 (10 checks): /debug 200 + no missing keys, cron shown, run tile
      present, fresh cache = "Up to date", a district shifted 5 days back = "Stale", restore =
      "Up to date" again, farmer -> POST /debug/refresh **403**, owner -> **302**, a MANUAL run
      row appears with a valid source. Result: **83 passed, 0 failed** on port 8091.
- [x] WHICH SOURCE REALLY WORKED FROM THIS MACHINE: GREENALERTS. The MANUAL run logged
      `source=GREENALERTS, districts 7/7 written` (Open-Meteo was never needed). The live scrape
      of greenalerts.in succeeds from this network; Open-Meteo remains the fallback when it is not.

Bugs found and fixed while smoke-testing PART A:
- `WeatherService` would not compile: a javadoc comment contained the literal `*/6` (from the
  example cron `0 0 */6 * * *`); the `*/` closed the comment early. Reworded the comment.
- The `/debug` data-status label was inverted (`${stale} ? yes : no` with `yes=Up to date`).
  Swapped the two message values so "Stale - a refresh is due" shows when stale is true.

Assumptions (viva notes):
- "Catch-up" is checked on every start-up (not stored in the DB) via a single
  `SELECT MAX(timestamp) ... WHERE district=?` per watched config, so it is cheap and cannot
  go stale itself.
- The daily job and the manual button both call the SAME `refreshAll(trigger)`, so the run
  history and retention logic are identical whichever way it is started.
- `weather_scrape_run` rows are real refresh history (not test junk), so the smoke test leaves
  the MANUAL row in place; the demo DB stays clean of the SAMPLE/lab/user rows the tests create
  (those are cleaned in PART F).

Remaining: PART C (real GIS maps), PART D (forecast details), PART E (usability pass),
PART F (extend smoke tests + cleanup + docs).


## PART B — Registration with email + phone verification — DONE
- [x] SQL (re-runnable, never DROPs): `schema_new.sql` section 10 adds guarded ALTERs on `users`
      (`email VARCHAR(120) NULL`, `phone VARCHAR(15) NULL`, `phone_verified TINYINT(1) NOT NULL
      DEFAULT 0`, `email_verified TINYINT(1) NOT NULL DEFAULT 0`, unique key `uq_users_email`)
      plus tables `phone_verification`, `email_verification`, `verification_session`.
      MySQL 8 has no `ADD COLUMN IF NOT EXISTS`, so each column is added via a prepared-statement
      guard that checks `information_schema.COLUMNS` first. No UNIQUE on `users.phone` on purpose
      (a lab may legitimately share a landline); farmer-mobile uniqueness is enforced in code.
- [x] `data_new.sql` section 8: six re-runnable `UPDATE users SET email=..., phone=...,
      phone_verified=1, email_verified=1 WHERE username='...' AND email IS NULL;` so the existing
      demo users (farmer1/2/3, lab1, lab2, owner) keep working and are pre-verified.
- [x] Registration form (`register.html`): full name, email, mobile, password + confirm, preferred
      language, then the farm details. Per-field errors in the active language; typed values kept
      after an error. New email field + hint added; the mobile hint now says the code is sent by SMS.
- [x] Server-side rules in `RegisterController.validate()`: email required + valid format +
      unique; mobile required, 10 digits starting 6-9, unique (`error.email.taken`,
      `error.mobile.taken`). Password: min 8 chars.
- [x] Verification flow: the account is created UNVERIFIED and the browser is redirected to
      `/verify?h=<one-time handle>` (handle in `verification_session`, never a raw user id).
- [x] OTP rules in ONE place (`OtpService`): 6-digit random code stored HASHED (BCrypt), expiry
      `otp.expiry.minutes` (10), max `otp.max.attempts` (5) then `otp.lock.minutes` (15) lockout,
      resend at most once every `otp.resend.seconds` (60) and `otp.resend.per.hour` (5) per hour.
- [x] `OtpSender` interface with two implementations: `DemoOtpSender` (default, `otp.provider=demo`)
      writes the code to the log and the verify page shows it inside a "DEMO MODE: no SMS is sent"
      box; `SmsGatewayOtpSender` is a no-op placeholder with TODO comments where MSG91/Twilio plug
      in, selected by `otp.provider=sms`. No API key needed to run.
- [x] Email verification: `spring-boot-starter-mail`; `JavaMailSender` is looked up through an
      `ObjectProvider`, so the app boots with NO SMTP configured. With SMTP properties set it sends
      a real link with a random token (SHA-256 hashed in `email_verification`, expires 24 h); without
      them it is demo mode (link shown on the page + logged). Email is OPTIONAL and never blocks login.
- [x] Login is BLOCKED until the PHONE is verified: `SecurityConfig` `.disabled(labNotApproved ||
      phoneNotVerified)`, and a `failureHandler` routes a disabled account to `/login?disabled`
      (a clear message + a `/verify/recover` resend form) instead of a generic credentials error.
- [x] Profile page shows the email address and "Email status: Verified / Not verified yet"
      (read-only; `ProfileController.addAccount`).
- [x] Lab registration collects name, email and phone with the same validation; the account still
      waits for owner approval AND phone verification before it can log in.
- [x] Messages: 40+ new PART B keys in ALL FOUR bundles (`field.email`, `register.email.hint`,
      `error.email.invalid/taken`, `error.mobile.taken`, `auth.login.disabled/verified`,
      the whole `verify.*` family, `profile.email.*`) with real Hindi + Marathi.
- [x] `./mvnw -q compile` -> EXIT 0.
- [x] smoke_test.sh: rewritten section 3 (email+phone), new section 3b (OTP rules), sections 4 + 12
      updated for the verification flow. Result: **98 passed, 0 failed** on port 8092.
- [x] DB rows checked: all 6 demo users verified=1; `phone_verification`=5, `verification_session`=5;
      the fresh `smokeotp*` account is phone_verified=0 with a real `locked_until` after the
      5-wrong-code test.

Bugs found and fixed while smoke-testing PART B:
- `VerifyController` would not compile: `pv.getPhone()` on a `record` (`PhoneVerification`) — the
  accessor is `phone()`. Fixed.
- `SecurityConfig` disabled a LAB for "not approved" but PART B also disables it for "phone not
  verified", so the smoke test's "approved lab can log in" needed a phone-verification step first.

Assumptions (viva notes):
- The verify link identifies the account by a random 32-hex handle, so nobody can verify someone
  else's phone by guessing a user id; the handle expires after `verification.handle.hours` (24).
- Email verification is deliberately OPTIONAL (a farmer can log in with only the phone verified).
- Tests create `smoke*` users/labs/verification rows; those are removed by the PART F cleanup.


## PART C — Real GIS maps (GeoJSON boundaries, heatmap, crop map) — DONE
- [x] GeoJSON: `src/main/resources/geo/maharashtra_districts.geojson` (open district boundaries,
      under 1 MB; source + licence recorded in README.md). Never edited at runtime.
- [x] `GeoMapService` parses the file ONCE at startup: builds one SVG path per district with a
      fixed viewBox (820x660) and a `cos(midLat)` correction so the state is not stretched East-West.
      It also caches the state outline (`outlinePath`), the label anchor per district and the IDW
      heat grid, so no request ever re-parses the file. Startup log:
      `[MAP] geo/maharashtra_districts.geojson loaded: 35 district shape(s), 36 code(s), viewBox 820x660`.
- [x] `/map` rewritten from the old dot map to real boundaries: server-rendered inline `<svg>` with
      one `<path class="district">` per district, an `<a>` wrapper per district linking to
      `?district=<code>` (keeps `layer` + `crop`), and a `<title>` per path so hover shows the name
      and value (SVG-only, no JS).
- [x] Layers (link row): temperature, heatmap, rain probability, rain heatmap, wind speed, crop map.
      Value layers colour each district by band (`band-*` classes) with a legend and a `band-none`
      swatch for "no reading". Crop layer uses `crop-*` classes + a `#{crop.__${c}__}` legend.
- [x] Heatmap: inverse-distance-weighted grid (~50x60 cells) computed in Java, drawn as `<rect>`
      cells clipped to the state with `<clipPath id="state"><path d=outline></clipPath>`.
- [x] Crop map: `crop=ref` shows the seeded reference crop per district; `crop=live` shows the
      majority crop from real farmer profiles, with a `map.crop.note` when few farmers are recorded.
- [x] Selected district side tile: picker form (hidden `layer`/`crop` + labelled `select#district`),
      selected crop, farmer count for that crop, temperature / rain / wind / last-updated, the
      nowcast link, and a `map.none` prompt when nothing is selected.
- [x] District table below the canvas: one row per district with `<th>` headers and a per-row link
      back to `?district=`; responsive viewBox, `width:100%`, `max-height ~70vh`.
- [x] Messages: `map.intro`, `map.none`, `map.approx` reworded (no longer say "dot") and new keys
      `map.layer.heat|rainheat|crop`, `map.crop.ref|live|note|farmers`, `map.legend.title|crops`,
      `map.table.title|district|value|farmers`, `map.geo_missing`, `map.selected` in ALL FOUR
      bundles with real Hindi + Marathi.
- [x] CSS: reuse existing `.tile`/`.map-wrap`/`.map-svg`; only NEW rules added for `.tile--map`,
      `.tile--side`, `.heat-cell`, `.district--heat`, `.district-label`, `.map-legend*` and the two
      specificity overrides (`.layout-home > .tile--map` / `.tile--side`) in section 6 and the
      800px `@media` block. No existing class was restyled.
- [x] `./mvnw -q compile` -> EXIT 0. Startup log confirms the GeoJSON load and a fresh cache.
- [x] smoke_test.sh section 7 replaced dot counting with real checks (7 checks): `/map` -> 200,
      36 district boundaries drawn, inline SVG present, `?district=pune` fills the side panel,
      `?layer=heat` draws cells, the heat surface is clipped to the state, `?layer=crop&crop=ref`
      colours by crop. Result: **103 passed, 0 failed** on port 8093.
- [x] DB rows checked: `users` holds 0 `smoke*%` rows and the 4 seeded demo users are untouched.

Bugs found and fixed while smoke-testing PART C:
- Smoke sections 10/11 failed (302 on create, 0 rows) — NOT a PART C regression. Root cause:
  section 3 posts a FIXED demo mobile + email, and 32 leftover `smoke*` users from earlier runs
  already held them, so registration 302'd away and `RUSER` was never created. Fixed by adding the
  PART F cleanup (below) instead of weakening the assertions.
- Cleanup SQL failed with `ERROR 1137 (HY000) Can't reopen table: '_smoke_users'` — MySQL cannot
  reference the SAME temporary table twice in one statement. Fixed by using an ordinary table
  (`DROP TABLE IF EXISTS _smoke_users;` first, `DROP TABLE _smoke_users;` last).

Assumptions (viva notes):
- GeoJSON coordinates are WGS84 lon/lat; they are projected to the fixed SVG viewBox in Java with a
  `cos(midLat)` scale so the map keeps its real proportions.
- The heatmap is deliberately computed in Java (inverse-distance weighting) and drawn as SVG rects,
  not in the browser, because JavaScript is not allowed anywhere in this project.
- The crop "live" layer reads the crops stored on farmer profiles; it is sparse until more farmers
  register, so the page says so instead of pretending the data is complete.

Remaining: PART D (forecast details), PART E (usability pass), PART F (docs + final output).


---

## PART D — Forecast details (7-day cards, charts, day table) — DONE

Goal: turn `/forecast` from a plain list into a real 7-day outlook — one card per day with the
date, condition, min/max, rain probability or amount, humidity and wind, a plain-language farming
tip chosen by rules, plus a compact table view and two inline SVG charts. No JavaScript.

What was built:
- `WeatherController.forecast(...)` rewritten. It still reads ONLY from `weather_forecast` (via
  `WeatherDao.forecastForDistrict`), so the page never calls the internet.
  - De-duplication: a district watched by more than one block (taluka) returns several rows per day;
    a `LinkedHashMap<LocalDate, WeatherDay>` keeps the FIRST row per day so the outlook shows
    exactly `FORECAST_DAYS = 7` cards.
  - `ForecastCard` record: weekday (from `forecast.weekday.*`), date, condition key, max, min,
    rain value, rain label key, humidity, wind, tip key, source, sample flag.
  - `conditionKey(WeatherDay)` -> `forecast.cond.rainy` / `cloudy` / `clear`.
  - `tipKey(WeatherDay)` -> `forecast.tip.rain` / `lightrain` / `hot` / `cold` / `humid` / `windy`
    / `good`, applied in that priority order.
  - `rainIsAmount(String)` distinguishes `"0 mm"` (greenalerts, an amount) from `"19 %"`
    (Open-Meteo, a probability) so the label key is `forecast.rain_amount` or
    `forecast.rain_chance`. The stored value already carries its unit, so the UI can never show a
    percentage where the source published millimetres.
- `addChart(Model, List<WeatherDay>)`: all geometry is projected to SVG pixels IN JAVA, not in the
  browser. Sets `chartW=700`, `chartH=200`, `chartPad=36`, `chartBase=174`, `chartDays`,
  `chartSlot`, `tempMarks` (`DayMark` record: x, yMax, yMin, maxText, minText), `rainBars`
  (`RainBar` record: x, y, w, h, text), plus the ready-to-use `chartMaxPoints` / `chartMinPoints`
  polyline strings, `chartHigh`, `chartLow` and `chartRainMax`.
- `templates/pages/forecast.html` rewritten:
  - `tile-forecast-header` (span), `tile-forecast-picker` (GET district form + info-list + nowcast
    link), `tile-forecast-cards` (span) = a `.forecast-strip` of seven `.forecast-day` cards,
    `tile-forecast-chart` (span) = TWO `<svg class="chart">` (one with `polyline.chart-line--max`
    / `--min` + `circle.chart-dot`, one with `rect.chart-bar`; each has an SVG `<title>` for the
    hover text), `tile-forecast-table` (span) = `<table class="day-table">` with `<th scope="col">`
    and `<th scope="row">`.
  - The SAMPLE badge/note shows when `cards[0].sample()` is true, so seeded rows are never passed
    off as a real observation.
- `style.css`: new section `6j2. PART D: forecast charts, advice line and the day table` — reuses
  the existing `:root` variables; new classes only (`.forecast-day__cond`, `.forecast-tip`,
  `.chart`, `.chart-caption`, `.chart-legend`, `.chart-key--max/--min`, `.chart-axis`,
  `.chart-axis-label`, `.chart-line`, `.chart-dot`, `.chart-bar`, `.chart-xlabel`, `.day-table`).
- All four bundles (`messages.properties`, `_en`, `_hi`, `_mr`) got the new keys with real Hindi and
  Marathi: `forecast.max`, `forecast.rain_chance`, `forecast.rain_amount`,
  `forecast.cond.clear|cloudy|rainy`, `forecast.tip.rain|lightrain|hot|cold|humid|windy|good`,
  `forecast.advice`, `forecast.cards.title`, `forecast.table.title|day|condition|tip`,
  `forecast.chart.title|temp|rain|high|low`.

Files changed:
- `src/main/java/.../controller/WeatherController.java` (forecast method + `addChart`,
  `conditionKey`, `tipKey`, `rainIsAmount`, records `ForecastCard`/`DayMark`/`RainBar`).
- `src/main/resources/templates/pages/forecast.html` (rewritten).
- `src/main/resources/static/css/style.css` (section 6j2).
- `src/main/resources/messages*.properties` (all four).
- `smoke_test.sh` (new section 6b).

SQL: none. PART D adds no tables or columns.

Result: `./mvnw -q compile` EXIT 0. App on port 8094; `/forecast` (farmer1, en) -> 200 with
2 `class="chart"`, 2 `polyline`, 7 `class="chart-bar"`, 7 `.forecast-day`, 7 `.forecast-tip`,
1 `day-table`, and NO `??key??`. `smoke_test.sh http://localhost:8094` -> **115 passed, 0 failed**
(the 12 new section-6b checks all pass, including `/forecast [hi]` and `/forecast [mr]`).

Bug found and fixed:
- Two checks (`/map?district=pune` side panel, public `/schemes/pmkisan` after the owner edit) had
  started failing once section 6b existed. Root cause: `SecurityConfig.localeResolver()` is a
  `SessionLocaleResolver`, so fetching `/forecast?lang=hi|mr` with the farmer's jar stores `mr` in
  that HTTP session; every later `J_FARM` fetch without `?lang=` then rendered Marathi, so the
  English literals `>Pune<` and `smoke edit` were absent. This was a TEST bug, not an app bug — the
  two fetches now pass `?lang=en` explicitly, which also resets the session back to English.

Assumptions (viva notes):
- The chart is drawn on the server because JavaScript is not allowed; the browser only paints the
  `<svg>`, `<polyline>` and `<rect>` elements the Java code computed.
- A daily "condition" is derived from rain alone (>=5 mm or >=60 % -> rainy, >=1 mm or >=30 % ->
  cloudy, else clear): the sources do not publish a standard condition code.
- The farming tip is a rule of thumb (rain, heat, cold, wind, humidity), not an official advisory.

Remaining: PART E (usability pass), PART F (docs + final output).


---

## Reusable commands

### Missing-translation audit (STEP 0)
```bash
cd /home/prab/Prab/Prabjingo/mini_pjt
grep -rhoE "#\{[a-zA-Z0-9._-]+\}" src/main/resources/templates | sed 's/#{//;s/}//' | sort -u > /tmp/used.txt
for f in messages messages_en messages_hi messages_mr; do
  echo "== missing in $f =="
  grep -oE "^[a-zA-Z0-9._-]+" src/main/resources/$f.properties | sort -u > /tmp/def_$f.txt
  comm -23 /tmp/used.txt /tmp/def_$f.txt
done
```

### Compile
```bash
cd /home/prab/Prab/Prabjingo/mini_pjt && ./mvnw -q compile
```

### Apply the new SQL (STEP 2-4 tables/rows) — run once, re-runnable, never DROPs
```bash
cd /home/prab/Prab/Prabjingo/mini_pjt/src/main/resources
mysql -uadvisory -padvisory123 advisory_db < schema_new.sql
mysql -uadvisory -padvisory123 advisory_db < data_new.sql
```
Both files are safe to run again. `spring.sql.init.mode=never`, so the app never runs them
by itself.

### Restart the app
```bash
# Ctrl+C in the running terminal, then:
cd /home/prab/Prab/Prabjingo/mini_pjt && ./mvnw spring-boot:run
```
