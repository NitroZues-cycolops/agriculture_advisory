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


## PART B — Registration + verification — SUPERSEDED by PART 1 (email-only)

> The phone-OTP design described in this section was removed in PART 1 (email-only verification);
> see the "PART 1 — Email-only verification" section at the end of this file. The bullets below are
> a short, corrected history of what PART B left in place.

- [x] Re-runnable SQL (`schema_new.sql` section 10): guarded ALTERs on `users`
      (`email VARCHAR(120) NULL`, `phone VARCHAR(15) NULL`, `email_verified TINYINT(1) NOT NULL
      DEFAULT 0`, unique key `uq_users_email`) plus the tables `email_verification` and
      `verification_session` (the old `phone_verification` table is left in place, unused - see PART 1).
      MySQL 8 has no `ADD COLUMN IF NOT EXISTS`, so each column is added via a prepared-statement
      guard that checks `information_schema.COLUMNS` first.
- [x] `data_new.sql` section 8: the existing demo users (farmer1/2/3, lab1, lab2, owner) are
      pre-filled with an email and marked `email_verified=1` so they keep working.
- [x] Registration collects email + an OPTIONAL mobile; the account is created with
      `email_verified=0` and the browser is sent to `/verify/email?h=<one-time handle>`.
- [x] Email verification: a random token (SHA-256 hashed in `email_verification`, expires
      `email.token.hours`) is delivered by the mail service, or shown in DEMO MODE when no SMTP server
      is configured.
- [x] Login is BLOCKED until the EMAIL is verified (`SecurityConfig.loginBlocked`); a disabled
      account is routed to `/login?disabled`.
- [x] Lab registration uses the same email rule and still waits for owner approval before login.

Superseded and removed in PART 1 (email-only verification): the `otp.*` properties, the
`OtpService` / `OtpSender` / `DemoOtpSender` / `SmsGatewayOtpSender` classes, the
`PhoneVerification` model, the `/verify/recover` route and the `users.phone_verified` column mapping.

Assumptions (viva notes):
- The verify link identifies the account by a random 32-hex handle (`verification_session`, expires
  `verification.handle.hours` = 24), never a user id typed into the URL.
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

---

## PART 1 — Email-only verification

Goal: make EMAIL the ONE verification step. Remove SMS / phone OTP completely and treat the mobile
number as an optional contact field.

Done (what changed):
- Deleted the whole phone-OTP stack: `service/OtpService.java`, `service/OtpSender.java`,
  `service/DemoOtpSender.java`, `service/SmsGatewayOtpSender.java`, `model/PhoneVerification.java`
  and `templates/pages/verify-phone.html`.
- `repository/VerificationDao.java`: removed the phone methods (`findPhone` / `startPhone` /
  `resendPhone` / `recordPhoneFailure` / `markPhoneVerified`) and the `PhoneVerification` mapper; the
  email + `verification_session` methods are kept.
- `model/User.java`: removed the `phoneVerified` field (and its `@Column(name="phone_verified")`
  mapping). The `users.phone_verified` column is left in the table on purpose (see assumptions).
- `controller/VerifyController.java`: only the email routes remain - `GET /verify/email`,
  `POST /verify/email/resend`, `GET /verify/email/confirm`, `POST /verify/skip`.
- `config/SecurityConfig.java`: the two helpers `labNotApproved(...)` / `phoneNotVerified(...)` are
  replaced by ONE documented `private static boolean loginBlocked(User u, LabDao labDao)`: a FARMER
  or LAB may log in only when the EMAIL is verified, a LAB additionally needs `labs.status='APPROVED'`,
  and OWNER is never blocked. A disabled account is routed to `/login?disabled` by the `failureHandler`.
- `application.properties`: deleted every `otp.*` property; kept `verification.handle.hours`,
  `email.token.hours`, `pfas.mail.from`; ADDED `email.resend.seconds=60`, `email.resend.per.hour=5` and
  (optional) `app.base-url`.
- `service/EmailVerificationService.java`: kept SHA-256 token hashing, 24 h expiry, demo mode and real
  sending; ADDED the resend rate limit (once per minute, 5 per hour) with DB-backed counters; the send
  path is `synchronized` with a TODO.
- Registration (`RegisterController`, `LabRegisterController`) and profile (`ProfileController`): the
  mobile/phone is now OPTIONAL - if given it must be exactly 10 digits starting 6-9, the error is shown
  next to the field and the typed value is kept. A duplicate-mobile error is only reported when a value
  was supplied. The profile shows the contact number as "Contact number (not verified)", and only on
  the user's OWN profile.
