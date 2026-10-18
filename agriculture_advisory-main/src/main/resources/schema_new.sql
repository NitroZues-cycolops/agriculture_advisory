-- =====================================================================
-- PURPOSE : ALL NEW DATABASE OBJECTS for the Precision Farming Advisory
--           Service, from STEP 2 onwards. Re-runnable by design:
--             * tables  -> CREATE TABLE IF NOT EXISTS   (never dropped)
--             * columns -> added only when they do not exist yet
--                          (MySQL 8 has no "ADD COLUMN IF NOT EXISTS",
--                           so each column is guarded by a query on
--                           information_schema + a prepared statement)
--           Safe to run again after every step; it never loses data.
-- HOW TO RUN (once, from the project root):
--   mysql -u advisory -padvisory123 advisory_db < src/main/resources/schema_new.sql
-- SEE ALSO : data_new.sql   (the sample rows that go with these tables)
-- If this breaks, check:
--   - "Access denied"            -> the advisory user/password in the -u/-p flags
--   - "Cannot add foreign key"   -> run this file AFTER the base schema.sql tables exist
--   - a column still missing     -> re-run this file; the guard only adds what is absent
-- =====================================================================

USE advisory_db;

-- ---------------------------------------------------------------------
-- 1. DISTRICTS (STEP 2 dropdown, reused by the STEP 4 SVG map)
--    36 districts of Maharashtra with approximate headquarters coordinates.
--    Names are stored in all three languages so the dropdown needs no i18n keys.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS districts (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    code     VARCHAR(40)  NOT NULL UNIQUE,   -- stable slug, used by ?district=code
    name_en  VARCHAR(60)  NOT NULL,
    name_hi  VARCHAR(60)  NOT NULL,
    name_mr  VARCHAR(60)  NOT NULL,
    lat      DOUBLE,                          -- APPROXIMATE district HQ latitude
    lon      DOUBLE                           -- APPROXIMATE district HQ longitude
);

-- ---------------------------------------------------------------------
-- 2. LAB STATUS HISTORY (STEP 5 tracker: one row per stage change)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lab_status_history (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT      NOT NULL,
    status     VARCHAR(50) NOT NULL,
    note       VARCHAR(255),
    changed_at DATETIME    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (request_id) REFERENCES lab_requests(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 3. FOLLOWS (STEP 6 "connect with a farmer")
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS follows (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    follower_id  BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_follow (follower_id, following_id),
    FOREIGN KEY (follower_id)  REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (following_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 4. NEW COLUMNS on tables that already exist
--    (guarded: added only when absent, so this file stays re-runnable)
-- ---------------------------------------------------------------------

-- labs.taluka - the lab address needs the taluka as well as the district
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'labs'
                AND COLUMN_NAME = 'taluka') = 0,
             'ALTER TABLE labs ADD COLUMN taluka VARCHAR(50)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- labs.status - PENDING until the owner approves it (STEP 2 + STEP 7)
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'labs'
                AND COLUMN_NAME = 'status') = 0,
             'ALTER TABLE labs ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''PENDING''',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- farmer_profiles.village
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'farmer_profiles'
                AND COLUMN_NAME = 'village') = 0,
             'ALTER TABLE farmer_profiles ADD COLUMN village VARCHAR(80)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- farmer_profiles.irrigation
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'farmer_profiles'
                AND COLUMN_NAME = 'irrigation') = 0,
             'ALTER TABLE farmer_profiles ADD COLUMN irrigation VARCHAR(50)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- farmer_profiles.preferred_lang - en / hi / mr chosen at registration
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'farmer_profiles'
                AND COLUMN_NAME = 'preferred_lang') = 0,
             'ALTER TABLE farmer_profiles ADD COLUMN preferred_lang VARCHAR(5)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- farmer_profiles.photo_path (PART 2a) - the absolute path of the farmer's
