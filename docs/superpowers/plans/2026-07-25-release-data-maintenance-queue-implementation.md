# Release and Data Maintenance Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a containerized internal queue system that assigns release and data-maintenance tasks by duty roster, time window, daily load, monthly completed duration, and availability.

**Architecture:** A Vue 3 single-page application is served by Nginx and calls a Spring Boot 3 REST API. The API owns authentication, authorization, assignment decisions, task state changes, audit records, and notification outbox events; MySQL 8 persists all state and serializes same-day assignments with a date lock row.

**Tech Stack:** Java 21, Spring Boot 3.5.7, Maven 3.9, Spring Security, Spring Data JPA, Flyway, Apache POI, MySQL 8.4, Testcontainers, Node 22, Vue 3.5, TypeScript 5.9, Vite 7, Pinia 3, Vue Router 4, Element Plus 2, Vitest 3, Playwright 1.55, Nginx, Docker Compose

## Global Constraints

- Business time zone is exactly `Asia/Shanghai`; persisted instants are UTC.
- Task categories are exactly `RELEASE` and `DATA_MAINTENANCE`.
- Daytime is `[08:30, 17:30)`; 17:30 is after-hours.
- A same-day request submitted at or after 21:00 overrides the three-task threshold.
- After-hours assignment uses second-line until 3 tasks, then third-line until 3 tasks, then fair allocation.
- Fair allocation sorts by operation-day task count, operation-month completed actual minutes, oldest assignment time, then user ID.
- Fair allocation excludes operation-day unavailable users and next-day second/third-line duty users.
- Monthly actual duration includes completed tasks only and is attributed to the final assignee in the operation-start month.
- The first release records `TASK_CALLED` outbox events but does not call DingTalk.
- Business records have no physical-delete API.
- Every production behavior is introduced through a failing automated test.

## File and Module Map

### Backend

- `backend/pom.xml`: Java dependencies, build plugins, test plugins.
- `backend/src/main/java/com/acme/opsqueue/OpsQueueApplication.java`: Spring Boot entrypoint.
- `backend/src/main/java/com/acme/opsqueue/common/`: error envelope, clock/time-zone policy, request identity, optimistic conflict mapping.
- `backend/src/main/java/com/acme/opsqueue/identity/`: users, roles, password policy, JWT cookie login, current-user endpoints.
- `backend/src/main/java/com/acme/opsqueue/roster/`: duty roster entities, Excel staging/validation/import, template download.
- `backend/src/main/java/com/acme/opsqueue/scheduling/`: pure assignment engine, candidate snapshots, scheduling locks and task-number sequence.
- `backend/src/main/java/com/acme/opsqueue/task/`: task aggregate, create/query/call/complete endpoints and services.
- `backend/src/main/java/com/acme/opsqueue/assignment/`: transfer, leader adjustment, unavailability and bulk redistribution.
- `backend/src/main/java/com/acme/opsqueue/notification/`: transactional outbox event entity and repository.
- `backend/src/main/java/com/acme/opsqueue/reporting/`: daily counts and monthly duration projections.
- `backend/src/main/java/com/acme/opsqueue/audit/`: immutable audit records and query endpoint.
- `backend/src/main/resources/db/migration/`: ordered Flyway migrations.
- `backend/src/test/java/com/acme/opsqueue/`: unit and MySQL Testcontainers integration tests mirroring production packages.

### Frontend

- `frontend/src/app/`: router, Pinia, HTTP client, CSRF/JWT-cookie handling.
- `frontend/src/layout/`: role navigation, header duty indicator, task queue shell.
- `frontend/src/features/auth/`: login, forced password change and session store.
- `frontend/src/features/tasks/`: create form, all-task queue, detail, call, complete and transfer.
- `frontend/src/features/roster/`: template download, preview, errors and confirmation.
- `frontend/src/features/people/`: account/role management, unavailability and redistribution.
- `frontend/src/features/reporting/`: daily/monthly statistics.
- `frontend/src/features/audit/`: audit search table.
- `frontend/tests/e2e/`: acceptance paths against the composed system.

### Deployment

- `compose.yaml`: `web`, `api`, `db` services, health checks, network and volumes.
- `deploy/nginx.conf`: SPA fallback and `/api` proxy.
- `deploy/api.Dockerfile`, `deploy/web.Dockerfile`: reproducible multi-stage images.
- `scripts/backup.ps1`, `scripts/restore.ps1`: explicit MySQL backup and guarded restore.
- `.env.example`: non-secret deployment variable contract.

---

### Task 1: Backend Bootstrap, MySQL Integration, and Health

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/test/java/com/acme/opsqueue/OpsQueueApplicationTest.java`
- Create: `backend/src/test/java/com/acme/opsqueue/support/MySqlIntegrationTest.java`
- Create: `backend/src/main/java/com/acme/opsqueue/OpsQueueApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/main/resources/db/migration/V1__bootstrap.sql`

**Interfaces:**
- Produces: a bootable Spring application, Flyway-enabled MySQL connection, `/actuator/health`, and `MySqlIntegrationTest` for later integration tests.

- [ ] **Step 1: Create the Maven build and a context test that references the missing application**

```java
package com.acme.opsqueue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OpsQueueApplicationTest {
    @Test
    void contextLoads() {
    }
}
```

Configure `pom.xml` with Spring Boot parent `3.5.7`, Java 21, Spring Web, Validation, Security, Data JPA, Actuator, Flyway MySQL, MySQL Connector/J, Spring Boot Test, Spring Security Test, Testcontainers JUnit/MySQL, and the Spring Boot Maven plugin.

- [ ] **Step 2: Run the test and verify the intended failure**

Run: `mvn -f backend/pom.xml -Dtest=OpsQueueApplicationTest test`

Expected: compilation fails because `OpsQueueApplication` does not exist.

- [ ] **Step 3: Add the minimal application, configuration, and bootstrap migration**

```java
package com.acme.opsqueue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OpsQueueApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsQueueApplication.class, args);
    }
}
```

`application.yml` must read `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, expose only `health` and `info`, enable Flyway, use `ddl-auto: validate`, and set Jackson dates to UTC.

`V1__bootstrap.sql` creates a one-row `schema_marker` table so the test proves Flyway executed.

- [ ] **Step 4: Add the reusable MySQL Testcontainers base**

```java
package com.acme.opsqueue.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class MySqlIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("ops_queue")
                    .withUsername("ops_queue")
                    .withPassword("test-password");
}
```

- [ ] **Step 5: Run the backend test suite**