- `schema_new.sql`: added guarded, re-runnable ALTERs on `email_verification` for the rate-limit columns
  `sends_in_window INT NOT NULL DEFAULT 0` and `window_start DATETIME NULL` (each guarded by an
  `information_schema.COLUMNS` check). `VerificationDao` gained the read/update methods for them.
- Messages: the OTP/phone/recover keys were deleted from all four bundles; the whole `verify.email.*`
  family plus `profile.contact.*` are present in `messages.properties` (base), `messages_en`,
  `messages_hi` and `messages_mr` with real Hindi and Marathi.
- Docs: README.md gained an "Email setup" section (Mailtrap + Gmail App Password, the SMTP env vars,
  `app.base-url`) and loses the SMS/DLT wording; `.gitignore` ignores `.env` and local config files;
  DEBUG_GUIDE.md has new email-verification troubleshooting rows and no phone/OTP rows; `smoke_test.sh`
  sections 3 / 3b / 4 / 12 were rewritten for email-only (with 4 new link-rule checks).

Files changed: `SecurityConfig.java`, `RegisterController.java`, `LabRegisterController.java`,
`ProfileController.java`, `VerifyController.java`, `User.java`, `UserRepository.java`,
`VerificationDao.java`, `EmailVerificationService.java`, `schema_new.sql`, `application.properties`,
`register.html`, `register-lab.html`, `profile.html`, `verify-email.html`, `verify-email-done.html`,
`login.html`, `messages.properties`, `messages_en.properties`, `messages_hi.properties`,
`messages_mr.properties`, `README.md`, `DEBUG_GUIDE.md`, `smoke_test.sh`, `.gitignore`.

Files deleted: `OtpService.java`, `OtpSender.java`, `DemoOtpSender.java`, `SmsGatewayOtpSender.java`,
`PhoneVerification.java`, `verify-phone.html`.

SQL added (guard example, repeated for each column):
```sql
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_verification'
                AND COLUMN_NAME = 'sends_in_window') = 0,
             'ALTER TABLE email_verification ADD COLUMN sends_in_window INT NOT NULL DEFAULT 0',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
-- same guarded block for: window_start DATETIME NULL
```

Assumptions (viva notes):
- The old `phone_verification` table and the `users.phone_verified` column are left in place on
  purpose: the app no longer reads or writes them, and the hard rule for this project is "never DROP".
  Removing them is a later cleanup.
- Compile/run verification is DEFERRED: this machine has NO JDK, NO Maven, NO MySQL, NO Git and NO
  Bash, so nothing was compiled, run or SQL-tested. Every change was verified by READING the files and
  by GREP only (missing-key audit, `??key??` scan, `th:action` scan, deleted-class reference scan).

---

## PART 2a — Profile page, picture and avatar

Goal: a farmer can see/edit their whole profile with per-field errors, add or remove a profile
picture, and have that picture (or a coloured initials fallback) shown everywhere their name appears -
with NO JavaScript and NO new look.

Done (what changed):
- `/profile`: the existing 3-tile layout is kept. The account tile keeps its Email verified/not-verified
  badge and gains a "Send verification email" button that POSTs to the EXISTING resend endpoint
  (`/verify/email/resend`) with the one-time handle the controller now creates for the page. Farm
  fields, crops, area, soil, irrigation and description all keep their per-field errors and typed
  values on failure; success shows a banner.
- Profile picture: `POST /profile/photo` (multipart, CSRF) accepts JPG/PNG only, decided by MAGIC BYTES
  (a text file renamed `.png` is refused), rejects > 2 MB, reads width/height with an `ImageReader`
  BEFORE decoding and refuses > 8000 px a side or > 20,000,000 px (friendly per-field error, never a
  500), then flattens onto WHITE, centre-crops to a square, resizes to 256x256 and saves a JPG under
  `<uploads>/avatars` (OUTSIDE static) with a random name. `POST /profile/photo/remove` (CSRF) clears
  the picture and deletes the file. The save is an UPSERT, so a freshly registered farmer with NO farm
  details can still upload.
- `GET /avatar/{userId}` (login required): streams the stored JPEG, else a server-rendered SVG initials
  avatar (colour from the name). A blank or unknown name never becomes a 500.
- One shared helper `util/Initials` (a Spring bean `@Component("initials")`): `of(name)` is code-point
  aware (handles null / blank / extra spaces / non-BMP / emoji, returns `"?"` when empty and never
  throws) and `colorFor(name)` maps a name deterministically to one palette colour. Used by the SVG
  avatar AND by the CSS initials circle in templates (`${@initials.of(name)}` / `${@initials.colorFor(name)}`).
- Avatars shown on: `/profile`, community posts, comments, the community member/connection lists, a
  farmer's community profile and every chat message, via ONE fragment `fragments/avatar.html`
  (`avatar(id, name, hasPhoto)`). "Add photo" prompt when there is no picture, "Remove photo" when
  there is.

