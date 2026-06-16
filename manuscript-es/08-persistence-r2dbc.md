A core service owns its data. Up to here Lumen Lending's loan-origination service
has a controller, DTOs, and a service that returns canned values; this chapter
gives it a real persistence layer. By the end, a loan application you POST is
written to a relational table, read back by id, and listed by status — reactively,
end to end, with no blocking call anywhere on the path.

You will build six pieces: an R2DBC `@Table` entity, a status enum, a reactive
repository, a Flyway migration that creates the schema, a MapStruct mapper between
entity and DTO, and the application service that ties them together. Most of it
will look like ordinary Spring Data — because it is. The one genuinely tricky part,
and the best teaching moment in the chapter, is a four-line `Persistable` trick that
tells R2DBC whether a `save` should INSERT or UPDATE when *you* assign the primary
key. Get that wrong and your second read silently overwrites instead of inserting;
get it right and it is invisible forever.

Everything here lives in `core-lending-loan-origination`, the system-of-record
service from Chapter 1's four-tier map. Cores own schema and data; they expose
reactive CRUD over R2DBC and never share a database with another tier. This is that
core's data layer.

!!! warning "R2DBC, not JPA"
    The prelude warned that JPA, JDBC, and Hibernate are blocking and have no place
    on the reactive stack. That warning is load-bearing here. If you reach for
    `@Entity`, `EntityManager`, or `JpaRepository`, you are back in the servlet
    world and you will stall the event loop. Everything in this chapter is Spring
    Data **R2DBC** — the reactive relational story the prelude introduced.

## The entity: an R2DBC `@Table`

