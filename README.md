# Precision Farming Advisory Service

A college mini-project for the farmers of Maharashtra, India. It gives a weather
nowcast and 7-day forecast, a soil/water lab test tracker with PDF reports,
simple government-scheme guides, a farmer community and an owner admin panel.
Everything is available in **English, Hindi and Marathi**.

- **Stack:** Spring Boot 3.3.4, Java 21, Thymeleaf, Spring Security, Spring Data JPA
  (one entity) + `JdbcTemplate` DAOs, MySQL 8, OpenPDF (reports), Jsoup (scraper), Lombok.
- **No JavaScript anywhere.** The UI is HTML + CSS only (a Hyprland-style CSS-Grid tiled
  layout). Every form is a real `<form th:action>` so Spring Security's CSRF token is included.

---

## 1. Prerequisites

- Java 21 (`java -version`)
- MySQL 8 running on `localhost:3306`
- Nothing else - Maven comes from the project's `./mvnw` wrapper.

---

## 2. Database

The app connects as the MySQL user **`advisory`** (password `advisory123`) to the
database **`advisory_db`**. These live in `src/main/resources/application.properties`
(the datasource block is never changed by feature work).

Create the user and database once:

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS advisory_db CHARACTER SET utf8mb4;"
mysql -uroot -p -e "CREATE USER IF NOT EXISTS 'advisory'@'localhost' IDENTIFIED BY 'advisory123';"
mysql -uroot -p -e "GRANT ALL PRIVILEGES ON advisory_db.* TO 'advisory'@'localhost'; FLUSH PRIVILEGES;"
```

Load the schema and the demo data (both files are **re-runnable**: they use
`CREATE TABLE IF NOT EXISTS`, guarded `ALTER`s and `INSERT IGNORE`, and never `DROP`):

```bash
cd src/main/resources
mysql -uadvisory -padvisory123 advisory_db < schema.sql       # core tables
mysql -uadvisory -padvisory123 advisory_db < schema_new.sql   # all later tables/columns
mysql -uadvisory -padvisory123 advisory_db < sample_data.sql  # demo users + sample rows
mysql -uadvisory -padvisory123 advisory_db < data_new.sql     # districts, schemes, weather config
```

`spring.sql.init.mode=never`, so the app never runs these by itself - you always
apply them by hand, which makes the viva explanation simple and repeatable.

---

## 3. Run

```bash
cd /home/prab/Prab/Prabjingo/mini_pjt
./mvnw spring-boot:run
```

Open <http://localhost:8080>.

To run on another port (useful when 8080 is busy during testing):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8091
```

Weather refresh uses `weather.refresh.cron` (default `0 0 6 * * *`, Asia/Kolkata),
with a start-up catch-up when the cache is stale. Offline seed rows are explicitly
marked SAMPLE.

---

## 4. Demo logins

| Username | Password      | Role   | What to show |
|----------|---------------|--------|--------------|
| `farmer1`| `password123` | FARMER | Home, nowcast/forecast, lab request + tracker, schemes, community, profile |
| `farmer2`| `password123` | FARMER | A second farmer to demo posts, comments, votes, follow and DM |
| `lab1`   | `password123` | LAB    | Lab dashboard: queue, advance a request, enter results, PDF report |
| `owner`  | `password123` | OWNER  | Owner panel, approve/reject labs, edit scheme text, moderate community, `/debug` |

A new farmer or lab account can log in only after its email is verified. A lab also
requires owner approval. SMS/OTP verification has been removed; the phone field is
contact information only.

## 5. Email verification

Email links are valid for 24 hours, stored as SHA-256 hashes, single-use, and limited
to one send per minute and five sends per hour. `app.base-url` must be the externally
reachable origin used in links (default `http://localhost:8080`). With no SMTP host,
demo mode shows the link on the verification page and logs only a non-sensitive
delivery notice; tokens and recipient addresses are not logged.

Set SMTP configuration through environment variables (do not commit credentials):

```bash
export APP_BASE_URL="http://localhost:8080"
export SPRING_MAIL_HOST="sandbox.smtp.mailtrap.io"
export SPRING_MAIL_PORT="587"
export SPRING_MAIL_USERNAME="your-mailtrap-username"
export SPRING_MAIL_PASSWORD="your-mailtrap-password"
export SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH="true"
export SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE="true"
./mvnw spring-boot:run
```

For Gmail, use `smtp.gmail.com`, port `587`, your Gmail address as
`SPRING_MAIL_USERNAME`, and a Google **App Password** as `SPRING_MAIL_PASSWORD`.
An App Password requires 2-Step Verification. Never use your normal Gmail password.
The same auth and STARTTLS variables above apply.

`verify.login.requires=email` is the only supported mode because verification is
email-only; there is no real SMS provider code or SMS configuration.

---

## 5. Pages (top-bar workspaces)

1. `/` Home · 2. `/nowcast` · 3. `/forecast` · 4. `/lab` · 5. `/schemes` ·
6. `/community` · 7. `/profile`. Plus `/farmers` (public farmer directory),
`/messages` (connected-farmer inbox), `/map` (district map), `/register`,
`/register/lab`, `/owner` (owner only) and `/debug` (owner only).

---

## 6. Smoke test (proves it works end to end)

`smoke_test.sh` starts nothing itself. Boot the app first, then point the script at it:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8091 &
bash smoke_test.sh http://localhost:8091
```

It checks pages in all three languages, exercises email token expiry/reuse and resend
limits, profile photos, follow relationships, contact privacy, connected-only chat and
unread state, the lab workflow, community and owner panel. It removes test rows and
test-owned uploaded files, then prints `RESULT: n passed, m failed`.

## 7. New database update and SQL commands

Apply the additive, re-runnable update after the core schema is installed:

```bash
cd /home/prab/Prab/Prabjingo/mini_pjt
mysql -uadvisory -padvisory123 advisory_db < src/main/resources/schema_new.sql
mysql -uadvisory -padvisory123 advisory_db < src/main/resources/data_new.sql
```

`schema_new.sql` adds nullable `farmer_profiles.photo_path` and email resend quota
columns with `information_schema` guards. It never drops existing data.
`spring.sql.init.mode=never` remains enabled.

---

## 8. Project layout

```
src/main/java/com/precisionfarming/advisory_service/
  config/SecurityConfig.java          roles, CSRF, lab-approved login rule
  controller/                         one class per workspace (see DEBUG_GUIDE.md)
  repository/                         JdbcTemplate DAOs + one JPA repository
  service/                            weather scraper, Open-Meteo fallback, PDF writer
  model/                              plain carriers / records
src/main/resources/
  templates/                          Thymeleaf pages (fragments, layouts, pages)
  static/css/style.css                the single stylesheet
  messages*.properties                i18n: base + _en + _hi + _mr
  schema.sql / schema_new.sql         re-runnable DDL
  sample_data.sql / data_new.sql      re-runnable demo data
uploads/                              reports, community photos and avatars (outside static)
```

See **DEBUG_GUIDE.md** for a symptom-to-file troubleshooting table.

The Maharashtra district GeoJSON used by `/map` is kept at
`src/main/resources/geo/maharashtra_districts.geojson`. It is based on the DataMeet
Maps community repository (India boundary data), whose README states CC BY 4.0 for
datasets unless a dataset says otherwise:
https://github.com/datameet/maps and https://creativecommons.org/licenses/by/4.0/.
This service contains no JavaScript, SMS gateway, or client-side map/chart library.