Files changed:
- NEW `src/main/java/com/.../util/Initials.java` — shared initials + colour helper (bean `initials`).
- NEW `src/main/java/com/.../service/ProfilePhotoService.java` — validate/crop/resize/store the upload; never throws.
- NEW `src/main/java/com/.../controller/AvatarController.java` — `GET /avatar/{userId}` (JPEG or SVG fallback).
- NEW `src/main/resources/templates/fragments/avatar.html` — the one avatar fragment (`img` or initials circle).
- `schema_new.sql` — guarded, re-runnable ALTERs for `farmer_profiles.photo_path` and the `user_id` unique key (SQL below).
- `repository/FarmerProfileDao.java` — maps `photo_path`; adds `upsertPhoto` (INSERT ... ON DUPLICATE KEY UPDATE `photo_path` only), `clearPhoto`, `idsWithPhoto()`.
- `controller/ProfileController.java` — `POST /profile/photo`, `POST /profile/photo/remove`; `verifyHandle` for the resend button; exposes `photoPath`/`hasPhoto`.
- `controller/CommunityController.java` — adds the `withPhoto` model attribute (`idsWithPhoto()`) to the community pages so the fragment can decide `img` vs initials without changing the `Member` record.
- `model/FarmerProfile.java` — adds the nullable `photoPath` field + getter/setter.
- `templates/pages/profile.html` — picture block with upload/remove forms (both `th:action`) + saved/removed banners + the resend button.
- `templates/pages/community.html`, `community-post.html`, `community-thread.html`, `community-inbox.html`, `community-profile.html` — avatar fragment next to names.
- `static/css/style.css` — new `6k-2. AVATARS (PART 2a)` block only (`.avatar`, `.avatar--initials`, `.with-avatar`, `.profile-photo*`, `.page-head__avatar`).
- `messages.properties`, `messages_en.properties`, `messages_hi.properties`, `messages_mr.properties` — 11 new keys in ALL FOUR bundles (real Hindi and Marathi).
- `application.properties` — ADDED (datasource untouched, `spring.sql.init.mode` still `never`) two multipart properties so the code-level 2 MB rule is reachable (see note below).
- `smoke_test.sh` — new section `13b. PART 2a` + pure-bash PNG builders (no ImageMagick assumed).

New message keys (English values; identical keys in all four bundles):
```
avatar.alt=Profile picture
profile.photo.label=Profile picture
profile.photo.hint=JPG or PNG, up to 2 MB. Your picture is cropped to a square.
profile.photo.upload=Save photo
profile.photo.remove=Remove photo
profile.photo.add=Add a photo so other farmers recognise you.
profile.photo.saved=Your profile picture has been saved.
profile.photo.removed=Your profile picture has been removed.
error.image.dimensions=This image is too large. Use an image up to 8000 x 8000 pixels.
error.image.corrupt=This image could not be read. Please try another file.
error.image.save=The picture could not be saved. Please try again.
```
(`error.image.type` and `error.image.size` already existed and are REUSED, not duplicated.)

SQL added to `schema_new.sql` (guarded, re-runnable, never DROPs) — run once:
```sql
-- farmer_profiles.photo_path (PART 2a)
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'farmer_profiles'
                AND COLUMN_NAME = 'photo_path') = 0,
             'ALTER TABLE farmer_profiles ADD COLUMN photo_path VARCHAR(255) NULL',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- one profile row per user (the photo upsert needs a unique key to key on)
SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'farmer_profiles'
                AND INDEX_NAME = 'uq_farmer_profile_user') = 0,
             'ALTER TABLE farmer_profiles ADD UNIQUE KEY uq_farmer_profile_user (user_id)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
```

### Apply the new SQL (PART 2a) — run once, re-runnable, never DROPs
```bash
cd src/main/resources
mysql -uadvisory -padvisory123 advisory_db < schema_new.sql
```

Smoke checks added (`smoke_test.sh`, section `13b. PART 2a`):
- A valid PNG → 302, the profile row is CREATED by the UPSERT, and `GET /avatar/$UID` returns 200 with
  JPEG magic `ffd8ff` and exactly `256x256`.
- A transparent PNG uploads and its served JPEG equals the all-white upload and differs from the
  all-black one (proves the WHITE flatten, not black).
- `POST /profile/photo/remove` clears `photo_path`.
- A text file renamed `.png` → 200 re-render with "Use a JPG or PNG image" and the stored path unchanged.
- A file over 2 MB → 200 re-render with "2 MB or smaller" and the stored path unchanged.
- A tiny file claiming 9000x9000 → 200 with NO whitelabel/500 and the stored path unchanged.
- A blank name still returns 200 with an `<svg` body from `/avatar`; an unknown id `999999999` also 200.