Spring Data R2DBC maps a plain Java class to a table. There is no JPA provider, no
lazy loading, no dirty-checking session — just a lightweight mapping from columns to
fields and back. You mark the class with `@Table`, the primary key with `@Id`, and
each column with `@Column`, naming the snake_case database column explicitly so the
Java camelCase field and the SQL name can differ without surprises.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listing 8.1 — the R2DBC table mapping and UUID primary key
@Table("loan_application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplication implements Persistable<UUID> {

    @Id
    @Column("loan_application_id")
    private UUID loanApplicationId;

    /** Stable public reference, distinct from the surrogate primary key. */
    @Column("application_number")
    private UUID applicationNumber;

    /** Applicant who owns this request (soft reference; no cross-context FK). */
    @Column("applicant_id")
    private UUID applicantId;

    /** Requested principal, stored as a fixed-scale decimal. */
    @Column("requested_amount")
    private BigDecimal requestedAmount;

    /** ISO-4217 currency code of {@link #requestedAmount} (e.g. {@code EUR}). */
    @Column("currency")
    private String currency;

    /** Requested repayment term in whole months. */
    @Column("term_months")
    private Integer termMonths;

    /** Free-text purpose of the loan (e.g. {@code HOME_IMPROVEMENT}). */
    @Column("purpose")
    private String purpose;

    @Column("status")
    private ApplicationStatus status;

    /** Reason captured when the application is rejected or cancelled. */
    @Column("decision_reason")
    private String decisionReason;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
:::

A few things to notice. The Lombok annotations (`@Data`, `@Builder`,
`@NoArgsConstructor`, `@AllArgsConstructor`) generate the boilerplate — accessors,
a builder, the two constructors R2DBC's mapping needs. The primary key is a
`UUID`, not a database-generated `BIGSERIAL`; the application assigns it before
the first save, which keeps ids opaque and lets a caller mint one without a round
trip. And the `status` field is the `ApplicationStatus` enum, which R2DBC stores
as a string in a `VARCHAR` column — more on that with the migration.

!!! note "Key term — surrogate vs. business key"
    `loanApplicationId` is the **surrogate key**: an internal UUID with no meaning
    beyond identity, used for joins and lookups. `applicationNumber` is a
    **business key**: a stable public reference you can quote to a customer. Keeping
    them separate means you can change how either is generated without breaking the
    other — and the repository can look an application up by either.

## The status enum

The lifecycle of an application is a small closed set of states, so it is an enum.
R2DBC needs no special configuration to persist it: by default it maps an enum to
its `name()` as a string, which is exactly what the `VARCHAR(32)` status column
expects.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/ApplicationStatus.java | Listing 8.2 — the lifecycle enum, stored as a string
public enum ApplicationStatus {

    /** Captured but not yet submitted for review. */
    DRAFT,

    /** Submitted by the applicant; awaiting a credit officer. */
    SUBMITTED,

    /** A credit officer is actively reviewing the application. */
    UNDER_REVIEW,

    /** Approved; an offer may be proposed to the applicant. */
    APPROVED,

    /** Declined; a terminal state. */
    REJECTED,

    /** Withdrawn before a decision was reached; a terminal state. */
    CANCELLED;

    /**
     * @return {@code true} if no further transition is allowed from this state
     */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
:::

Storing the `name()` rather than the ordinal is the safe choice: adding or
reordering enum constants later never silently re-labels existing rows. The column
is wide enough (`VARCHAR(32)`) for the longest name with room to grow.

## The `Persistable` trick: insert versus update

Here is the subtle part. Spring Data's `save` has to decide, for each entity,
whether to emit an `INSERT` or an `UPDATE`. With a database-generated id, the rule
is easy: a `null` id means "never saved, so INSERT," and a non-null id means
"already has a key, so UPDATE." That heuristic is what Spring Data uses by default.

But Lumen assigns the UUID *itself*, before the first save. By the time the entity
reaches the repository its id is already non-null — so the default heuristic
concludes "this must be an update," issues an `UPDATE ... WHERE id = ?`, matches
zero rows, and the insert you intended silently never happens. This is a classic
R2DBC footgun with client-assigned keys, and it fails quietly: no exception, just a
row that was never written.

The fix is to stop letting R2DBC guess. Implement `Persistable<UUID>` and answer
the question explicitly with an `isNew()` method, backed by a `@Transient` flag that
is never written to the database.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listing 8.3 — telling R2DBC "this is new" explicitly
    /**
     * Transient flag (never persisted) that tells Spring Data R2DBC whether a
     * {@code save} should INSERT or UPDATE. Because the primary key is a
     * client-assigned UUID rather than a DB-generated value, R2DBC cannot infer
     * "new vs. existing" from a null id; {@link Persistable} makes it explicit.
     */
    @Transient
    @Builder.Default
    private boolean newEntity = false;

    /** {@inheritDoc} */
    @Override
    public UUID getId() {
        return loanApplicationId;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    /**
     * Marks this aggregate as a freshly created entity so the next {@code save}
     * performs an INSERT.
     *
     * @return this aggregate, for chaining
     */
    public LoanApplication markNew() {
        this.newEntity = true;
        return this;
    }
:::

The `@Transient` annotation keeps `newEntity` out of the mapping, so there is no
`new_entity` column and the flag never touches the wire. `getId()` returns the
surrogate key; `isNew()` returns the flag. When the service is about to save a
brand-new application it calls `markNew()`, R2DBC sees `isNew() == true`, and an
`INSERT` is issued regardless of the non-null id. On any later `save` the loaded
entity has `newEntity == false`, so an `UPDATE` is issued — which is exactly right.

!!! warning "Default the flag to false, not true"
    The flag defaults to `false` so that an application *loaded* from the database
    — which never calls `markNew()` — is correctly treated as an update. Only the
    create path opts into INSERT. Defaulting to `true` would make every save after
    a read try to re-insert a row that already exists, and you would get duplicate-
    key errors instead of updates.

!!! spring "Spring parity"
    This is pure Spring Data, not a Firefly addition. In Spring Data JPA you rarely
    meet this problem because Hibernate tracks entity state in its persistence
    context. R2DBC has no session and no dirty-checking, so when *you* own the key
    you must own the insert/update decision too — and `Persistable<T>` is Spring
    Data's standard, documented hook for doing exactly that. Firefly changes none of
    this; it just keeps the reactive stack underneath it non-blocking.

## The reactive repository

With the entity in place, the repository is a one-liner plus two derived queries.
Extend `ReactiveCrudRepository<LoanApplication, UUID>` and Spring Data generates a
reactive implementation at runtime: `save` returns `Mono<LoanApplication>`,
`findById` returns `Mono<LoanApplication>`, `findAll` returns
`Flux<LoanApplication>`. You add finder methods by *naming* them, and Spring Data
parses the name into a query.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/persistence/LoanApplicationRepository.java | Listing 8.4 — a reactive repository with derived queries
public interface LoanApplicationRepository
        extends ReactiveCrudRepository<LoanApplication, UUID> {

    /**
     * @param status lifecycle state to match
     * @return every application currently in {@code status}
     */
    Flux<LoanApplication> findByStatus(ApplicationStatus status);

    /**
     * @param applicationNumber the stable public reference
     * @return the matching application, if any
     */
    Mono<LoanApplication> findByApplicationNumber(UUID applicationNumber);
}
:::

`findByStatus` returns a `Flux` because many applications can share a status;
`findByApplicationNumber` returns a `Mono` because the business key is unique. You
write no SQL and no implementation class — the method names *are* the query, and
Spring Data builds `WHERE status = ?` and `WHERE application_number = ?` for you.

!!! note "Key term — derived query"
    A **derived query** is a repository method whose name Spring Data parses into a
    query: `findBy` + property names + optional keywords (`And`, `OrderBy`,
    `Between`). It is the fastest way to add a finder, with no SQL to maintain — at
    the cost of long method names once the criteria grow. For anything richer, you
    annotate the method with `@Query`, or — for open-ended list filtering — you
    reach for the filter engine described at the end of this chapter.

## The schema: a Flyway migration

R2DBC maps to a table; something has to *create* that table. Lumen uses **Flyway**,
which applies versioned SQL migration scripts in order and records which have run, so
the schema is reproducible from an empty database and evolves in tracked steps. A
migration file is named `V<version>__<description>.sql`; Flyway runs `V1` before
`V2`, once each, and refuses to silently change one that has already been applied.

::: listing core-lending-loan-origination/src/main/resources/db/migration/V1__loan_application.sql | Listing 8.5 — the first migration creates the table
CREATE TABLE loan_application (
    loan_application_id UUID PRIMARY KEY,
    application_number  UUID NOT NULL,
    applicant_id        UUID NOT NULL,
    requested_amount    NUMERIC(19, 2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    term_months         INTEGER NOT NULL,
    purpose             VARCHAR(255) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    decision_reason     VARCHAR(1000),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX ux_loan_application_application_number
    ON loan_application (application_number);

CREATE INDEX ix_loan_application_status
    ON loan_application (status);
:::

Read this against Listing 8.1 column by column: every `@Column("...")` maps to a
column here. The `loan_application_id` is the `PRIMARY KEY` (the surrogate);
`application_number` carries a `UNIQUE` index because the business key must not
repeat; `status` gets a plain index because `findByStatus` filters on it. The enum
lands in `VARCHAR(32)`, the amount in `NUMERIC(19, 2)` to hold money without
floating-point error, and the two audit timestamps are `NOT NULL`.

This same script runs unchanged against in-memory H2 in tests and against
production-shaped Postgres — it is deliberately written in portable, H2-compatible
DDL so the test you run in a moment exercises the *real* migration, not a mock.

!!! spring "Spring parity"
    This is stock Spring Boot. Flyway auto-configuration sees `flyway-core` on the
    classpath and runs anything under `src/main/resources/db/migration` on startup.
    On the reactive stack Flyway still uses a short-lived blocking JDBC connection
    *at boot only* to apply migrations — that is fine, because it happens once
    before the event loop starts serving traffic; the request path stays R2DBC and
    non-blocking. Firefly leaves this wiring exactly as Spring Boot ships it.

## The mapper: entity to DTO with MapStruct

The repository deals in entities; the web layer deals in DTOs. **MapStruct**
generates the conversion code at compile time from an interface you declare, so you
get fast, allocation-light mapping with no reflection and no hand-written copy loops.
Declaring `componentModel = SPRING` makes the generated implementation a Spring bean
you can inject like any other.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/mapper/LoanApplicationMapper.java | Listing 8.6 — a MapStruct mapper, half generated and half hand-written
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LoanApplicationMapper {

    /**
     * Projects a persisted aggregate to its API response shape.
     *
     * @param entity the persisted application
     * @return the response DTO
     */
    LoanApplicationResponse toResponse(LoanApplication entity);

    /**
     * Builds a new, unsaved {@link LoanApplication} from a create request,
     * normalising the currency and starting the aggregate in
     * {@link com.firefly.lumen.core.domain.ApplicationStatus#DRAFT}. The
     * identifiers and timestamps are assigned by the service before persisting.
     *
     * @param request the validated create request
     * @return a transient entity ready to be submitted and saved
     */
    default LoanApplication toNewEntity(CreateLoanApplicationRequest request) {
        return LoanApplication.builder()
                .applicantId(request.applicantId())
                .requestedAmount(request.requestedAmount())
                .currency(request.currency() == null ? null : request.currency().toUpperCase())
                .termMonths(request.termMonths())
                .purpose(request.purpose())
                .build();
    }
}
:::

`toResponse` is fully generated: MapStruct matches entity fields to DTO fields by
name and writes the implementation for you, so the mechanical entity-to-response
projection costs you one method signature. `toNewEntity` is a `default` method you
write by hand, because building a new application is not a field-for-field copy — it
normalizes the currency to upper case and deliberately leaves the id, status, and
timestamps unset, because the *service* owns those defaults. Mixing generated and
hand-written methods in one mapper is idiomatic MapStruct: let it generate the dull
copies, take over where there is real logic.

## The service: tying it together reactively

The application service is where the pieces compose. It is constructor-injected with
the repository and mapper, is marked `@Transactional`, and returns reactive types
throughout — so nothing blocks. This is also where the `Persistable` trick pays off:
the create path mints the ids, sets the timestamps, submits the application through
its domain method, calls `markNew()`, and only then saves.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listing 8.7 — the create and read paths, fully reactive
    /**
     * Opens a new application, submits it, persists it, and returns the view.
     *
     * @param request the validated create request
     * @return the persisted application as a response DTO
     */
    public Mono<LoanApplicationResponse> create(CreateLoanApplicationRequest request) {
        LoanApplication application = mapper.toNewEntity(request);
        application.setLoanApplicationId(UUID.randomUUID());
        application.setApplicationNumber(UUID.randomUUID());
        application.setStatus(ApplicationStatus.DRAFT);
        application.setCreatedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());
        application.submit();
        application.markNew();
        return repository.save(application).map(mapper::toResponse);
    }

    /**
     * Fetches one application by its surrogate id.
     *
     * @param id the loan-application id
     * @return the application
     * @throws ResourceNotFoundException if no application has that id
     */
    @Transactional(readOnly = true)
    public Mono<LoanApplicationResponse> getById(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("Loan application not found: " + id)));
    }
:::

Trace the create path: build a transient entity from the request, assign both UUIDs,
stamp the status and timestamps, run `submit()` (the domain method that moves the
application from `DRAFT` to `SUBMITTED`), then `markNew()` so R2DBC issues an
`INSERT`. The save returns `Mono<LoanApplication>`, and `.map(mapper::toResponse)`
turns it into the `Mono<LoanApplicationResponse>` the controller returns. No
`.block()`, no `subscribe()` — the framework subscribes at the edge.

The read path shows the framework's error model. `findById` returns an empty `Mono`
when there is no row; `switchIfEmpty` turns that emptiness into a
`ResourceNotFoundException`, and the web layer renders it as an RFC 7807 problem
detail automatically. There is no hand-written 404 anywhere — exactly the "one error
model, everywhere" promise from Chapter 1.

The `list` method rounds it out, switching between `findAll` and the derived
`findByStatus` depending on whether a status filter was supplied:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listing 8.8 — listing, with and without a status filter
    @Transactional(readOnly = true)
    public Flux<LoanApplicationResponse> list(ApplicationStatus status) {
        Flux<LoanApplication> source = (status == null)
                ? repository.findAll()
                : repository.findByStatus(status);
        return source.map(mapper::toResponse);
    }
:::

!!! spring "Spring parity"
    `@Transactional` works on the reactive stack too, but it manages a *reactive*
    transaction bound to the Reactor context rather than a thread-local one — which
    is why it must never wrap a blocking call. `@Transactional(readOnly = true)` on
    the read paths is the same hint you would give in MVC: it lets the data layer
    skip dirty-tracking work for queries. Same annotations, reactive semantics
    underneath.

## Run it

Because the migration is portable DDL and the test boots against in-memory H2 with
the R2DBC runtime, the whole persistence layer is verifiable with no Docker and no
external database. The controller slice test creates an application over HTTP, reads
it back, asserts the 404 problem detail, and rejects an invalid payload — all
against the real Flyway-migrated schema. Run it from `samples/lumen-lending`:

```text
mvn -q -pl core-lending-loan-origination test
```

You should see the three tests pass:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

!!! tip "Checkpoint"
    Run `mvn -q -pl core-lending-loan-origination test` from `samples/lumen-lending`.
    A green `Tests run: 3, Failures: 0` means Flyway applied `V1__loan_application.sql`
    to H2, the `Persistable` flag drove a real `INSERT`, `findById` read the row
    back, and a missing id produced an RFC 7807 404 — the entire round trip,
    verified. If the create test fails with a duplicate-key or "0 rows updated"
    symptom, the first thing to check is the `markNew()` call and the `isNew()`
    flag's default.

## The production path for list endpoints

`list(status)` is honest but blunt: one optional filter, no paging, no sorting. A
real core service exposes list endpoints with arbitrary filtering, stable
pagination, and a consistent response envelope — and Lumen's loan-origination slice
keeps that surface small on purpose. The framework's answer, which production
Firefly services use, is a **generic reflective filter engine** paired with a
`PaginationRequest`/`PaginationResponse` pair. You describe a filter DTO, annotate
the fields that may be filtered, and the engine builds the query and the paged
response for you — no derived-method explosion, no per-endpoint paging boilerplate.

Conceptually, a filterable request and a paged call look like this. (This is an
illustrative sketch of the framework's shape — the built loan-origination slice does
not wire the filter engine; it uses the derived queries above.)

```java
// Illustrative: the framework's filter + pagination surface, not Lumen's slice.
public record LoanApplicationFilter(
        @FilterableId UUID applicantId,
        ApplicationStatus status) {}

Mono<PaginationResponse<LoanApplicationResponse>> page =
        filterService.filter(
                new LoanApplicationFilter(applicantId, ApplicationStatus.SUBMITTED),
                PaginationRequest.of(/* page */ 0, /* size */ 20));
```

The point is the *path*, not the syntax: when a list endpoint outgrows a derived
query, you do not hand-roll page/size/sort DTOs and a bespoke query builder in every
service — the enterprise tax Chapter 1 named. You declare the filter, annotate the
filterable fields, and inherit a uniform, reactive, paged list endpoint. Lumen's
slice stays with derived queries because two finders are all it needs; reach for the
filter engine the moment a real filtering surface appears.

!!! note "Key term — `@FilterableId`"
    `@FilterableId` marks an id-typed field on a filter DTO as something the engine
    may filter by, with the right type handling for UUID keys. It is the filter
    engine's way of saying "this field is a safe, indexed filter target" — the
    opt-in that keeps reflective filtering from becoming an open query surface over
    every field.

## What you built {.recap}

- A Spring Data **R2DBC `@Table` entity**, `LoanApplication`, with a UUID `@Id`,
  snake_case `@Column` mappings, and an `ApplicationStatus` enum stored as a string.
- The **`Persistable<UUID>` trick**: a `@Transient` `newEntity` flag plus `isNew()`,
  so R2DBC issues an `INSERT` for client-assigned keys instead of a silent,
  zero-row `UPDATE` — and an `UPDATE` for everything loaded from the database.
- A **reactive repository** extending `ReactiveCrudRepository` with two derived
  queries (`findByStatus`, `findByApplicationNumber`) and no hand-written SQL.
- A **Flyway migration**, `V1__loan_application.sql`, in portable DDL that runs on
  H2 in tests and Postgres in production from the same file.
- A **MapStruct mapper** that generates `toResponse` and hand-writes `toNewEntity`,
  and a `@Transactional` **service** that composes them reactively — with a
  `ResourceNotFoundException` that the framework renders as an RFC 7807 404.
- A green `Tests run: 3, Failures: 0` from `mvn -q -pl core-lending-loan-origination
  test`, proving the round trip against the real migrated schema.

## Try it yourself {.exercises}

1. **Watch the trick fail.** In `LoanApplicationService.create`, comment out the
   `application.markNew()` line and rerun `mvn -q -pl core-lending-loan-origination
   test`. Observe how the create test breaks, then restore the line and explain in
   one sentence why the non-null UUID id made R2DBC choose `UPDATE`.
2. **Add a derived finder.** Add `Flux<LoanApplication> findByApplicantId(UUID
   applicantId)` to `LoanApplicationRepository` and a thin `list`-style service
   method that uses it. No SQL — let the method name carry the query.
3. **Extend the schema.** Add a `risk_band VARCHAR(16)` column to
   `V1__loan_application.sql` and a matching `@Column("risk_band")` field on
   `LoanApplication`, then confirm the slice test still goes green against the
   migrated H2 schema.
4. **Map a new field.** Surface your `risk_band` field on `LoanApplicationResponse`
   and confirm MapStruct's generated `toResponse` picks it up automatically by
   name — no change to the mapper interface required.
5. **Sketch the production path.** Without wiring it, write a `LoanApplicationFilter`
   record with a `@FilterableId` applicant id and a status, and note which existing
   service method it would replace once the list endpoint needs paging.

## Where to go next

The core now owns its data. The next layers up the stack put this service to work:
the domain tier orchestrates these CRUD operations into business flows over generated
SDKs, and a saga coordinates the multi-step decision path with compensation when a
step fails. The persistence layer you built here is the system of record everything
above it ultimately writes to.
