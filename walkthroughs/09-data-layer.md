# Lab 09 — The Data Layer: Seeing and Controlling the SQL

## Learning goals

By the end of this lab you will be able to:

- Explain how Spring Data JPA derives SQL from repository method names.
- Enable SQL logging in a Spring Boot application and read the output.
- Identify when Hibernate fires extra queries due to lazy loading (N+1 problem).
- Replace an auto-derived query with an explicit `@Query` annotation.
- Read the database schema that Hibernate generates from entity annotations.

---

## Background

The data layer of SecureTask has a property that trips up almost every developer the first time: **there is no SQL in the source code**.

All five repository interfaces (`TaskRepository`, `UserRepository`, `AttachmentRepository`, `RefreshTokenRepository`, `WebhookRepository`) extend `JpaRepository` and declare only method signatures — no implementations, no SQL strings. Spring Data JPA reads each method name at startup, parses it according to a naming convention, and generates a Hibernate query automatically.

That query is then compiled to SQL by Hibernate and sent to the database at runtime. Without SQL logging enabled you cannot see it. You can only see its side-effects.

This makes the data layer feel like a black box. The goal of this lab is to open it.

---

## The three reasons SQL is invisible by default

### 1. Logging is explicitly off

In `api/src/main/resources/application.properties`:

```properties
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false
```

No SQL is ever printed to the console or log files.

### 2. All queries are generated from method names