Assumptions (viva notes):
- `Initials` is a Spring bean, so templates call it as `${@initials.of(name)}` / `${@initials.colorFor(name)}`
  and the SVG controller injects the same instance. No `T(...)` type expressions are used anywhere in
  this project, so none were introduced.
- The `Member` record is built POSITIONALLY by `CommunityDao` and was NOT changed; instead a `withPhoto`
  set is passed to every community page (also avoids one query per row).
- `application.properties`: no multipart limits existed, so Spring Boot's default 1 MB file cap would
  have rejected a 2 MB upload with a 500 BEFORE the controller could show the friendly message. Two
  properties were ADDED (datasource unchanged, `spring.sql.init.mode` stays `never`) so the code-level
  2 MB rule is the effective limit: `spring.servlet.multipart.max-file-size=5MB` and
  `spring.servlet.multipart.max-request-size=6MB`.
- The spec mentions "/farmers cards", but this project has NO `/farmers` route or farmers-cards page
  (only a community member/connection list, a farmer's community profile, and an owner-side farmer
  COUNT). Avatars were added to every place a farmer's NAME is actually rendered; there was nothing
  at `/farmers` to attach them to.
- The stored file lives OUTSIDE `src/main/resources/static` (under `<uploads>/avatars`, the same root
  the community photo feature uses) and is served back only through `AvatarController`.
- Compile/run verification is DEFERRED: this machine has NO JDK, NO Maven, NO MySQL, NO Git and NO
  Bash, so nothing was compiled, run or SQL-tested. Everything was verified by READING files and by
  GREP / static checks only (see the five cross-checks in the task report).

---

## PART 2b — Managing my own content

Goal: a farmer can manage what they created - see their own posts on `/profile`, edit a post (text +
photo), and delete a post / comment / chat message through a confirmation PAGE (never a GET that
deletes), all ownership-checked, with NO JavaScript and NO new look.

Done (what changed):
- `/profile` gains a **My posts** tile: the farmer's OWN community posts, newest first, each with
  **Edit** and **Delete** links (empty state: `profile.my_posts.empty`).
- **Edit my own post** - `GET /community/post/{id}/edit` shows a form pre-filled with the text and the
  current photo; `POST /community/post/{id}/edit` re-validates exactly like posting (non-empty,
  length <= 1000, per-field error, typed value kept) and lets the author REPLACE the photo (a new
  file) or REMOVE it (a checkbox). The replaced / removed file is deleted from disk. Ownership: the
  author, or an OWNER; anyone else -> 403.
- **Delete my own post** - `GET /community/post/{id}/delete` is a confirmation PAGE that SHOWS the
  post (and warns the comments/votes go too); `POST /community/post/{id}/delete` (CSRF) performs it.
  Deleting removes the comments and votes (DB `ON DELETE CASCADE`) and deletes the photo FILE from
  disk. The old OWNER-only delete was broadened so the AUTHOR can delete too, and the OWNER keeps the
  power to moderate ANY post.
- **Delete my own comment** - `GET /community/comment/{id}/delete` (confirm page) +
  `POST /community/comment/{id}/delete`. Author or OWNER; anyone else -> 403.
- **Delete my own chat message** - `GET /community/message/{id}/delete` (confirm page) +
  `POST /community/message/{id}/delete`. SENDER only; anyone else -> 403. It is a **SOFT delete**: the
  text is cleared so BOTH people still see the bubble as "This message was deleted" in the thread and
  the inbox (documented choice: a placeholder, not a vanished row).
- **Unfollow anyone** - `POST /community/unfollow/{id}` already existed and is ownership-safe (the
  follower id comes from the security context, never from the URL), so no change was needed.
- Ownership is enforced by one shared gate: `requireAuthorOrOwner(...)` (author OR OWNER) for posts and
  comments, `requireSender(...)` (sender only) for chat messages. The user id always comes from
  `Authentication`, never from the URL.
- Feed / post / own community-profile tiles now show **Edit** and **Delete** links (to the confirm
  pages) for the author and for the OWNER; comments get a per-comment **Delete** link; chat messages
  get a **Delete** link on the sender's own bubbles.

Files changed:
- `src/main/java/.../repository/CommunityDao.java` — adds `updatePost`, `findComment`, `deleteComment`,
  `findMessage` and a SOFT `deleteMessage` (clears `content`).
- `src/main/java/.../controller/CommunityController.java` — the edit/delete-confirm routes for posts,
  comments and messages; adds `requireAuthorOrOwner`, `requireSender`, `deleteImageQuietly` helpers; the
  single-post GET now exposes `me`.