Run: `mvn -f backend/pom.xml test`

Expected: `OpsQueueApplicationTest` passes and logs Flyway migration `V1`.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "build: bootstrap Spring Boot backend"
```

---

### Task 2: Local Accounts, Roles, Login, and Forced Password Change

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__identity.sql`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/RoleName.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/UserAccount.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/UserAccountRepository.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/JwtCookieService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/IdentityService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/IdentityController.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/SecurityConfiguration.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/BootstrapLeaderInitializer.java`
- Create: `backend/src/main/java/com/acme/opsqueue/identity/CurrentUser.java`
- Create: `backend/src/test/java/com/acme/opsqueue/identity/IdentityApiTest.java`

**Interfaces:**
- Produces: `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`, `POST /api/auth/change-password`, leader-only user create/disable/reset/role endpoints.
- Produces: `CurrentUser(UUID id, String username, String displayName, Set<RoleName> roles, boolean mustChangePassword)`.

- [ ] **Step 1: Write failing API tests for login and role enforcement**

```java
@Test
void initialPasswordLoginRequiresChangeBeforeBusinessApis() throws Exception {
    createUser("dev1", "Initial-Password-1", RoleName.DEVELOPER, true);

    mvc.perform(post("/api/auth/login")
                    .contentType(APPLICATION_JSON)
                    .content("""
                        {"username":"dev1","password":"Initial-Password-1"}
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mustChangePassword").value(true))
            .andExpect(cookie().httpOnly("OPS_SESSION", true));
}

@Test
void nonLeaderCannotCreateAccounts() throws Exception {
    mvc.perform(authenticatedPost("/api/admin/users", developerCookie())
                    .contentType(APPLICATION_JSON)
                    .content("""
                        {"username":"ops1","displayName":"Ops One",
                         "initialPassword":"Initial-Password-2",
                         "roles":["OPERATOR"]}
                        """))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `mvn -f backend/pom.xml -Dtest=IdentityApiTest test`

Expected: FAIL because the identity endpoints and schema do not exist.

- [ ] **Step 3: Add identity schema and domain**

`V2__identity.sql` creates `users`, `roles`, and `user_roles`; seeds `DEVELOPER`, `OPERATOR`, and `LEADER`; enforces unique username; stores BCrypt hashes, `must_change_password`, enabled flag, last login, created and updated timestamps.

```java
public enum RoleName {
    DEVELOPER, OPERATOR, LEADER
}
```

`UserAccount` must expose `hasRole(RoleName)`, `disable()`, `resetPassword(String hash)`, and `changePassword(String hash)`. Reset sets `mustChangePassword=true`; change sets it to false.

- [ ] **Step 4: Implement cookie JWT authentication and CSRF**

`SecurityConfiguration` must:

- Permit `/api/auth/login`, `/actuator/health`, and CSRF-token bootstrap.
- Require authentication for `/api/**`.
- Use `CookieCsrfTokenRepository.withHttpOnlyFalse()`.
- Read `OPS_SESSION` from an HttpOnly, SameSite=Strict cookie.
- Re-query the user on every request so disabled accounts stop immediately.
- Reject business endpoints while `mustChangePassword=true`, except `/api/auth/me`, `/api/auth/change-password`, and logout.
- Add per-username and per-IP login throttling with five failures in fifteen minutes.

- [ ] **Step 5: Implement leader bootstrap and account administration**

Read `BOOTSTRAP_LEADER_USERNAME`, `BOOTSTRAP_LEADER_DISPLAY_NAME`, and `BOOTSTRAP_LEADER_PASSWORD`. Create the first leader only when no leader exists. Reject startup when no leader exists and any required bootstrap value is blank.

- [ ] **Step 6: Run identity tests and the full backend suite**

Run: `mvn -f backend/pom.xml -Dtest=IdentityApiTest test`

Expected: all identity API tests pass.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests pass.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add local account authentication and roles"
```

---

### Task 3: Duty Roster Template, Validation Preview, and Atomic Import

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__duty_roster.sql`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/DutyRoster.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/RosterImportBatch.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/RosterImportRow.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/RosterImportPreview.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/RosterImportError.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/DutyRosterView.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/RosterExcelParser.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/RosterImportService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/roster/RosterController.java`
- Create: `backend/src/test/java/com/acme/opsqueue/roster/RosterImportServiceTest.java`
- Create: `backend/src/test/java/com/acme/opsqueue/roster/RosterApiTest.java`

**Interfaces:**
- Produces: `DutyRosterView dutyFor(LocalDate date)` with duty date, second-line user ID, and third-line user ID.
- Produces: `GET /api/rosters/template`, `POST /api/rosters/imports/preview`, `POST /api/rosters/imports/{batchId}/confirm`, `GET /api/rosters`, `GET /api/rosters/imports`.

- [ ] **Step 1: Write failing parser and service tests**

```java
@Test
void previewRejectsSameSecondAndThirdLineAccount() {
    byte[] workbook = workbook("""
        值班日期,二线管理员账号,三线管理员账号
        2026-07-25,ops1,ops1
        """);

    RosterImportPreview preview = service.preview("roster.xlsx", workbook, leaderId);

    assertThat(preview.valid()).isFalse();
    assertThat(preview.errors()).containsExactly(
            new RosterImportError(2, "二线管理员账号和三线管理员账号不能相同"));
}

@Test
void confirmReplacesOnlyDatesPresentInValidatedBatch() {
    UUID batchId = stageValidRows(
            row("2026-07-25", "ops1", "ops2"),
            row("2026-07-26", "ops3", "ops4"));

    service.confirm(batchId, leaderId);

    assertThat(repository.findByDutyDate(LocalDate.parse("2026-07-25")))
            .get().extracting(DutyRoster::secondLineId, DutyRoster::thirdLineId)
            .containsExactly(ops1Id, ops2Id);
}
```

- [ ] **Step 2: Run the roster tests and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=RosterImportServiceTest,RosterApiTest test`

Expected: FAIL because roster classes and endpoints do not exist.

- [ ] **Step 3: Implement the schema and Excel parser**

Create `duty_rosters`, `roster_import_batches`, and `roster_import_rows`. Use Apache POI and require the exact headers `值班日期`, `二线管理员账号`, `三线管理员账号`.

Return row-specific errors for:

- blank or invalid date;
- duplicate date in the workbook;
- unknown or disabled account;
- account without `OPERATOR`;
- identical second- and third-line account.

Store the validated staged rows and SHA-256 file digest; do not store the uploaded workbook bytes.

- [ ] **Step 4: Implement atomic confirmation and leader-only endpoints**

`confirm` locks the batch, requires status `VALIDATED`, replaces only its staged dates in one transaction, changes status to `IMPORTED`, and stores confirmer/time. A second confirmation returns HTTP 409. `GET /api/rosters/imports` returns paged batch history ordered by creation time descending.

- [ ] **Step 5: Run focused and full tests**

Run: `mvn -f backend/pom.xml -Dtest=RosterImportServiceTest,RosterApiTest test`

Expected: roster tests pass.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add duty roster Excel import"
```

---

### Task 4: Pure Auto-Assignment Engine

**Files:**
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/AssignmentRule.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/DutyPair.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/CandidateMetric.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/AssignmentInput.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/CandidateSnapshot.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/AssignmentDecision.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/NoEligibleCandidateException.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/AutoAssignmentEngine.java`
- Create: `backend/src/test/java/com/acme/opsqueue/scheduling/AutoAssignmentEngineTest.java`

**Interfaces:**
- Produces: `AssignmentDecision AutoAssignmentEngine.assign(AssignmentInput input)`.
- `AssignmentInput` contains submission instant, operation start in `Asia/Shanghai`, duty pair, and one metric per active operator.

- [ ] **Step 1: Write the complete failing decision-table test**

Use parameterized tests for these expected rules:

```java
static Stream<Arguments> priorityCases() {
    return Stream.of(
        arguments("08:30 uses second line", "2026-07-25T08:30+08:00",
                "2026-07-25T08:00:00Z", 9, 0, AssignmentRule.DAY_SECOND, SECOND),
        arguments("17:30 uses second line below threshold", "2026-07-25T17:30+08:00",
                "2026-07-25T08:00:00Z", 2, 0, AssignmentRule.AFTER_HOURS_SECOND, SECOND),
        arguments("second line at three uses third line", "2026-07-25T20:00+08:00",
                "2026-07-25T08:00:00Z", 3, 0, AssignmentRule.AFTER_HOURS_THIRD, THIRD),
        arguments("21:00 submission for same-day operation overrides threshold",
                "2026-07-25T22:00+08:00", "2026-07-25T13:00:00Z",
                8, 8, AssignmentRule.LATE_SAME_DAY_SECOND, SECOND));
}
```

Add separate tests proving:

- 08:29 is after-hours;
- unavailable second-line falls back to third-line;
- unavailable second- and third-line enters fair allocation;
- fair pool includes the current duty pair;
- tomorrow-duty users are absent from fair snapshots;
- daily count wins before monthly minutes;
- monthly minutes wins before last assignment;
- null/oldest assignment wins before user ID;
- empty candidate pool throws `NoEligibleCandidateException`.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=AutoAssignmentEngineTest test`

Expected: compilation fails because `AutoAssignmentEngine` is absent.

- [ ] **Step 3: Implement the immutable input/output types and enum**

```java
public enum AssignmentRule {
    LATE_SAME_DAY_SECOND,
    LATE_SAME_DAY_THIRD,
    DAY_SECOND,
    DAY_THIRD,
    AFTER_HOURS_SECOND,
    AFTER_HOURS_THIRD,
    FAIR
}
```

`CandidateSnapshot` stores `userId`, `dailyTaskCount`, `monthlyActualMinutes`, `lastAssignedAt`, and exclusion reason.

- [ ] **Step 4: Implement the pure engine**

Use `ZoneId.of("Asia/Shanghai")`, `LocalTime.of(8, 30)`, `LocalTime.of(17, 30)`, and `LocalTime.of(21, 0)`. Do not query a repository or read the system clock inside the engine.

Fair comparator:

```java
Comparator<CandidateMetric> FAIR_ORDER =
        Comparator.comparingInt(CandidateMetric::dailyTaskCount)
                .thenComparingLong(CandidateMetric::monthlyActualMinutes)
                .thenComparing(CandidateMetric::lastAssignedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(CandidateMetric::userId);
```

- [ ] **Step 5: Run the engine tests**

Run: `mvn -f backend/pom.xml -Dtest=AutoAssignmentEngineTest test`

Expected: all decision-table and tie-breaker tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/acme/opsqueue/scheduling backend/src/test/java/com/acme/opsqueue/scheduling
git commit -m "feat: implement deterministic assignment engine"
```

---

### Task 5: Transactional Task Creation and Concurrent Ticket Numbers

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__tasks_and_assignment.sql`
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskCategory.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskStatus.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/OpsTask.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/OpsTaskRepository.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/CreateTaskCommand.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/CreatedTask.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskView.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskValidationException.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/CreateTaskService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskController.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/AssignmentType.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/AssignmentHistory.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/AssignmentHistoryRepository.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/Unavailability.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/UnavailabilityRepository.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/SchedulingMetricsRepository.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/ScheduleDateLockRepository.java`
- Create: `backend/src/main/java/com/acme/opsqueue/scheduling/DailyTicketSequenceRepository.java`
- Create: `backend/src/test/java/com/acme/opsqueue/task/CreateTaskServiceTest.java`
- Create: `backend/src/test/java/com/acme/opsqueue/task/ConcurrentCreateTaskTest.java`

**Interfaces:**
- Consumes: `DutyRosterView`, `DutyPair`, and `AutoAssignmentEngine.assign`.
- Produces: `CreatedTask(UUID id, String ticketNumber, UUID assigneeId, AssignmentRule rule) CreateTaskService.create(CreateTaskCommand command, UUID creatorId, Instant submittedAt)`.
- Produces: developer-only `POST /api/tasks`.

- [ ] **Step 1: Write failing validation and integration tests**

```java
@Test
void bothCategoriesRequireProcessNumber() {
    CreateTaskCommand command = new CreateTaskCommand(
            TaskCategory.DATA_MAINTENANCE, "Data Platform", 45, " ",
            zdt("2026-07-25T20:00+08:00"), zdt("2026-07-25T20:45+08:00"));

    assertThatThrownBy(() -> service.create(command, developerId, instant("2026-07-25T10:00:00Z")))
            .isInstanceOf(TaskValidationException.class)
            .hasMessageContaining("操作流程编号");
}

@Test
void concurrentCreatesProduceUniqueSequentialNumbers() {
    List<CreatedTask> results = runConcurrently(20, this::createAfterHoursTask);

    assertThat(results).extracting(CreatedTask::ticketNumber)
            .doesNotHaveDuplicates()
            .contains("OPS-20260725-0001", "OPS-20260725-0020");
}
```

Add tests for missing `D` roster, missing `D+1` roster when fair allocation is reached, end not after start, non-positive estimate, and non-developer creation.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=CreateTaskServiceTest,ConcurrentCreateTaskTest test`

Expected: FAIL because task persistence and creation do not exist.

- [ ] **Step 3: Add schema and aggregate**

`V4__tasks_and_assignment.sql` creates `tasks`, `assignment_histories`, `unavailability`, `schedule_date_locks`, and `daily_ticket_sequences`. `unavailability` has a unique `(user_id, unavailable_date)` key.

`tasks` includes a numeric `version` for optimistic locking and indexes on operation start, status, creator, current assignee, category, and system name.

- [ ] **Step 4: Implement the locked creation transaction**

`CreateTaskService.create` must:

1. validate and normalize input;
2. derive `D` in Asia/Shanghai;
3. insert-or-lock `schedule_date_locks[D]` using `SELECT ... FOR UPDATE`;
4. load `D` duty and active operator metrics;
5. load `D+1` duty only when the engine needs fair allocation;
6. call the engine;
7. lock/increment the daily number row;
8. insert the task with `PENDING`;
9. insert an `AUTO` assignment history with candidate snapshot;
10. commit once.

Wrap deadlock/lock-timeout exceptions with three bounded retries around the whole transaction.

- [ ] **Step 5: Run focused and full tests**

Run: `mvn -f backend/pom.xml -Dtest=CreateTaskServiceTest,ConcurrentCreateTaskTest test`

Expected: validation and 20-way concurrency tests pass.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add transactional task creation"
```

---

### Task 6: Call, Complete, and Notification Outbox

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__notification_outbox.sql`
- Create: `backend/src/main/java/com/acme/opsqueue/notification/NotificationEvent.java`
- Create: `backend/src/main/java/com/acme/opsqueue/notification/NotificationEventRepository.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskLifecycleService.java`
- Create: `backend/src/test/java/com/acme/opsqueue/task/TaskLifecycleServiceTest.java`
- Modify: `backend/src/main/java/com/acme/opsqueue/task/TaskController.java`

**Interfaces:**
- Produces: `TaskView call(UUID taskId, UUID actorId, Instant calledAt)`.
- Produces: `TaskView complete(UUID taskId, UUID actorId, int actualMinutes, Instant completedAt)`.
- Produces: `POST /api/tasks/{id}/call`, `POST /api/tasks/{id}/complete`.

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test
void assigneeCallingPendingTaskMovesItToInProgressAndWritesOutbox() {
    OpsTask task = pendingTaskAssignedTo(operatorId);

    service.call(task.id(), operatorId, instant("2026-07-25T12:01:00Z"));

    assertThat(taskRepository.findById(task.id()).orElseThrow().status())
            .isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(notificationRepository.findByAggregateId(task.id()))
            .singleElement()
            .extracting(NotificationEvent::eventType)
            .isEqualTo("TASK_CALLED");
}

@Test
void completingRequiresPositiveActualMinutes() {
    OpsTask task = inProgressTaskAssignedTo(operatorId);

    assertThatThrownBy(() -> service.complete(task.id(), operatorId, 0, now))
            .isInstanceOf(TaskValidationException.class)
            .hasMessageContaining("实际耗时");
}
```

Add tests for non-assignee forbidden, wrong source status returning conflict, completed task immutable, and stale version returning conflict.

- [ ] **Step 2: Run and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=TaskLifecycleServiceTest test`

Expected: FAIL because lifecycle service and outbox table do not exist.

- [ ] **Step 3: Implement aggregate transitions and outbox transaction**

```java
public void call(UUID actorId, Instant at) {
    requireAssignee(actorId);
    requireStatus(TaskStatus.PENDING);
    this.status = TaskStatus.IN_PROGRESS;
    this.calledAt = at;
}

public void complete(UUID actorId, int minutes, Instant at) {
    requireAssignee(actorId);
    requireStatus(TaskStatus.IN_PROGRESS);
    if (minutes <= 0) throw new TaskValidationException("实际耗时必须大于 0");
    this.actualMinutes = minutes;
    this.completedAt = at;
    this.status = TaskStatus.COMPLETED;
}
```

Insert `TASK_CALLED` with task ID, developer ID, ticket number, system name, caller and called time in the same transaction as `call`. The outbox row also stores `status=NEW`, `retry_count=0`, nullable `last_error`, `next_attempt_at`, creation time and update time so a future DingTalk adapter can retry without changing the task schema.

- [ ] **Step 4: Add controller endpoints and error mapping**

Return:

- 403 for non-assignee;
- 409 for wrong state or optimistic conflict;
- 422 for non-positive actual minutes;
- 200 with updated task view on success.

- [ ] **Step 5: Run tests**

Run: `mvn -f backend/pom.xml -Dtest=TaskLifecycleServiceTest test`

Expected: lifecycle tests pass.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add task call and completion lifecycle"
```

---

### Task 7: Full Task Center, Role-Scoped Views, and Filtering

**Files:**
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskQuery.java`
- Create: `backend/src/main/java/com/acme/opsqueue/task/TaskQueryService.java`
- Create: `backend/src/test/java/com/acme/opsqueue/task/TaskQueryApiTest.java`
- Modify: `backend/src/main/java/com/acme/opsqueue/task/OpsTaskRepository.java`
- Modify: `backend/src/main/java/com/acme/opsqueue/task/TaskController.java`

**Interfaces:**
- Produces: `GET /api/tasks` with `operationDate`, `category`, `systemName`, `status`, `creatorId`, `assigneeId`, `page`, `size`, `sort`.
- Produces: `GET /api/tasks/{id}` including assignment rule, status, assignment timeline, `canCall`, `canComplete`, `canTransfer`.
- Produces: `GET /api/tasks/system-names?query=<text>&limit=10` returning distinct historical names visible to the caller.

- [ ] **Step 1: Write failing visibility tests**

```java
@Test
void developerSeesOnlyOwnTasksEvenWhenPassingAnotherCreatorFilter() throws Exception {
    createTaskOwnedBy(dev1);
    createTaskOwnedBy(dev2);

    mvc.perform(get("/api/tasks?creatorId=" + dev2).cookie(dev1Cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].creatorId").value(dev1.toString()));
}

@Test
void operatorSeesAllTasksAndTodayDashboardDefaultsToOperationDate() throws Exception {
    createTaskFor("2026-07-25");
    createTaskFor("2026-07-26");

    mvc.perform(get("/api/tasks?operationDate=2026-07-25").cookie(operatorCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));
}

@Test
void systemNameSuggestionsAreDistinctAndRoleScoped() throws Exception {
    createTaskOwnedBy(dev1, "支付系统");
    createTaskOwnedBy(dev1, "支付系统");
    createTaskOwnedBy(dev2, "数据平台");

    mvc.perform(get("/api/tasks/system-names?query=支&limit=10").cookie(dev1Cookie))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                ["支付系统"]
                """));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=TaskQueryApiTest test`

Expected: FAIL because task query endpoints are missing.

- [ ] **Step 3: Implement repository specifications and DTO projection**

Use JPA Specifications with indexed equality/range predicates. Escape `%` and `_` in system-name contains searches. Limit `size` to 100. Default sort is operation start ascending, then ticket number ascending. The system-name suggestion query returns distinct trimmed values, applies the same developer-own/operator-all visibility rule, and caps `limit` at 20.

`canCall` is true only for the current assignee with status `PENDING`; `canComplete` only for the current assignee with `IN_PROGRESS`; `canTransfer` only for the current assignee and an unfinished status.

- [ ] **Step 4: Run focused and full tests**

Run: `mvn -f backend/pom.xml -Dtest=TaskQueryApiTest test`

Expected: query and visibility tests pass.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add role-scoped task center"
```

---

### Task 8: Transfer, Leader Adjustment, Unavailability, and Redistribution

**Files:**
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/AssignmentValidationException.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/AssignmentService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/RedistributionService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/assignment/AssignmentController.java`
- Create: `backend/src/test/java/com/acme/opsqueue/assignment/AssignmentServiceTest.java`
- Create: `backend/src/test/java/com/acme/opsqueue/assignment/RedistributionServiceTest.java`

**Interfaces:**
- Produces: `transfer(taskId, actorId, targetId, reason, at)`.
- Produces: `leaderAdjust(taskId, leaderId, targetId, reason, at)`.
- Produces: `previewRedistribution(operatorId, date)` and `redistribute(operatorId, date, leaderId, reason)`.
- Produces: endpoints under `/api/assignments` and `/api/unavailability`.

- [ ] **Step 1: Write failing transfer tests**

```java
@Test
void currentAssigneeTransfersImmediatelyWithoutAcceptance() {
    OpsTask task = pendingTaskAssignedTo(operator1);

    service.transfer(task.id(), operator1, operator2, "临时冲突", now);

    assertThat(taskRepository.findById(task.id()).orElseThrow().assigneeId())
            .isEqualTo(operator2);
    assertThat(historyRepository.findByTaskId(task.id()))
            .last().extracting(AssignmentHistory::type, AssignmentHistory::reason)
            .containsExactly(AssignmentType.TRANSFER, "临时冲突");
}

@Test
void transferRejectsTargetUnavailableOnOperationDate() {
    markUnavailable(operator2, taskOperationDate);

    assertThatThrownBy(() -> service.transfer(taskId, operator1, operator2, "转交", now))
            .isInstanceOf(AssignmentValidationException.class)
            .hasMessageContaining("不可参与");
}
```

Add tests for non-assignee forbidden, leader adjustment of another assignee's `IN_PROGRESS` task, completed task conflict, next-day duty warning without rejection, and blank reason rejection.

- [ ] **Step 2: Write failing redistribution tests**

Prove:

- preview includes only `PENDING` tasks whose operation date matches;
- `IN_PROGRESS` tasks never move automatically;
- each pending task reruns the assignment engine under the date lock;
- no-candidate task keeps its original assignee and sets `needsManualAttention=true`;
- one failed task does not prevent other tasks from redistributing;
- every successful change writes history.

- [ ] **Step 3: Run and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=AssignmentServiceTest,RedistributionServiceTest test`

Expected: FAIL because assignment services and unavailability schema do not exist.

- [ ] **Step 4: Implement assignment services**

Transfer and adjustment must lock the task, validate target enabled/`OPERATOR`/available, change current assignee, clear `needsManualAttention`, and append history. Redistribution processes stable ticket-number order and opens one transaction per task so it can return per-task results.

- [ ] **Step 5: Add leader endpoints and warning payload**

Return `warnings:["目标人员是次日值班人员"]` for a permitted manual transfer to `D+1` duty. Require leader role for unavailability create/remove, adjustment, preview, and execute.

- [ ] **Step 6: Run tests**

Run: `mvn -f backend/pom.xml -Dtest=AssignmentServiceTest,RedistributionServiceTest test`

Expected: all assignment tests pass.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests pass.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add task transfer and redistribution"
```

---

### Task 9: Audit Trail and Operational Reporting

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__audit.sql`
- Create: `backend/src/main/java/com/acme/opsqueue/audit/AuditLog.java`
- Create: `backend/src/main/java/com/acme/opsqueue/audit/AuditService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/audit/AuditController.java`
- Create: `backend/src/main/java/com/acme/opsqueue/reporting/ReportingService.java`
- Create: `backend/src/main/java/com/acme/opsqueue/reporting/MonthlyOperatorReport.java`
- Create: `backend/src/main/java/com/acme/opsqueue/reporting/DailyOperatorReport.java`
- Create: `backend/src/main/java/com/acme/opsqueue/reporting/ReportingController.java`
- Create: `backend/src/test/java/com/acme/opsqueue/audit/AuditIntegrationTest.java`
- Create: `backend/src/test/java/com/acme/opsqueue/reporting/ReportingServiceTest.java`
- Modify: identity, roster, task and assignment services to call `AuditService`.

**Interfaces:**
- Produces: `audit(action, objectType, objectId, actorId, before, after, sourceIp, at)`.
- Produces: leader-only `GET /api/audit-logs`.
- Produces: operator/leader `GET /api/reports/daily` and `GET /api/reports/monthly`.

- [ ] **Step 1: Write failing reporting tests**

```java
@Test
void monthlyDurationUsesOperationMonthAndFinalAssignee() {
    completedTask("2026-07-31T23:00+08:00", "2026-08-01T01:00+08:00",
            finalOperator, 120);

    MonthlyOperatorReport report =
            service.monthly(YearMonth.of(2026, 7), finalOperator);

    assertThat(report.completedActualMinutes()).isEqualTo(120);
}
```

Add tests that pending/in-progress tasks contribute zero monthly minutes and completed tasks still count in operation-day task count.

- [ ] **Step 2: Write failing audit tests**

Perform login, account reset, roster import, task create, call, complete, transfer, adjustment and redistribution; assert each action produces one immutable audit row with actor, object, before/after summary, source IP and timestamp.

- [ ] **Step 3: Run and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=ReportingServiceTest,AuditIntegrationTest test`

Expected: FAIL because reporting and audit modules are absent.

- [ ] **Step 4: Implement reports and audit persistence**

Use SQL projections grouped by `DATE(CONVERT_TZ(operation_start,'+00:00','+08:00'))` and by operation-start year/month. Do not group by completion month.

Audit summaries must whitelist fields and never include password hashes, raw passwords, JWTs, cookies, or application secrets.

- [ ] **Step 5: Run tests**

Run: `mvn -f backend/pom.xml -Dtest=ReportingServiceTest,AuditIntegrationTest test`

Expected: reporting and audit tests pass.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add audit trail and workload reporting"
```

---

### Task 10: Frontend Foundation, Authentication, and Role Navigation

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/pnpm-lock.yaml`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/app/http.ts`
- Create: `frontend/src/app/router.ts`
- Create: `frontend/src/features/auth/auth.api.ts`
- Create: `frontend/src/features/auth/auth.store.ts`
- Create: `frontend/src/features/auth/LoginPage.vue`
- Create: `frontend/src/features/auth/ChangePasswordPage.vue`
- Create: `frontend/src/layout/AppLayout.vue`
- Create: `frontend/src/layout/RoleNavigation.vue`
- Create: `frontend/src/layout/__tests__/RoleNavigation.spec.ts`
- Create: `frontend/src/features/auth/__tests__/auth.store.spec.ts`

**Interfaces:**
- Consumes: backend auth endpoints and `CurrentUser`.
- Produces: authenticated SPA shell, route guards, CSRF-aware HTTP client, role-based left navigation.

- [ ] **Step 1: Create the frontend build and failing navigation tests**

Pin `package.json` dependencies to Vue `3.5.22`, Vue Router `4.5.1`, Pinia `3.0.3`, Axios `1.12.2`, Element Plus `2.11.5`, TypeScript `5.9.2`, Vite `7.1.9`, Vitest `3.2.4`, Vue Test Utils `2.4.6`, Playwright `1.55.1`, ExcelJS `4.4.0`, and TSX `4.20.6`; generate and commit `pnpm-lock.yaml`.

```ts
it('shows roster and people links only to leaders', () => {
  const wrapper = mount(RoleNavigation, {
    props: { roles: ['LEADER'] },
    global: { plugins: [router] },
  })

  expect(wrapper.text()).toContain('值班管理')
  expect(wrapper.text()).toContain('人员与可用性')
  expect(wrapper.text()).not.toContain('我要取号')
})
```

Add cases for developer (`工作台`, `我要取号`, `任务中心`) and operator (`工作台`, `任务中心`, `统计`).

- [ ] **Step 2: Run and verify failure**

Run: `corepack pnpm --dir frontend install && corepack pnpm --dir frontend test --run`

Expected: FAIL because the Vue components and stores do not exist.

- [ ] **Step 3: Implement app bootstrap and authentication**

Use Vue 3, Vue Router, Pinia, Axios and Element Plus. Configure Axios with:

```ts
export const http = axios.create({
  baseURL: '/api',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})
```

Route guard behavior:

- unauthenticated users go to `/login`;
- `mustChangePassword=true` users go to `/change-password`;
- authenticated users cannot stay on `/login`;
- forbidden role routes go to `/403`.

- [ ] **Step 4: Implement A-style navigation and B-style queue outlet**

`AppLayout` contains the left role navigation, header with current user and today's duty pair, summary counters, and a full-width main route outlet intended for the task queue.

- [ ] **Step 5: Run tests, type-check, and build**

Run: `corepack pnpm --dir frontend test --run`

Expected: frontend unit tests pass.

Run: `corepack pnpm --dir frontend exec vue-tsc --noEmit`

Expected: no TypeScript errors.

Run: `corepack pnpm --dir frontend build`

Expected: Vite production build succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat: add authenticated role-based frontend shell"
```

---

### Task 11: Developer Take-Number Flow and All-Task Queue

**Files:**
- Create: `frontend/src/features/tasks/task.types.ts`
- Create: `frontend/src/features/tasks/task.api.ts`
- Create: `frontend/src/features/tasks/TaskCreatePage.vue`
- Create: `frontend/src/features/tasks/TaskQueuePage.vue`
- Create: `frontend/src/features/tasks/TaskFilters.vue`
- Create: `frontend/src/features/tasks/TaskDetailPage.vue`
- Create: `frontend/src/features/tasks/TaskActions.vue`
- Create: `frontend/src/features/tasks/__tests__/TaskCreatePage.spec.ts`
- Create: `frontend/src/features/tasks/__tests__/TaskActions.spec.ts`
- Modify: `frontend/src/app/router.ts`

**Interfaces:**
- Consumes: task create/query/detail/call/complete/transfer APIs.
- Produces: complete developer form, all-task queue for operators/leaders, own-task queue for developers, and lifecycle action dialogs.

- [ ] **Step 1: Write failing form tests**

```ts
it('requires a process number for data maintenance', async () => {
  const wrapper = mountTaskCreate()
  await wrapper.get('[data-testid="category"]').setValue('DATA_MAINTENANCE')
  await wrapper.get('[data-testid="system-name"]').setValue('数据平台')
  await wrapper.get('[data-testid="estimated-minutes"]').setValue('45')
  await wrapper.get('[data-testid="operation-start"]').setValue('2026-07-25 20:00')
  await wrapper.get('[data-testid="operation-end"]').setValue('2026-07-25 20:45')
  await wrapper.get('[data-testid="submit-task"]').trigger('click')

  expect(wrapper.text()).toContain('请输入操作流程编号')
  expect(taskApi.create).not.toHaveBeenCalled()
})
```

Add tests for end before start, non-positive estimate, successful display of ticket/assignee/rule, and backend missing-roster message.

- [ ] **Step 2: Write failing action-visibility tests**

Prove:

- only current assignee sees `叫号` for `PENDING`;
- only current assignee sees `填写实际耗时并完成` for `IN_PROGRESS`;
- completed tasks show neither action;
- transfer requires target and nonblank reason;
- developer detail never renders operational action buttons.

- [ ] **Step 3: Run and verify failure**

Run: `corepack pnpm --dir frontend test --run src/features/tasks`

Expected: FAIL because task pages do not exist.

- [ ] **Step 4: Implement task pages and filters**

Queue columns: ticket number, category, system, process number, operation range, status, creator, assignee, estimate, actual minutes. Filters map one-to-one to backend query fields. The operator dashboard route supplies today's Asia/Shanghai date by default; the task-center route does not force a date. The system-name field calls `/api/tasks/system-names` after two typed characters and displays distinct historical suggestions without requiring selection.

- [ ] **Step 5: Implement detail actions**

After call, complete or transfer:

1. disable the submit button while pending;
2. show backend validation/conflict text;
3. refresh task detail and timeline on success;
4. refresh dashboard counters;
5. never optimistically change the assignee or status before the API succeeds.

- [ ] **Step 6: Run tests and build**

Run: `corepack pnpm --dir frontend test --run`

Expected: all frontend unit tests pass.

Run: `corepack pnpm --dir frontend exec vue-tsc --noEmit && corepack pnpm --dir frontend build`

Expected: type-check and production build succeed.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "feat: add task submission and operations queue"
```

---

### Task 12: Leader Management, Roster Import, Statistics, and Audit UI

**Files:**
- Create: `frontend/src/features/roster/RosterImportPage.vue`
- Create: `frontend/src/features/roster/RosterPreviewTable.vue`
- Create: `frontend/src/features/people/PeoplePage.vue`
- Create: `frontend/src/features/people/UnavailabilityDialog.vue`
- Create: `frontend/src/features/people/RedistributionDialog.vue`
- Create: `frontend/src/features/reporting/ReportingPage.vue`
- Create: `frontend/src/features/audit/AuditLogPage.vue`
- Create: `frontend/src/features/roster/__tests__/RosterImportPage.spec.ts`
- Create: `frontend/src/features/people/__tests__/RedistributionDialog.spec.ts`
- Modify: `frontend/src/app/router.ts`

**Interfaces:**
- Consumes: identity admin, roster, unavailability, redistribution, reporting and audit APIs.
- Produces: all leader-only management screens and operator reporting screen.

- [ ] **Step 1: Write failing roster UI tests**

Prove:

- selected `.xlsx` uploads to preview;
- row errors render with Excel row numbers;
- confirm remains disabled for invalid preview;
- valid preview lists dates/accounts and enables confirm;
- second confirmation conflict refreshes import status.

- [ ] **Step 2: Write failing redistribution UI tests**

Prove:

- a nonblank unavailability reason is required;
- preview lists only pending tasks;
- executing tasks appear in a separate “需手工调整” notice;
- per-task redistribution failures remain visible after successful rows;
- leader can open the task detail for a failed row.

- [ ] **Step 3: Run and verify failure**

Run: `corepack pnpm --dir frontend test --run src/features/roster src/features/people`

Expected: FAIL because management components do not exist.

- [ ] **Step 4: Implement management pages**

People page supports create, disable, reset password, and role assignment. It never displays generated or stored passwords after submission. Roster page includes exact-template download and import batch history.

- [ ] **Step 5: Implement reporting and audit pages**

Reporting page shows daily task count and monthly completed actual minutes by operator. Audit filters are actor, action, object type, and time range; before/after data is shown in a read-only drawer.

- [ ] **Step 6: Run frontend verification**

Run: `corepack pnpm --dir frontend test --run`

Expected: all frontend tests pass.

Run: `corepack pnpm --dir frontend exec vue-tsc --noEmit && corepack pnpm --dir frontend build`

Expected: type-check and build succeed.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "feat: add leader management and reporting UI"
```

---

### Task 13: Container Images, Compose, Health Checks, and Backup/Restore

**Files:**
- Create: `deploy/api.Dockerfile`
- Create: `deploy/web.Dockerfile`
- Create: `deploy/nginx.conf`
- Create: `compose.yaml`
- Create: `.env.example`
- Create: `scripts/backup.ps1`
- Create: `scripts/restore.ps1`
- Create: `docs/operations.md`
- Create: `backend/src/test/java/com/acme/opsqueue/identity/BootstrapLeaderInitializerTest.java`

**Interfaces:**
- Produces: `docker compose up -d --build`, persistent `mysql_data`, health-gated service startup, explicit backup and guarded restore commands.

- [ ] **Step 1: Write the failing bootstrap test**

```java
@Test
void startupFailsWhenNoLeaderExistsAndBootstrapPasswordIsBlank() {
    assertThatThrownBy(() -> initializer.initialize(
            new BootstrapLeaderProperties("leader", "运维组长", "")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BOOTSTRAP_LEADER_PASSWORD");
}
```

- [ ] **Step 2: Run the test and verify failure**

Run: `mvn -f backend/pom.xml -Dtest=BootstrapLeaderInitializerTest test`

Expected: FAIL until the initializer enforces the exact environment contract.

- [ ] **Step 3: Implement multi-stage Dockerfiles and Nginx**

API image:

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY backend/src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

Web image builds with Node 22, then copies `frontend/dist` into unprivileged Nginx. `nginx.conf` serves `index.html` for unknown non-API routes and proxies `/api/` to `api:8080`.

- [ ] **Step 4: Add Compose and environment contract**

`compose.yaml` must:

- pin MySQL to `mysql:8.4`;
- persist `/var/lib/mysql` in `mysql_data`;
- health-check MySQL with `mysqladmin ping`;
- health-check API at `/actuator/health`;
- health-check web at `/healthz`;
- make API wait for healthy DB and web wait for healthy API;
- expose only web port `${WEB_PORT:-8080}`;
- pass secrets only through environment variables;
- set restart policy `unless-stopped`.

- [ ] **Step 5: Implement guarded PowerShell backup and restore**

`backup.ps1` requires an output directory, creates a timestamped `.sql`, checks `docker compose ps db`, and fails on an empty dump.

`restore.ps1` requires both `-InputFile` and `-ConfirmDatabaseName`. It reads `DB_NAME` from `.env`, refuses when confirmation differs, verifies the resolved input file exists, and pipes only that file to the composed MySQL container.

- [ ] **Step 6: Verify deployment**

Run: `docker compose --env-file .env.example config`

Expected: valid merged configuration with three services and one named volume.

Run: `docker compose --env-file .env.example build`

Expected: both application images build.

Run: `docker compose --env-file .env.example up -d`

Expected: `docker compose ps` reports `db`, `api`, and `web` healthy.

Run: `Invoke-WebRequest http://127.0.0.1:8080/healthz`

Expected: HTTP 200.

- [ ] **Step 7: Run backup and restore verification against a disposable database**

Create one known task, run `backup.ps1`, stop the stack, start with a new explicitly named test volume, run `restore.ps1` with the matching database confirmation, and verify the known task through the API. Do not reuse or delete the primary development volume.

- [ ] **Step 8: Commit**

```bash
git add deploy compose.yaml .env.example scripts docs/operations.md backend
git commit -m "ops: add container deployment and database recovery"
```

---

### Task 14: End-to-End Acceptance and Final Verification

**Files:**
- Create: `frontend/playwright.config.ts`
- Create: `frontend/tests/e2e/auth.setup.ts`
- Create: `frontend/tests/e2e/developer-task-flow.spec.ts`
- Create: `frontend/tests/e2e/operator-call-complete.spec.ts`
- Create: `frontend/tests/e2e/leader-roster-redistribute.spec.ts`
- Create: `frontend/tests/e2e/helpers.ts`
- Create: `frontend/tests/e2e/seed.ts`
- Create: `.env.e2e.example`
- Create: `scripts/e2e-seed.ps1`
- Create: `scripts/verify.ps1`
- Modify: `README.md`

**Interfaces:**
- Consumes: the complete composed system.
- Produces: one repeatable acceptance command and operator/developer/leader usage documentation.

- [ ] **Step 1: Write failing developer and operator E2E tests**

Developer flow:

```ts
test('developer receives a number and sees the assigned operator', async ({ page }) => {
  await login(page, 'dev1', 'Developer-Password-1')
  await page.goto('/tasks/new')
  await page.getByLabel('类别').selectOption('RELEASE')
  await page.getByLabel('系统名称').fill('支付系统')
  await page.getByLabel('预计耗时（分钟）').fill('60')
  await page.getByLabel('操作流程编号').fill('REL-20260725-001')
  await page.getByLabel('操作开始时间').fill('2026-07-25T20:00')
  await page.getByLabel('操作结束时间').fill('2026-07-25T21:00')
  await page.getByRole('button', { name: '取号并自动分配' }).click()
  await expect(page.getByText(/OPS-\d{8}-\d{4}/)).toBeVisible()
  await expect(page.getByText('已分配负责人')).toBeVisible()
})
```

Operator flow logs in as that assignee, opens the task, clicks `叫号`, verifies `执行中`, enters `60` actual minutes, completes, and verifies `已完成`. The developer session then verifies the updated status.

- [ ] **Step 2: Write failing leader E2E test**

Upload a generated roster workbook, confirm preview, mark one operator unavailable, preview pending tasks, run redistribution, and assert executing tasks remain assigned with a manual-adjustment notice.

- [ ] **Step 3: Run E2E tests and verify the intended failure**

Run: `corepack pnpm --dir frontend exec playwright test`

Expected: the global setup fails because the deterministic E2E users and two-day duty roster have not been seeded.

- [ ] **Step 4: Implement deterministic E2E seeding**

`.env.e2e.example` sets `COMPOSE_PROJECT_NAME=opsqueue-e2e`, `WEB_PORT=18080`, a non-production database password, bootstrap leader credentials, and a dedicated JWT key.

`scripts/e2e-seed.ps1` invokes `corepack pnpm --dir frontend exec tsx tests/e2e/seed.ts`. `seed.ts` waits for all three container health checks, logs in with the bootstrap leader, changes the forced initial password, creates `dev1`, `ops1`, `ops2`, and `ops3`, builds an `.xlsx` workbook with ExcelJS, and imports duty rows for the current Asia/Shanghai date and the next date. It treats HTTP 409 “already exists/imported” responses as idempotent success and fails on every other non-2xx response.

`auth.setup.ts` invokes the seed script once, then writes separate Playwright storage states for developer, each operator, and leader. `helpers.ts` exports `todayInShanghai()`, `tomorrowInShanghai()`, `operationDateTime(date, time)`, and `createRosterWorkbook(rows)` so tests do not depend on a fixed calendar date.

- [ ] **Step 5: Run the E2E suite**

Run: `corepack pnpm --dir frontend exec playwright test`

Expected: all developer, operator and leader acceptance scenarios pass. Any product failure requires a focused regression test in the owning task module before changing production code.

- [ ] **Step 6: Add the repeatable verification script**

`scripts/verify.ps1` runs in this exact order and exits on the first failure:

```powershell
$ErrorActionPreference = 'Stop'
mvn -f backend/pom.xml test
corepack pnpm --dir frontend test --run
corepack pnpm --dir frontend exec vue-tsc --noEmit
corepack pnpm --dir frontend build
docker compose --env-file .env.example config
docker compose --env-file .env.example build
try {
    docker compose -p opsqueue-e2e --env-file .env.e2e.example up -d --build
    powershell -ExecutionPolicy Bypass -File scripts/e2e-seed.ps1
    corepack pnpm --dir frontend exec playwright test
    git diff --check
}
finally {
    docker compose -p opsqueue-e2e --env-file .env.e2e.example down -v
}
```

- [ ] **Step 7: Run full verification**

Run: `powershell -ExecutionPolicy Bypass -File scripts/verify.ps1`

Expected:

- Maven reports zero failures and errors.
- Vitest reports zero failed tests.
- Vue TypeScript check exits 0.
- Vite build exits 0.
- Compose config and image builds exit 0.
- Playwright reports all acceptance scenarios passed.
- `git diff --check` reports no whitespace errors.

- [ ] **Step 8: Update README with exact operator commands**

Document:

- copying `.env.example` to `.env` and replacing every example secret;
- `docker compose up -d --build`;
- first leader login and forced password change;
- roster template import;
- health and log commands;
- backup and guarded restore;
- full verification command.

- [ ] **Step 9: Commit**

```bash
git add frontend .env.e2e.example scripts/e2e-seed.ps1 scripts/verify.ps1 README.md
git commit -m "test: add end-to-end acceptance suite"
```

---

## Final Review Checklist

- [ ] Every design-spec section maps to at least one implementation task.
- [ ] Every backend state mutation has authorization, validation, audit, and transaction tests.
- [ ] Every assignment branch and tie-breaker has a deterministic unit test.
- [ ] Developer task visibility and operator all-task visibility are both tested.
- [ ] Only the current assignee can call, complete, or directly transfer a task.
- [ ] Leader adjustment and pending-only bulk redistribution are tested.
- [ ] `TASK_CALLED` is persisted without any DingTalk network call.
- [ ] Excel import is staged, validated, atomic, and replay-safe.
- [ ] Concurrency tests prove unique ticket numbers and serialized daily allocation.
- [ ] Docker health, backup, guarded restore, and full E2E verification pass.
