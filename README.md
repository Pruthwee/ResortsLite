# ResortsLite — Modernised Java 21 / Spring Boot 3.2.x Application

A compact Spring Boot 3.2.x resort booking application modernised from Java 8 / Spring Boot 2.7.x
as part of the **Concierto Modernize** AWS upgrade campaign (IDE1-ResortAWSUpgradeCMP).

**Build Status:** ✅ 0 compilation errors — clean build on Java 21 / Spring Boot 3.2.5

---

## Tech Stack (Post-Transformation)

| Item | Before | After |
|---|---|---|
| Java | 1.8 | **21** |
| Spring Boot | 2.7.18 | **3.2.5** |
| Jakarta EE | javax.servlet | **jakarta.servlet** |
| MySQL Connector | mysql-connector-java | **com.mysql:mysql-connector-j:8.2.0** |
| Log4j Core | 2.14.1 (CVE-2021-44228) | **2.23.1** |
| Commons Collections | 3.2.1 (CVE-2015-6420) | **3.2.2** |
| Hashing | MD5 (broken) | **SHA-256** |
| Date/Time API | java.util.Date / SimpleDateFormat | **java.time.LocalDateTime + DateTimeFormatter** |
| maven-compiler-plugin | (default) | **3.13.0** |
| maven-surefire-plugin | (default) | **3.2.5** |
| Build | Maven | Maven |
| Database | H2 in-memory | H2 in-memory |

---

## Transformation Summary

### ✅ Completed Transformations

| Rule / Category | File(s) | Change Applied |
|---|---|---|
| JAVA8_TO_21_JAKARTA_EE_MIGRATION | BookingController.java | `javax.servlet` → `jakarta.servlet` |
| JAVA8_TO_21_SPRING_BOOT_COMPATIBILITY | pom.xml | Spring Boot 2.7.18 → 3.2.5 |
| JAVA8_TO_21_DEPENDENCY_UPDATES | pom.xml | Java source/target/release set to 21 |
| JAVA8_TO_21_DEPENDENCY_UPDATES | pom.xml | mysql-connector-java → com.mysql:mysql-connector-j:8.2.0 |
| JAVA8_TO_21_SECURITY_CHANGES | BookingService.java | MD5 → SHA-256 (MessageDigest) |
| JAVA8_TO_21_DATE_TIME_CHANGES | ReportService.java | java.util.Date/SimpleDateFormat → java.time.LocalDateTime |
| JAVA8_TO_17_SPECIFIC_DEPENDENCY_UPDATES | pom.xml | log4j-core 2.14.1 → 2.23.1 (Log4Shell fix) |
| JAVA8_TO_17_DEPENDENCY_CONFLICTS | pom.xml | commons-collections 3.2.1 → 3.2.2 (RCE fix) |
| JAVA8_TO_17_MAVEN_PLUGIN_UPDATES | pom.xml | maven-surefire-plugin → 3.2.5 |
| JAVA8_TO_17_LOMBOK_CONFIGURATION | pom.xml | maven-compiler-plugin → 3.13.0 |

---

## Remaining Violation Traceability (Runtime / Architecture — Not Compilation Errors)

The following are **runtime / cloud-architecture** violations noted for future remediation.
They do **not** cause compilation errors.

| Rule ID | Domain | Severity | File | Description |
|---|---|---|---|---|
| cr-java-0065 | Cloud Compatibility | Mandatory | BookingController.java | HTTP session state — breaks auto-scaling |
| cr-java-0067 | Cloud Compatibility | Potential | BookingController.java | In-memory cache without TTL — instance-local |
| cr-java-0088 | Cloud Compatibility | Mandatory | BookingController.java, ReportService.java | Plain HTTP URLs for internal service calls |
| cr-java-0021 | Cloud Compatibility | Mandatory | BookingService.java, application.properties | Hardcoded DB hostname + service endpoints |
| czr-java-001 | Software Portability | Mandatory | BookingController.java, ReportService.java | Hardcoded absolute file paths |
| czr-port-001 | Software Portability | High | ReportService.java | Fixed server port — blocks ECS/EKS dynamic binding |
| sql-inject-001 | Security Health | Critical | BookingService.java | SQL injection via string concatenation |
| sec-cred-001 | Security Health | Critical | BookingService.java | Hardcoded database credentials in source code |
| dup-logic-001 | Code Sustainability | Medium | BookingService.java | Duplicated room type validation |
| complexity-001 | Code Sustainability | High | BookingService.java | Cyclomatic complexity > 9 in calculateRoomPrice |

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

## Source File Summary

| File | Lines | Status |
|---|---|---|
| pom.xml | ~120 | ✅ Updated |
| ResortsLiteApplication.java | 11 | ✅ Clean |
| BookingController.java | ~82 | ✅ jakarta.servlet migrated |
| BookingService.java | ~130 | ✅ SHA-256, Java 21 syntax |
| ReportService.java | ~110 | ✅ java.time API |
| application.properties | 18 | ✅ H2 datasource configured |

*Java 21 — Spring Boot 3.2.5 — 0 compilation errors*