- `src/main/java/.../controller/ProfileController.java` — injects `CommunityDao` and adds `myPosts` to
  the `/profile` model.
- `src/main/resources/templates/pages/profile.html` — the **My posts** tile.
- `src/main/resources/templates/pages/community.html` — Edit/Delete links for the author and OWNER.
- `src/main/resources/templates/pages/community-post.html` — Edit/Delete for the post + per-comment Delete.
- `src/main/resources/templates/pages/community-thread.html` — per-message Delete (sender only) + the
  "This message was deleted" placeholder.
- `src/main/resources/templates/pages/community-inbox.html` — the same placeholder for a blank last message.
- `src/main/resources/templates/pages/community-profile.html` — Edit/Delete on your own profile's posts.
- NEW `src/main/resources/templates/pages/community-post-edit.html` — the edit form.
- NEW `src/main/resources/templates/pages/community-post-delete.html` — the post confirm page.
- NEW `src/main/resources/templates/pages/community-comment-delete.html` — the comment confirm page.
- NEW `src/main/resources/templates/pages/community-message-delete.html` — the message confirm page.
- `src/main/resources/messages.properties`, `messages_en.properties`, `messages_hi.properties`,
  `messages_mr.properties` — 19 new keys in ALL FOUR bundles (real Hindi and Marathi).
- `smoke_test.sh` — new section `13c. PART 2b` (10 checks).
- `PROGRESS.md` — this section.

New message keys (English values; identical keys in all four bundles):
```
common.cancel=Cancel
community.edit=Edit
community.edit.title=Edit your post
community.edit.submit=Save changes
community.edit.current=Current photo
community.edit.replace=Replace the photo (optional)
community.edit.remove=Remove the current photo
community.post.delete.title=Delete this post?
community.post.delete.body=This post, all its comments and its votes will be removed for everyone. This cannot be undone.
community.post.delete.note=The photo attached to this post is deleted too.
community.comment.delete.title=Delete this comment?
community.comment.delete.body=Your comment will be removed from the post. This cannot be undone.
community.message.delete.title=Delete this message?
community.message.delete.body=The message will be replaced by "This message was deleted" for both of you.
community.message.deleted=This message was deleted.
community.delete.confirm=Yes, delete
profile.my_posts=My posts
profile.my_posts.intro=Everything you have shared in the community. You can edit or delete your own posts here.
profile.my_posts.empty=You have not posted anything in the community yet.
```
(`community.delete`, `community.back`, `community.error.content`, `error.too.long`, `error.image.type`,
`community.new.content`, `community.new.image.hint`, `community.image.alt`, `common.back*` already
existed and are REUSED, not duplicated.)

SQL: **none.** PART 2b adds no tables and no columns. Deleting a post relies on the existing
`ON DELETE CASCADE` on `community_comments.post_id` and `community_votes.post_id`; the chat-message
delete is an `UPDATE` (`direct_messages.content = ''`). The photo FILE on disk is deleted by the
controller (`deleteImageQuietly`).

Smoke checks added (`smoke_test.sh`, section `13c. PART 2b`):
- created own post #… with a photo.
- edited my own post -> 302 and the text in the DB is updated.
- farmer2 -> GET **and** POST edit someone's post -> 403.
- a GET on `.../delete` shows the confirm page and deletes NOTHING (row still present); a POST without
  CSRF -> 403 and the row is unchanged.
- farmer2 -> delete someone's comment -> 403; deleting my own comment -> 302 and the row is gone.
- deleting my own post -> 302 with its comments and votes cascaded (all three counts 0) AND the photo
  file removed from disk (the stored absolute path no longer exists).
- deleting my own chat message -> 302 and its content is cleared (soft delete); the receiver ->
  delete my message -> 403.

**OPTIONAL "Delete my account" — SKIPPED.** It was the last, optional item and there was not enough
room to build it safely (a password-confirmation confirm page, a full cascade over profiles / posts /
comments / votes / follows / messages / photo files / verification rows, a last-OWNER guard, session
logout and a goodbye page). Per the task it is deliberately NOT implemented, so nothing half-done is
left behind.

Assumptions (viva notes):
- Deleting a chat message is a SOFT delete (clear the text) so the conversation still makes sense to
  both people; the thread and the inbox render "This message was deleted." where the content is blank.
- The confirmation pattern is always: a GET renders the confirm PAGE (it only SHOWS what will be
  removed) and the "Yes, delete" button is the POST that does the work - no JavaScript, no GET deletes.
- Compile/run verification is DEFERRED: this machine has NO JDK, NO Maven, NO MySQL, NO Git and NO
  Bash, so nothing was compiled, run or SQL-tested. Every change was verified by READING the files and
  by GREP / static checks only (the five cross-checks below).

## PART 3 — Connections and chat