-- profile picture (a 256x256 JPG under <uploads>/avatars), NULL when none.
-- The file lives OUTSIDE static and is streamed back by GET /avatar/{userId}.
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'farmer_profiles'
                AND COLUMN_NAME = 'photo_path') = 0,
             'ALTER TABLE farmer_profiles ADD COLUMN photo_path VARCHAR(255) NULL',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- One profile row per user (PART 2a): the base schema.sql has no unique key on
-- farmer_profiles.user_id, but the photo upload is an UPSERT
-- (INSERT ... ON DUPLICATE KEY UPDATE photo_path) and MUST have a unique key to
-- key on. Guarded on information_schema.STATISTICS so re-running is safe.
SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'farmer_profiles'
                AND INDEX_NAME = 'uq_farmer_profile_user') = 0,
             'ALTER TABLE farmer_profiles ADD UNIQUE KEY uq_farmer_profile_user (user_id)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 5. WEATHER (STEP 4)
--    weather_config already exists. These two columns let one config row
--    drive the scraper and let the map colour a district by its newest row.
-- ---------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_config'
                AND COLUMN_NAME = 'source') = 0,
             'ALTER TABLE weather_config ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT ''GREENALERTS''',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_cache'
                AND COLUMN_NAME = 'source') = 0,
             'ALTER TABLE weather_cache ADD COLUMN source VARCHAR(30)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_cache'
                AND COLUMN_NAME = 'district') = 0,
             'ALTER TABLE weather_cache ADD COLUMN district VARCHAR(50)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_cache'
                AND COLUMN_NAME = 'taluka') = 0,
             'ALTER TABLE weather_cache ADD COLUMN taluka VARCHAR(50)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- weather_cache.config_id was NOT NULL; the scraper may write Open-Meteo rows
-- that have no config row, so the column is made nullable (re-runnable).
SET @s := IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_cache'
                AND COLUMN_NAME = 'config_id') = 'NO',
             'ALTER TABLE weather_cache MODIFY COLUMN config_id BIGINT NULL',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- STEP 4 stores each day of the 7-day forecast as its own row, so the scraper
-- can replace one day without rewriting the whole string.
CREATE TABLE IF NOT EXISTS weather_forecast (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    district    VARCHAR(50) NOT NULL,
    taluka      VARCHAR(50),
    forecast_day DATE       NOT NULL,
    temp_min    VARCHAR(10),
    temp_max    VARCHAR(10),
    rain_chance VARCHAR(10),
    humidity    VARCHAR(10),
    wind        VARCHAR(20),
    summary     VARCHAR(255),
    source      VARCHAR(30),
    updated_at  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_forecast (district, taluka, forecast_day)
);

-- ---------------------------------------------------------------------
-- 6. SCHEME TEXT (STEP 3) - the owner-editable, three-language content
--    One row per scheme per language. The `schemes` table keeps the
--    official link and the key used in the URL.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheme_text (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    scheme_id   BIGINT       NOT NULL,
    lang        VARCHAR(5)   NOT NULL,          -- en / hi / mr
    scheme_name VARCHAR(120) NOT NULL,
    what_text   TEXT,                            -- what the scheme is
    who_text    TEXT,                            -- who is eligible
    docs_text   TEXT,                            -- documents to carry
    steps_text  TEXT,                            -- how to apply, step by step
    where_text  TEXT,                            -- where to go
    UNIQUE KEY uq_scheme_lang (scheme_id, lang),
    FOREIGN KEY (scheme_id) REFERENCES schemes(id) ON DELETE CASCADE
);

-- schemes.scheme_key - short slug used in /schemes/{key}
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schemes'
                AND COLUMN_NAME = 'scheme_key') = 0,
             'ALTER TABLE schemes ADD COLUMN scheme_key VARCHAR(40)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schemes'
                AND COLUMN_NAME = 'sort_order') = 0,
             'ALTER TABLE schemes ADD COLUMN sort_order INT NOT NULL DEFAULT 0',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 7. DISTRICT OFFICE (STEP 3 "Find my district office" tile) - SAMPLE rows.