Spring Data JPA turns method names into queries by parsing keywords like `findBy`, `And`, `OrderBy`, `Desc`, `Before`. The full table of keywords is in the [Spring Data JPA reference docs](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#repository-query-keywords).

Here is every method in `TaskRepository` and the SQL each one generates:

| Method name | Generated SQL |
|---|---|
| `findByOwner(User owner)` | `SELECT … FROM tasks WHERE owner_id = ?` |
| `findByOwnerOrderByPinnedDescCreatedAtDesc(User owner)` | `SELECT … FROM tasks WHERE owner_id = ? ORDER BY pinned DESC, created_at DESC` |
| `findByIdAndOwner(Long id, User owner)` | `SELECT … FROM tasks WHERE id = ? AND owner_id = ?` |

And in `RefreshTokenRepository`:

| Method name | Generated SQL |
|---|---|
| `findByTokenHash(String hash)` | `SELECT … FROM refresh_tokens WHERE token_hash = ?` |
| `deleteByUser(User user)` | `DELETE FROM refresh_tokens WHERE user_id = ?` |
| `deleteByExpiresAtBefore(Instant t)` | `DELETE FROM refresh_tokens WHERE expires_at < ?` |

**Key rule:** field names in method names map to column names via the entity's field-to-column mapping, not to the raw column name. If the entity field is `createdAt`, the keyword is `CreatedAt`, and Hibernate converts it to `created_at` (snake_case) automatically.

### 3. Lazy loading fires hidden secondary queries

Every `@ManyToOne(fetch = FetchType.LAZY)` in this project means Hibernate does **not** load the related entity in the initial query. Instead, it creates a proxy object. The moment any code accesses a field on that proxy (e.g., `task.getOwner().getUsername()`), Hibernate fires a second `SELECT` to load it.

This is called the **N+1 problem**: one query to load N tasks, then N more queries to load each owner. With logging disabled, all N secondary queries happen silently.

The affected relationships in this codebase:

| Entity | Lazy field | Secondary query triggered by |
|---|---|---|
| `Task` | `owner` (`User`) | `task.getOwner()` in `TaskResponse.from()` |
| `Attachment` | `task` (`Task`) | `attachment.getTask()` in service code |

---

## Relevant files

| File | Purpose |
|------|---------|
| `api/src/main/java/com/securetask/repository/TaskRepository.java` | Derived-query repository — main focus of this lab |
| `api/src/main/java/com/securetask/repository/UserRepository.java` | Contains the one hand-written `@Query` in the project |
| `api/src/main/java/com/securetask/repository/RefreshTokenRepository.java` | Uses derived delete methods |
| `api/src/main/java/com/securetask/entity/Task.java` | JPA entity — columns, types, lazy associations |
| `api/src/main/java/com/securetask/entity/User.java` | JPA entity — cascades, lazy associations |
| `api/src/main/java/com/securetask/service/TaskService.java` | Calls the repository methods — trace queries from here |
| `api/src/main/resources/application.properties` | SQL logging disabled here |
| `api/src/main/resources/application-local.properties` | SQL logging enabled here (local profile only) |

---

## Step-by-step guide

### Step 1 — Enable SQL logging for local development

Open `api/src/main/resources/application-local.properties`. You will see this block, which was added to make the SQL layer visible:

```properties
# Print every SQL statement to stdout, formatted for readability.
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# Print the actual bind-parameter values that replace the ? placeholders.
logging.level.org.hibernate.orm.jdbc.bind=TRACE

# Count how many queries fire per transaction — exposes N+1 problems.
spring.jpa.properties.hibernate.generate_statistics=true
logging.level.org.hibernate.stat=DEBUG
```

These settings are in the `local` profile only. They do not affect the production `application.properties`.

Start the application with the local profile:

```bash
./gradlew :api:bootRun --args='--spring.profiles.active=local'
```

### Step 2 — Observe a simple query

Register a user and then call `GET /api/tasks`. You will see output like this in the console:

```
Hibernate:
    select
        t1_0.id,
        t1_0.owner_id,
        t1_0.title,
        t1_0.description,
        t1_0.status,
        t1_0.created_at,
        t1_0.updated_at,
        t1_0.completed_at,
        t1_0.pinned
    from
        tasks t1_0
    where
        t1_0.owner_id=?
    order by
        t1_0.pinned desc,
        t1_0.created_at desc

org.hibernate.orm.jdbc.bind - binding parameter (1:BIGINT) <- [3]
```

Two things to notice:
1. The `?` in the query is replaced with its actual value (`3`, the owner's id) thanks to `logging.level.org.hibernate.orm.jdbc.bind=TRACE`.
2. The `ORDER BY pinned DESC, created_at DESC` clause was derived entirely from the method name `findByOwnerOrderByPinnedDescCreatedAtDesc`.

**Exercise:** Create three tasks and then call `GET /api/tasks`. Count the number of SQL statements that appear. Is it 1 or more?

### Step 3 — Find the N+1 queries

`TaskResponse.from(Task task)` calls `task.getOwner()` to populate the response. Because `Task.owner` is `FetchType.LAZY`, Hibernate has not loaded the owner yet when the task list query runs.

Open `api/src/main/java/com/securetask/dto/TaskResponse.java` and find the `from()` method. Notice the call to `task.getOwner()`.

Now look at the Hibernate statistics block that appears at the end of each request in the console:

```
StatisticsImpl - Session Metrics {
    ...
    17 nanoseconds spent executing 1 JDBC statements;
    ...
}
```

Create five tasks, call `GET /api/tasks`, and count the JDBC statements reported. If you see more than 1, you are observing N+1.

**Question:** Which line of code in `TaskService.listOwn()` triggers the secondary owner queries?

### Step 4 — Find the one hand-written query in the project

Open `api/src/main/java/com/securetask/repository/UserRepository.java` and find this method:

```java
@Query("SELECT COUNT(u) FROM User u")
long countUsers();
```

This uses JPQL (Java Persistence Query Language), not SQL. JPQL looks like SQL but operates on **entity class names and field names**, not table names and column names.

| JPQL | What it means |
|---|---|
| `FROM User u` | from the `users` table (via the `User` entity) |
| `COUNT(u)` | count rows |

Hibernate translates this JPQL to:

```sql
SELECT count(u1_0.id) FROM users u1_0
```

**Exercise:** Call any endpoint that triggers the `countUsers()` method. Find the translated SQL in the console output and confirm the table name matches the `@Table(name = "users")` annotation on the `User` entity, not the class name.

### Step 5 — Read the generated schema

Add these two lines temporarily to `application-local.properties`:

```properties
spring.jpa.properties.javax.persistence.schema-generation.scripts.action=create
spring.jpa.properties.javax.persistence.schema-generation.scripts.create-target=schema-dump.sql
```

Start the application once. A file named `schema-dump.sql` appears in the project root. Open it.

You will see the `CREATE TABLE` statements that Hibernate derives from the entity annotations:

```sql
CREATE TABLE tasks (
    id           BIGSERIAL NOT NULL,
    owner_id     BIGINT NOT NULL,
    title        VARCHAR(200) NOT NULL,
    description  VARCHAR(2000),
    status       VARCHAR(32) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    pinned       BOOLEAN NOT NULL,
    PRIMARY KEY (id)
);
```

Trace each column back to its annotation in `Task.java`:

| Column | Annotation source |
|---|---|
| `title VARCHAR(200)` | `@Column(nullable = false, length = 200)` |
| `status VARCHAR(32)` | `@Enumerated(EnumType.STRING)` + `@Column(length = 32)` |
| `created_at … NOT NULL` | `@Column(nullable = false, updatable = false)` |
| `owner_id` | `@ManyToOne` + `@JoinColumn(name = "owner_id")` |

Remove those two lines from `application-local.properties` when you are done — the file is only useful once and will cause Hibernate to recreate it on every start.

### Step 6 — Replace a derived query with an explicit `@Query`

Suppose you want to change `findByOwnerOrderByPinnedDescCreatedAtDesc` to only return `OPEN` tasks. A derived method name for that would be `findByOwnerAndStatusOrderByPinnedDescCreatedAtDesc` — readable but unwieldy. An explicit `@Query` is clearer:

```java
// In TaskRepository.java — replace the existing derived method:

@Query("SELECT t FROM Task t WHERE t.owner = :owner AND t.status = com.securetask.entity.TaskStatus.OPEN ORDER BY t.pinned DESC, t.createdAt DESC")
List<Task> findOpenByOwnerOrderByPinnedDescCreatedAtDesc(@Param("owner") User owner);
```

After making the change, restart the app and call `GET /api/tasks`. In the console you will see the new `WHERE status = 'OPEN'` clause in the formatted SQL output.

To use native SQL instead of JPQL, add `nativeQuery = true` and use the real column/table names:

```java
@Query(
    value = "SELECT * FROM tasks WHERE owner_id = :ownerId AND status = 'OPEN' ORDER BY pinned DESC, created_at DESC",
    nativeQuery = true
)
List<Task> findOpenByOwnerNative(@Param("ownerId") Long ownerId);
```

Native queries bypass JPQL translation entirely — what you write is exactly what PostgreSQL executes. This is useful when you need database-specific features (window functions, `RETURNING`, full-text search) that JPQL cannot express.

**Undo this change before moving on** — the existing query intentionally returns all statuses.

---

## How to read the console output

When logging is enabled, each request produces output in this order:

```
1.  Hibernate: <formatted SQL>           — the query that was sent
2.  o.h.o.j.bind — binding parameter …  — the ? values (TRACE level)
3.  StatisticsImpl — Session Metrics {   — summary at end of transaction
        N nanoseconds spent executing M JDBC statements;
    }
```

If you see `M JDBC statements` where M is greater than 1 for a single list endpoint, investigate whether lazy loading is firing secondary queries.

---

## Manual test checklist

- [ ] Start the app with `-Dspring.profiles.active=local` and confirm SQL appears in the console
- [ ] Call `GET /api/tasks` and identify the `ORDER BY` clause in the output
- [ ] Confirm the `?` placeholder is replaced with a real value in the bind-parameter line
- [ ] Create 5 tasks, call `GET /api/tasks`, and count the JDBC statements in the statistics output
- [ ] Find and read the one `@Query` JPQL method in `UserRepository`
- [ ] Generate `schema-dump.sql` and trace three columns back to their entity annotations
- [ ] Add a `@Query` override to `TaskRepository`, restart, and confirm the SQL changes in the log

---

## Expected results

| Action | Expected console output |
|---|---|
| `GET /api/tasks` (1 user, 3 tasks) | At least 1 `SELECT … FROM tasks` query |
| Bind parameter line | `binding parameter (1:BIGINT) <- [<owner_id>]` |
| Statistics after `GET /api/tasks` | `M JDBC statements` where M ≥ 1 |
| `@Query` JPQL on `countUsers()` | `SELECT count(u1_0.id) FROM users u1_0` |
| `schema-dump.sql` after generation | `CREATE TABLE tasks`, `CREATE TABLE users`, etc. |

---

## Common mistakes

**Mistake:** Adding SQL logging to the production `application.properties`.  
**Why it matters:** SQL logs expose query patterns, table names, and parameter values to anyone who can read logs. In a cloud environment, logs may be shipped to third-party aggregators. Keep SQL logging in the `local` profile only.

**Mistake:** Assuming the method name `findByOwnerOrderByPinnedDescCreatedAtDesc` is readable enough — never checking what SQL it actually produces.  
**Why it matters:** A small typo in a method name generates different SQL silently. `findByOwnerOrderByPinnedAscCreatedAtDesc` and `findByOwnerOrderByPinnedDescCreatedAtDesc` look similar but produce different ordering. You only discover this by reading the generated SQL.

**Mistake:** Accessing a `LAZY` association outside a transaction.  
**Why it matters:** Hibernate holds a database connection open for the duration of a transaction. If you call `task.getOwner()` after the transaction has closed (e.g., in a controller that received the entity from a service), Hibernate cannot load the proxy — it throws `LazyInitializationException`. The service layer in this project avoids this by converting entities to DTOs (`TaskResponse.from()`) inside the `@Transactional` method.

**Mistake:** Using `nativeQuery = true` with JPQL syntax (`FROM Task t` instead of `FROM tasks t`).  
**Why it matters:** A native query bypasses Hibernate's entity mapping entirely. The SQL goes directly to PostgreSQL. Using a class name instead of a table name returns an error at runtime, not at compile time.

**Mistake:** Confusing `@Query` JPQL field names with database column names.  
**Why it matters:** In JPQL, you write `t.createdAt` (the Java field name on the entity). In native SQL, you write `created_at` (the column name). Mixing these causes a runtime `QueryException` that only appears when the query is first executed, not at startup.

---

## Discussions

**Q1: Why does Spring Data JPA derive queries from method names instead of requiring you to write SQL?**

The derivation mechanism removes boilerplate. A method like `findByUsernameAndEmail(String username, String email)` maps to a query that you would otherwise have to write, name, and maintain manually. For simple lookups, derivation is unambiguous and safe.

The trade-off is opacity: the SQL is invisible unless you enable logging. For complex queries — joins, subqueries, aggregates, database-specific functions — derivation does not scale well. The convention is to use derived methods for simple filters and `@Query` (JPQL or native) for everything else.

**Q2: What is the N+1 problem and why is it dangerous at scale?**

When you load a list of N entities and then access a lazy-loaded association on each one, Hibernate fires 1 query for the list and N queries for the associations — N+1 total.

At small scale (5 tasks) this is invisible. At scale (500 tasks), the `GET /api/tasks` endpoint fires 501 queries instead of 1. Each query carries network round-trip latency to the database. At 1ms per query, 501 queries add 500ms of database time to every page load.

The standard fix is to either:
1. Change the association to `FetchType.EAGER` (Hibernate joins in the initial query), or
2. Use `JOIN FETCH` in a `@Query` annotation to explicitly request the join.

Option 2 is generally preferred because it keeps the default fetch type lazy (good for cases where you do not need the association) and only joins when you know you will need the data.

**Q3: What is the difference between JPQL and native SQL in a `@Query` annotation?**

| | JPQL | Native SQL |
|---|---|---|
| Syntax | `FROM Task t WHERE t.owner = :owner` | `FROM tasks t WHERE t.owner_id = :ownerId` |
| Names | Entity class names and Java field names | Table names and column names |
| Database-specific | No — translates to any SQL dialect | Yes — PostgreSQL features work, but the query is not portable |
| Hibernate processing | Hibernate translates to SQL | Hibernate sends the string unchanged |
| Use for | Standard queries | Window functions, `RETURNING`, full-text search, raw performance tuning |

`nativeQuery = true` is the escape hatch. Use it when JPQL cannot express what you need, but be aware that the query will only work on the target database type.

**Q4: Why does `application-local.properties` override `application.properties` instead of modifying it directly?**

Spring Boot's profile mechanism allows a profile-specific properties file to override any value from the base file. When the application starts with `-Dspring.profiles.active=local`, it loads `application.properties` first and then `application-local.properties` on top.

This separation enforces a discipline: production-safe defaults live in `application.properties` (committed, reviewed). Developer-only settings live in `application-local.properties` (also committed, but clearly scoped). Nobody accidentally ships `show-sql=true` to production because the override only activates when the `local` profile is explicitly set.

The same pattern applies to any setting you want to change for development — log levels, mock endpoints, shorter token expiry times — without touching the production baseline.

**Q5: Why is `ddl-auto=update` acceptable for local development but not for production?**

`spring.jpa.hibernate.ddl-auto=update` tells Hibernate to inspect the current database schema at startup and issue `ALTER TABLE` statements to bring it in line with the entity definitions. It is convenient because you never have to write a migration manually when you add a field to an entity.

The risks in production are:

1. **It does not drop columns.** If you remove a field from an entity, the column stays in the database forever. This causes a schema/entity drift that breaks future `update` runs.
2. **It runs before the application is ready.** If a migration is destructive or slow (e.g., adding a `NOT NULL` column to a 50-million-row table), Hibernate applies it with no coordination across multiple app instances.
3. **It cannot express complex migrations.** Renaming a column, changing a type with data transformation, or backfilling values requires SQL that Hibernate's `update` mode cannot generate.

Production deployments should use `ddl-auto=validate` (Hibernate checks the schema matches the entities and refuses to start if it does not) combined with a migration tool like **Flyway** or **Liquibase**, which version-controls each schema change as an explicit SQL script.