Goal: a farmer can find other farmers, follow them, see who they are connected with, and have a private
conversation - with an unread badge in the top bar, clear rules about who may chat, and limits on what
a message may contain and how fast it may be sent. NO JavaScript: every action is a link or a POST form.

Done (what changed):
- **Find farmers** - `GET /farmers` is a directory of every other farmer (name search + district
  filter, both plain GET params so the URL is shareable). Each card is the shared **person card**
  (`fragments/person :: card`): avatar, name, district, taluka, crops, plus **View profile**, **Chat**
  and **Follow / Unfollow**. It NEVER shows another user's phone or email.
- **Inbox** - `GET /messages` lists one row per farmer you have exchanged messages with, newest first,
  with the last message, its time and an "N unread" tag. Empty state: `messages.inbox.empty`.
- **Thread** - `GET /messages/{id}` shows both sides of the conversation, oldest at the top, my own
  bubbles to the right, and a send form. Opening it **marks the incoming messages read**. There is no
  live update, so a visible **Refresh** link re-requests the page (no JavaScript).
- **Send** - `POST /messages/{id}` validates the text (1..1000 characters; empty or over-long is
  refused with the message KEPT and the error beside the field) and enforces a **20-messages-per-minute**
  rate limit. On success it redirects back to the thread.
- **Who may chat** - a farmer may chat with anyone they FOLLOW **or** who FOLLOWS them
  (`CommunityDao.canChat`, one-way is enough). Opening or posting to a non-connected farmer -> the
  friendly **"You cannot message this farmer yet"** page (`messages-blocked.html`) with HTTP **403**
  and a Follow button. A thread with **yourself** redirects to the inbox (GET) and is refused on POST (403).
- **Unread badge** - `config/UnreadBadgeAdvice` is a `@ControllerAdvice` that puts `unreadCount` (one
  cheap `COUNT`) into every model; the top bar shows a **Messages** link with an unread badge
  (`fragments/topbar.html` + the new `.nav-badge` rule in `style.css`). Anonymous users always see 0.
- **Connections** - `/profile` gains a **Connections** tile: **Following**, **Followers** and
  **Connections** (a connection = a MUTUAL follow), each rendered with the shared person card.
- **Route clean-up** - the canonical chat lives at `/messages`; every `/community/messages` link was
  repointed, the orphaned `community-inbox.html` / `community-thread.html` were DELETED, and the old
  dead inbox link on `/community` now points at `/messages` (with the live unread tag).

Files changed:
- `src/main/java/.../controller/CommunityController.java` — the `/messages` (inbox, thread, send) and
  `/farmers` routes; `MAX_CONTENT = 1000`, `RATE_LIMIT = 20`; `messages-blocked.html` on a forbidden chat.
- `src/main/java/.../repository/CommunityDao.java` — `following`, `followers`, `connections` (mutual),
  `canChat`, `searchFarmers`, `inbox` (with per-row unread), `thread`, `insertMessage`, `markRead`,
  `unreadCount`, `sentInLastMinute`, `displayName`, and `findMember`.
- NEW `src/main/java/.../config/UnreadBadgeAdvice.java` — the `unreadCount` model attribute (0 for anonymous).
- `src/main/java/.../controller/ProfileController.java` — adds `followingList`, `followerList`,
  `connectionList`, their counts and `followingIds` to the `/profile` model.
- `src/main/resources/templates/pages/messages-inbox.html`, `messages-thread.html`,
  `messages-blocked.html`, `farmers.html` — the PART 3 pages.
- NEW `src/main/resources/templates/fragments/person.html` — the reusable farmer card (no phone/email).
- `src/main/resources/templates/pages/profile.html` — the **Connections** tile.
- `src/main/resources/templates/fragments/topbar.html` — the Messages link with the unread badge.
- `src/main/resources/templates/pages/community.html`, `community-post.html`, `community-profile.html`,
  `community-message-delete.html` — message links repointed to `/messages` (+ "Find farmers" link).
- `src/main/resources/static/css/style.css` — the new `.nav-badge` rule (section 7b; no existing rule changed).
- DELETED `src/main/resources/templates/pages/community-inbox.html` and `community-thread.html` (superseded).
- `src/main/resources/messages.properties`, `messages_en.properties`, `messages_hi.properties`,
  `messages_mr.properties` — 31 new keys in ALL FOUR bundles (real Hindi and Marathi). All four bundles
  now hold the SAME 614 keys (checked: identical key sets, no duplicates).
- `smoke_test.sh` — new section `13d. PART 3` (17 checks) + the direct-message URL moved to `/messages`.
- `PROGRESS.md` — this section.