--    Kept in its own table so the owner can edit the address/office name.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS district_offices (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    district_code VARCHAR(40) NOT NULL UNIQUE,
    office_name   VARCHAR(150) NOT NULL,
    address       VARCHAR(255),
    note          VARCHAR(255)
);

-- ---------------------------------------------------------------------
-- 8. WEATHER SCRAPER LOG (STEP 4 `/debug` tile)
--    One row per watched URL. The @Scheduled job upserts it after every run,
--    so /debug can show "when did this URL last succeed" without digging in
--    the log file. Rows survive a failure on purpose: a failed run only
--    updates last_status/last_run_at, the old weather_cache data stays.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS weather_scrape_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    url             VARCHAR(255) NOT NULL UNIQUE,
    source          VARCHAR(30)  NOT NULL,
    last_status     VARCHAR(20)  NOT NULL,   -- OK / FALLBACK / FAILED
    last_message    VARCHAR(500),
    last_run_at     DATETIME,
    last_success_at DATETIME,
    rows_written    INT DEFAULT 0
);

-- weather_cache.state - the state this reading belongs to (always Maharashtra
-- today, but the column keeps the table honest if a second state is added).
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_cache'
                AND COLUMN_NAME = 'state') = 0,
             'ALTER TABLE weather_cache ADD COLUMN state VARCHAR(50)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- weather_cache.summary - the sky condition, e.g. "Partly cloudy".
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_cache'
                AND COLUMN_NAME = 'summary') = 0,
             'ALTER TABLE weather_cache ADD COLUMN summary VARCHAR(120)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- weather_cache.is_sample - 1 for the seeded offline demo rows, so the UI can
-- mark them SAMPLE (rule: never present seed data as a real observation).
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weather_cache'
                AND COLUMN_NAME = 'is_sample') = 0,
             'ALTER TABLE weather_cache ADD COLUMN is_sample TINYINT(1) NOT NULL DEFAULT 0',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- alerts.dedupe_key - the 3-hourly job must not pile up the same alert on
-- every run. A unique key on (user_id, dedupe_key) makes the insert idempotent
-- inside one day: the key carries the date, so a new alert appears tomorrow.
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alerts'
                AND COLUMN_NAME = 'dedupe_key') = 0,
             'ALTER TABLE alerts ADD COLUMN dedupe_key VARCHAR(120) NULL',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alerts'
                AND INDEX_NAME = 'uq_alert_dedupe') = 0,
             'ALTER TABLE alerts ADD UNIQUE KEY uq_alert_dedupe (user_id, dedupe_key)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 9. WEATHER SCRAPE RUN (PART A) - one row per REFRESH RUN (not per URL).
--    weather_scrape_log keeps "last outcome per watched URL"; this table
--    keeps the last N runs so /debug can show time, source, how many
--    districts were written and whether the run succeeded or failed.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS weather_scrape_run (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    trigger_type    VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED / CATCHUP / MANUAL
    source          VARCHAR(30),                               -- GREENALERTS / OPENMETEO / MIXED / FAILED
    districts_ok    INT          NOT NULL DEFAULT 0,
    districts_total INT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL,                     -- OK / FALLBACK / PARTIAL / FAILED
    message         VARCHAR(500)
);

-- ---------------------------------------------------------------------
-- 10. EMAIL VERIFICATION (PART B, now PART 1: email-only)
--     Account-level identity: the email and the (optional) mobile number live
--     on the `users` row so both can be checked for uniqueness in ONE place,
--     whichever role the account has.
--     NOTE: no UNIQUE key is put on users.phone because a lab may legitimately
--     share a landline; the optional mobile uniqueness rule is enforced by the
--     registration controller (server-side), not by the database.
--     PART 1 REMOVED the phone-OTP flow. The `phone_verification` table and the
--     `users.phone_verified` column are LEFT IN PLACE on purpose (they are no
--     longer read or written), so no data is dropped on an existing database.
-- ---------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                AND COLUMN_NAME = 'email') = 0,
             'ALTER TABLE users ADD COLUMN email VARCHAR(120) NULL',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                AND COLUMN_NAME = 'phone') = 0,
             'ALTER TABLE users ADD COLUMN phone VARCHAR(15) NULL',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                AND COLUMN_NAME = 'phone_verified') = 0,
             'ALTER TABLE users ADD COLUMN phone_verified TINYINT(1) NOT NULL DEFAULT 0',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                AND COLUMN_NAME = 'email_verified') = 0,
             'ALTER TABLE users ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 0',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- One email = one account. Multiple NULLs are allowed in MySQL, so accounts
