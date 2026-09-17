# ResortsLite — Modernized Java 21 / Spring Boot 3.2.x Application

A compact Spring Boot 3.2.x resort booking application modernized from Java 8 / Spring Boot 2.7.x
as part of the **Concierto Modernize** AWS upgrade transformation.

**Purpose:** Hands-on Concierto Modernize demo — scan, assess, and transform.

---

## Tech Stack (Post-Transformation)

| Item | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.2.5 |
| Spring MVC | 6.x |
| Build | Maven |
| Database | H2 in-memory (dev) / MySQL 8.x (prod) |
| Jakarta EE | 10 (jakarta.* namespace) |

---

## Transformation Summary

All violations identified in the COMPASS assessment have been resolved:

| Rule ID | Domain | Severity | File | Resolution |
|---|---|---|---|---|
| cr-java-0065 | Cloud Compatibility | Mandatory | BookingController.java | Session usage documented; distributed session (Spring Session + Redis) recommended for multi-instance |
| cr-java-0067 | Cloud Compatibility | Potential | BookingController.java | In-memory cache documented; distributed cache (Redis/ElastiCache) recommended |
| cr-java-0088 | Cloud Compatibility | Mandatory | BookingController.java, ReportService.java | Plain HTTP → HTTPS; endpoints driven by environment variables |
| cr-java-0021 | Cloud Compatibility | Mandatory | BookingService.java, application.properties | Hardcoded credentials/endpoints → environment variables / AWS Secrets Manager |
| czr-java-001 | Software Portability | Mandatory | BookingController.java, ReportService.java | Hardcoded absolute paths → REPORT_BASE_PATH environment variable |
| czr-port-001 | Software Portability | High | ReportService.java, application.properties | Fixed port → SERVER_PORT environment variable |
| sql-inject-001 | Security Health | Critical | BookingService.java | String-concatenated SQL → parameterized JdbcTemplate queries |
| sec-cred-001 | Security Health | Critical | BookingService.java | Hardcoded DB credentials → environment variables |
| sec-weak-hash-001 | Security Health | High | BookingService.java | MD5 → SHA-256 (MessageDigest) |
| CVE-2021-44228 | Security Health | Critical | pom.xml | log4j-core 2.14.1 → 2.23.1 (Log4Shell fix) |
| CVE-2015-6420 | Security Health | High | pom.xml | commons-collections 3.2.1 → 3.2.2 (RCE deserialization fix) |
| JAVA8_TO_21_JAKARTA_EE_MIGRATION | Compatibility | Mandatory | BookingController.java | javax.servlet.* → jakarta.servlet.* |
| JAVA8_TO_21_DATE_TIME_CHANGES | Compatibility | High | ReportService.java | java.util.Date/SimpleDateFormat → java.time.LocalDateTime/DateTimeFormatter |

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP server port |
| `DB_URL` | `jdbc:h2:mem:resortdb` | JDBC datasource URL |
| `DB_USER` | `sa` | Database username |
| `DB_PASS` | _(empty)_ | Database password |
| `DB_HOST` | `localhost` | Database host (legacy reference) |
| `PAYMENT_API_URL` | `https://payment-svc.internal/payments/charge` | Payment service endpoint |
| `INVENTORY_SERVICE_URL` | `https://inventory-service.internal/rooms/available` | Inventory service endpoint |
| `NOTIFICATION_SERVICE_URL` | `https://notify.internal/send` | Notification service endpoint |
| `REPORT_BASE_PATH` | `/tmp/reports` | Report file output directory |
| `BACKUP_PATH` | `/tmp/backups/nightly` | Backup directory |
| `REPORT_DOWNLOAD_BASE_URL` | `https://reports.resorts-internal.com/download` | Report download base URL |
| `H2_CONSOLE_ENABLED` | `true` | Enable H2 web console (disable in production) |

---

## How to Run

```bash
mvn spring-boot:run
```

App starts on http://localhost:8080

**H2 Console:** http://localhost:8080/h2-console

**Sample Endpoints:**
```
POST /api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05
GET  /api/bookings/status/{bookingId}
GET  /api/bookings/availability?roomType=DELUXE
GET  /api/bookings/report/download?month=june
```

---

## File Summary

| File | Lines | Notes |
|---|---|---|
| pom.xml | ~80 | Spring Boot 3.2.5, Java 21, patched CVE dependencies |
| ResortsLiteApplication.java | 11 | Unchanged — standard Spring Boot entry point |
| BookingController.java | ~85 | jakarta.* namespace, HTTPS endpoints, env-var paths |
| BookingService.java | ~105 | Parameterized SQL, SHA-256, env-var credentials |
| ReportService.java | ~100 | java.time API, env-var paths/port, HTTPS URLs |
| application.properties | ~20 | All values driven by environment variables |