New message keys (English values; identical keys in all four bundles):
```
messages.title=Messages
messages.inbox.empty=No messages yet. Find a farmer to chat with.
messages.inbox.unread={0} unread
messages.inbox.open=Open
messages.thread.title=Conversation with {0}
messages.thread.refresh=Refresh
messages.thread.empty=No messages yet - write the first one.
messages.thread.you=You
messages.thread.type=Your message
messages.thread.send=Send
messages.blocked.title=You cannot message this farmer yet
messages.blocked.body=Follow {0} to start a conversation. You can chat with the farmers you follow, or with farmers who follow you.
messages.blocked.back=Back to messages
messages.send.empty=Please write a message first.
messages.send.long=Your message is too long (1000 characters at most).
messages.send.rate=You are sending messages too quickly. Please wait a minute and try again.
farmers.title=Find farmers
farmers.intro=Search for other farmers and follow them, then start a conversation from the Messages page.
farmers.link=Find farmers
farmers.search.label=Name
farmers.district.label=District
farmers.district.all=All districts
farmers.search.submit=Search
farmers.empty=No farmers match your search.
farmers.count={0} farmer(s)
person.view=View profile
person.chat=Chat
profile.connections=Connections
profile.following=Following
profile.followers=Followers
profile.connections.intro=The farmers you are connected with. You can chat with anyone you follow, or who follows you.
profile.connections.empty=You have not connected with anyone yet. Use Find farmers to follow someone.
```

SQL: **none.** PART 3 adds no tables and no columns. It reuses the existing `follows` and
`direct_messages` tables (`direct_messages.is_read` drives the badge; `created_at >= NOW() - INTERVAL 1
MINUTE` drives the rate limit). Nothing is DROPped.

Smoke checks added (`smoke_test.sh`, section `13d. PART 3`, 17 checks):
- `/farmers` -> 200 with no missing keys and lists another farmer; the name search filters (an impossible
  name shows the empty state); the district filter round-trips.
- farmer2 writes to me -> stored and **unread** for me.
- `/messages` -> 200, no missing keys, lists the conversation.
- the top bar shows the **unread badge** while a message is unread.
- opening the thread -> 200, no missing keys, its messages are marked read and the badge **disappears**.
- a non-connected farmer's thread -> **403** (blocked page); opening my OWN thread -> **302**; POSTing to
  myself -> **403**; POSTing to a non-connected farmer -> **403**.
- an empty message and a 1000+ character message are each refused (200 with an error, **no row stored**).
- the 20-per-minute ceiling is reached and the 21st message is refused (no row stored).
- a mutual follow is a **connection** (1 row) and `/profile` shows the **Connections** tile with the
  connected farmer; chat permission is symmetric (a follower may open my thread -> 200).

Assumptions (viva notes):
- **Who may chat** is deliberately one-way: follow someone, OR be followed by them, and you may chat.
  A **connection** (for the profile tile) is stricter: BOTH directions must follow.
- The **rate limit** needs no extra table: "how many did this user send in the last minute" is one
  indexed `COUNT` over `direct_messages`.
- The **unread badge** is a single `COUNT` per request via `@ControllerAdvice`; it is 0 for anonymous
  visitors and never breaks a page if a user is not logged in.
- Reading a thread is what clears the badge (opening the conversation == reading it); there is no
  JavaScript "mark as read".
- The old `/community/inbox` and `/community/thread` templates were deleted because the canonical chat
  is `/messages`; leaving them would be dead, unmaintained duplicates.
- Compile/run verification stays DEFERRED on this machine (no JDK / Maven / MySQL / Git / Bash):
  PART 3 was verified by READING the files and by GREP / static checks only.

## PART 4 — Email robustness and concurrency

Goal: the verification email must behave correctly when two requests arrive at once, must never hang or
return a 500 when the mail server is slow or down, and must build a link that points at a host the
reader can actually open. NO JavaScript, no datasource change, no new tables.

Reproduce first (what was already true): the request-derived base URL (`ServletUriComponentsBuilder`),
the reused-link **"already verified"** message, the unknown-token **"not valid"** message and the
never-500 demo fallback were ALL already correct from PART 1 — nothing was changed there.

Done (what changed):
- **Removed `synchronized`** from `EmailVerificationService.send`. The class-level lock only helped a
  single instance and blocked every user during a send.
- **Atomic rate limit.** The check ("may this account get a new link now?") and the record (write the
  token + bump `last_sent_at` / `sends_in_window`) now run together inside **one short database
  transaction** that locks the account's row with **`SELECT ... FOR UPDATE`** (new
  `VerificationDao.lockEmail`). Two simultaneous resends are serialised by the database, so only the
  first is accepted (the second sees the fresh `last_sent_at` and is refused) — and the rule now holds
  across **several app instances**, which `synchronized` never could.