-- created before this step are unaffected.
SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                AND INDEX_NAME = 'uq_users_email') = 0,
             'ALTER TABLE users ADD UNIQUE KEY uq_users_email (email)',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- One row per user: the phone OTP state. The code is stored HASHED (BCrypt),
-- never in clear; attempts/locked_until implement the 5-try / 15-min lockout;
-- window_start + sends_in_window implement "1 per minute, 5 per hour".
CREATE TABLE IF NOT EXISTS phone_verification (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    phone           VARCHAR(15)  NOT NULL,
    otp_hash        VARCHAR(100) NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    locked_until    DATETIME     NULL,
    expires_at      DATETIME     NOT NULL,
    verified_at     DATETIME     NULL,
    last_sent_at    DATETIME     NOT NULL,
    window_start    DATETIME     NOT NULL,
    sends_in_window INT          NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_phone_verif_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- One row per user: the email verification token. The token is stored as a
-- SHA-256 hex digest (deterministic, so the link can be looked up), never clear.
CREATE TABLE IF NOT EXISTS email_verification (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    email        VARCHAR(120) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    expires_at   DATETIME     NOT NULL,
    verified_at  DATETIME     NULL,
    last_sent_at DATETIME     NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_email_verif_user (user_id),
    KEY ix_email_verif_token (token_hash),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- PART 1 rate limit for a new email link ("1 per minute, 5 per hour"): the two
-- columns are added to an existing email_verification table run by a RE-RUNNABLE
-- guarded ALTER (checks information_schema first, so re-running this file is
-- safe on an already-migrated database).
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_verification'
                AND COLUMN_NAME = 'sends_in_window') = 0,
             'ALTER TABLE email_verification ADD COLUMN sends_in_window INT NOT NULL DEFAULT 0',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_verification'
                AND COLUMN_NAME = 'window_start') = 0,
             'ALTER TABLE email_verification ADD COLUMN window_start DATETIME NULL',
             'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- The random handle in the /verify/email?h=... link. It identifies the account
-- while the user is NOT logged in yet, and it is deleted once the email is
-- verified (or when the user skips the step).
CREATE TABLE IF NOT EXISTS verification_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    handle     VARCHAR(64) NOT NULL UNIQUE,
    user_id    BIGINT      NOT NULL,
    expires_at DATETIME    NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 11. DISTRICT CROPS (PART C) - the reference half of the crop map.
--     One row per district + crop. `is_sample=1` and a visible
--     "Sample data. Verify before use." note in the UI keep this honest:
--     no open district-wise crop dataset could be fetched (data.gov.in
--     needs an API key), so the seeded values are clearly marked SAMPLE.
--     The LIVE half of the crop map is computed from farmer_profiles.crops
--     and needs no table of its own.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS district_crops (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    district_code  VARCHAR(40)  NOT NULL,
    crop           VARCHAR(30)  NOT NULL,          -- a ReferenceData.CROPS code
    share_percent  DECIMAL(5,1) NOT NULL DEFAULT 0,
    source         VARCHAR(120),                   -- where the value came from
    year           INT,
    is_sample      TINYINT(1)   NOT NULL DEFAULT 1,
    UNIQUE KEY uq_district_crop (district_code, crop)
);

SELECT 'schema_new.sql applied' AS status;
