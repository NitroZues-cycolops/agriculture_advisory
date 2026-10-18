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

The weather scraper starts 20 seconds after boot and then runs every 3 hours. The
first run fills `weather_cache` / `weather_forecast`; with the college wifi off it
falls back to the SAMPLE rows and the page still renders (marked `SAMPLE`).

---

## 4. Demo logins

| Username | Password      | Role   | What to show |
|----------|---------------|--------|--------------|
| `farmer1`| `password123` | FARMER | Home, nowcast/forecast, lab request + tracker, schemes, community, profile |
| `farmer2`| `password123` | FARMER | A second farmer to demo posts, comments, votes, follow and DM |
| `lab1`   | `password123` | LAB    | Lab dashboard: queue, advance a request, enter results, PDF report |
| `owner`  | `password123` | OWNER  | Owner panel, approve/reject labs, edit scheme text, moderate community, `/debug` |

A new lab account can log in **only after the owner approves it**.

---

## 5. Pages (top-bar workspaces)

1. `/` Home · 2. `/nowcast` · 3. `/forecast` · 4. `/lab` · 5. `/schemes` ·
6. `/community` · 7. `/profile`. Plus `/map` (district temperature map),
`/register`, `/register/lab`, `/owner` (owner only) and `/debug` (owner only).

---

## 6. Smoke test (proves it works end to end)

`smoke_test.sh` starts nothing itself. Boot the app first, then point the script at it:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8091 &
bash smoke_test.sh http://localhost:8091
```

It logs in with a cookie jar, checks every page for all three roles in all three
languages (no 404/500, no `??key??`), registers a farmer and a lab, runs the whole
lab flow (request -> ready -> PDF `%PDF`), exercises the community and the owner
panel, and prints `RESULT: n passed, m failed`. It creates a few test rows; that is
fine for a demo database.

---

## 7. Project layout

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
uploads/                              generated reports + community photos (outside static)
```

See **DEBUG_GUIDE.md** for a symptom-to-file troubleshooting table.