- **SMTP outside the transaction.** `JavaMailSender.send` runs **after** the transaction has committed.
  A slow or dead mail server therefore never holds a database lock and never delays other users. A
  `RuntimeException` from the mail layer is caught, logged without secrets, and the link is shown on
  the page instead — so a mail failure is **never a 500** and the account can still be verified.
- **Honest failure message.** `Dispatch` gained a `sendFailed` flag, so a send that was ATTEMPTED and
  failed is no longer reported as "The verification link has been sent." The resend page then shows a
  new **warning** notice (`verify.email.send_failed`, in the existing `.alert--warning` style) that says
  the email could not be sent, while the link and the resend button stay on the page. Plain demo mode
  (no SMTP configured) still shows the normal green notice.
- **SMTP timeouts.** Added (as properties ONLY) `spring.mail.properties.mail.smtp.connectiontimeout`,
  `.timeout` and `.writetimeout` = **5000 ms**, so a hanging mail server fails fast instead of blocking
  a request for minutes.
- **Base-URL sanity check.** A one-off `ApplicationReadyEvent` listener logs a clear **warning** when a
  real mail server is configured (`spring.mail.host` set) but `app.base-url` is blank, because the link
  would then be built from whatever host the request used (usually wrong behind a proxy). Booting is
  never blocked for the college demo.

Files changed:
- `src/main/java/.../service/EmailVerificationService.java` — `send` is no longer `synchronized`; the
  check + record run in a `TransactionTemplate` over `dao.lockEmail(...)`; the SMTP send is outside;
  `Dispatch` gains a `sendFailed` flag; new private `Attempt` record and the `warnIfBaseUrlMissing`
  start-up listener; class Javadoc updated.
- `src/main/java/.../repository/VerificationDao.java` — NEW `lockEmail(userId)` (`SELECT ... FOR UPDATE`).
- `src/main/java/.../controller/VerifyController.java` — the resend flow redirects to `&r=MAIL_FAILED`
  when the send failed and puts `noticeWarn` in the model so the page styles it as a warning.
- `src/main/resources/templates/pages/verify-email.html` — the notice box switches to the existing
  `.alert--warning` class and a warning icon for a failed send (no new CSS).
- `src/main/resources/application.properties` — **ADDED** the three SMTP timeout properties (datasource
  untouched; `spring.sql.init.mode` stays `never`).
- `src/main/resources/messages.properties`, `messages_en.properties`, `messages_hi.properties`,
  `messages_mr.properties` — **1 new key** (`verify.email.send_failed`) in ALL FOUR bundles with real
  Hindi and Marathi; all four now hold the SAME **615 keys** (checked: identical counts, no duplicates).
- `smoke_test.sh` — new section `3c. PART 4` (5 checks) + the header note.
- `PROGRESS.md` — this section.

SQL: **none.** PART 4 adds no table and no column; the row lock uses the existing
`email_verification` row (`UNIQUE KEY uq_email_verif_user (user_id)` — the lock target). Nothing is DROPped.

SAMPLE / no invented facts: the new notice only states that the email could not be sent; it makes no
claim about delivery and adds no external data.

Smoke checks added (`smoke_test.sh`, section `3c. PART 4`, 5 checks):
- a fresh unverified account is created for the concurrency test.
- two resends fired at the **same time** -> **exactly one** is accepted and there is exactly one
  `email_verification` row (the once-a-minute rule holds under concurrency).
- the accepted reply's **demo link is absolute and uses the request's own host:port** (proof it is
  built from the request, never hard-coded).
- static guards: `EmailVerificationService` no longer contains `synchronized`; `VerificationDao`
  contains `SELECT ... FOR UPDATE`; `application.properties` sets all three SMTP timeouts.

Assumptions (viva notes):
- Why a **database** lock and not `synchronized`? A JVM lock only guards one process; the rule must
  also hold when a second instance answers the same request (load balancer / restart overlap). The
  `SELECT ... FOR UPDATE` on the row is the one place all instances agree on.
- Why is the SMTP call **outside** the transaction? Sending mail is I/O that can take seconds or fail;
  holding a row lock for that long would serialise unrelated users and risk lock-timeout errors. The
  transaction is tiny (read a row, write a row) and commits before any network call.
- Why does a mail failure still show the link? It is the friendlier of the two safe outcomes: no 500,
  no lost account. With SMTP configured this only happens when delivery fails; the user may also ask
  for another link once the minute passes.
- The row-lock read on a **missing** row still takes the index gap lock, so a genuine first send is
  serialised too — no special-casing of "no row yet".
- Compile/run verification stays DEFERRED on this machine (no JDK / Maven / MySQL / Git / Bash):
  PART 4 was verified by READING the files and by GREP / static checks only.
