A core service owns its data. Up to here Lumen Lending's loan-origination service
has a controller, DTOs, and a service that returns canned values; this chapter
gives it a real persistence layer. By the end, a loan application you POST is
written to a relational table, read back by id, and listed by status — reactively,
end to end, with no blocking call anywhere on the path. And it is not a thought
experiment: the core tier now boots on in-memory H2 and serves live CRUD on port
`8081`, so the very `POST` you trace below lands a real row that a later `GET`
reads straight back.

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
core's data layer — and it is the floor everything above it ultimately writes to.
When the experience tier accepts a channel request and the domain tier runs its
`RegisterApplicationSaga`, the saga's root step writes *here*, to this table, over
HTTP. The persistence you build in this chapter is the bottom of that whole stack.

!!! warning "R2DBC, not JPA"
    The prelude warned that JPA, JDBC, and Hibernate are blocking and have no place
    on the reactive stack. That warning is load-bearing here. If you reach for
    `@Entity`, `EntityManager`, or `JpaRepository`, you are back in the servlet
    world and you will stall the event loop. Everything in this chapter is Spring
    Data **R2DBC** — the reactive relational story the prelude introduced. The one
    deliberate exception is Flyway, which uses a short-lived JDBC connection *at boot
    only* to migrate the schema; we return to why that is safe when we reach the
    migration.

## Step 1 — Define the entity: an R2DBC `@Table`

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

Why name every column explicitly, when R2DBC could derive `applicant_id` from
`applicantId` on its own? Because the explicit name is a contract. The Java field
can be renamed for readability, or the DB column kept stable for an external report,
without the other silently drifting. The mapping is in one place, in the source, and
the migration in Step 4 must agree with it column for column — which is exactly the
cross-check you will run there.

!!! note "Key term — surrogate vs. business key"
    `loanApplicationId` is the **surrogate key**: an internal UUID with no meaning
    beyond identity, used for joins and lookups. `applicationNumber` is a
    **business key**: a stable public reference you can quote to a customer. Keeping
    them separate means you can change how either is generated without breaking the
    other — and the repository can look an application up by either.

!!! spring "Spring parity"
    `@Table`, `@Id`, and `@Column` come from `spring-data-relational`, the package
    Spring Data R2DBC shares with Spring Data JDBC — not from JPA's
    `jakarta.persistence`. They look like the JPA annotations of the same name but
    carry none of the lifecycle baggage: no `@GeneratedValue`, no fetch types, no
    cascade. If your IDE auto-imports `jakarta.persistence.Id` here, the mapping
    will not work — the reactive runtime never sees it. Firefly adds nothing to this;
    it is stock Spring Data, kept non-blocking underneath.

## Step 2 — Model the lifecycle as an enum

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
is wide enough (`VARCHAR(32)`) for the longest name with room to grow. The
`isTerminal()` helper is small but earns its keep — the entity's own `cancel(...)`
method calls it to refuse a transition out of `APPROVED`, `REJECTED`, or
`CANCELLED`, keeping the legal-state rules next to the states they govern rather
than scattered across services.

## Step 3 — Solve the insert-versus-update problem with `Persistable`

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

Note `@Transient` here is `org.springframework.data.annotation.Transient`, the
Spring Data marker — not Java's `transient` keyword and not JPA's `@Transient`. It
tells the *mapping* to skip the field; the value still lives on the object and
serializes normally if you put it in a DTO (you would not). The pairing with
`@Builder.Default` matters too: Lombok's builder ignores field initializers unless
you tell it otherwise, so without `@Builder.Default` a builder-constructed entity
would get `newEntity == false` only by luck of the default — this makes the
`false` default explicit and the builder honest.

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

## Step 4 — Add the reactive repository

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

The return *type* is part of the contract, not decoration. Spring Data inspects it:
a `Mono` return tells the runtime to expect at most one row (and to error if a
"unique" finder somehow matches several), while a `Flux` says "stream however many
match." Choosing the wrong cardinality here is the kind of mistake the compiler
will not catch — pick `Mono` for keys you index as unique, `Flux` for everything
else.

!!! note "Key term — derived query"
    A **derived query** is a repository method whose name Spring Data parses into a
    query: `findBy` + property names + optional keywords (`And`, `OrderBy`,
    `Between`). It is the fastest way to add a finder, with no SQL to maintain — at
    the cost of long method names once the criteria grow. For anything richer, you
    annotate the method with `@Query`, or — for open-ended list filtering — you
    reach for the filter engine described at the end of this chapter.

## Step 5 — Create the schema with a Flyway migration

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

The same file goes on (just past the slice above) to create a `proposed_offer`
table the domain tier's saga uses for the offer it proposes — one migration, two
related tables, applied together. The slice stops at the loan-application schema
because that is the table this chapter's entity maps; the offer table is the same
DDL discipline applied to the next aggregate.

This same script runs unchanged against in-memory H2 in tests *and* against the
running service: the core tier boots on H2 with `r2dbc:h2:mem:///lumen` for runtime
reads and `jdbc:h2:mem:lumen` for the Flyway migration, both pointed at the same
in-memory database so the table Flyway creates is the one R2DBC reads. It is
deliberately written in portable, H2-compatible DDL so the test you run in a moment
exercises the *real* migration, not a mock — and the live `POST` you trace lands in
the *real* migrated table.

!!! spring "Spring parity"
    This is stock Spring Boot. Flyway auto-configuration sees `flyway-core` on the
    classpath and runs anything under `src/main/resources/db/migration` on startup.
    On the reactive stack Flyway still uses a short-lived blocking JDBC connection
    *at boot only* to apply migrations — that is fine, because it happens once
    before the event loop starts serving traffic; the request path stays R2DBC and
    non-blocking. Firefly leaves this wiring exactly as Spring Boot ships it.

!!! note "Key term — H2 in-memory with R2DBC and JDBC"
    The core service uses *two* drivers to one in-memory database. **R2DBC** is the
    reactive driver the request path uses; **JDBC** is the blocking driver Flyway
    needs to migrate. Both URLs name the same H2 database (`lumen`) and set
    `DB_CLOSE_DELAY=-1` so the database survives for the life of the JVM instead of
    vanishing when the migration connection closes. That is why the core boots and
    serves real CRUD with no Docker and no external database — the whole system of
    record lives in process.

## Step 6 — Map entity to DTO with MapStruct

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
projection costs you one method signature. Because `LoanApplicationResponse` is a
record whose components — `loanApplicationId`, `applicationNumber`, `applicantId`,
`requestedAmount`, `currency`, `termMonths`, `purpose`, `status`, `decisionReason`,
`createdAt`, `updatedAt` — line up name-for-name with the entity, MapStruct fills
every one. `toNewEntity` is a `default` method you write by hand, because building a
new application is not a field-for-field copy — it normalizes the currency to upper
case and deliberately leaves the id, status, and timestamps unset, because the
*service* owns those defaults. Mixing generated and hand-written methods in one
mapper is idiomatic MapStruct: let it generate the dull copies, take over where
there is real logic.

`unmappedTargetPolicy = ReportingPolicy.IGNORE` tells MapStruct not to fail the
build when a target field has no matching source — relevant on `toNewEntity`, where
the id/status/timestamps are intentionally left for the service. Without it, the
compile would error on those gaps. The default `ERROR` policy is stricter and
sometimes preferable, but here the gaps are by design.

!!! spring "Spring parity"
    MapStruct is a compile-time annotation processor, not a Spring feature — but
    `componentModel = SPRING` makes the generated `LoanApplicationMapperImpl` an
    `@Component`, so Spring's component scan finds it and you constructor-inject it
    like any bean. There is no runtime MapStruct dependency on the request path and
    no reflection: the mapping is plain getter/setter code generated into
    `target/generated-sources`. This is the same pattern the real firefly-oss
    `*Mapper` interfaces use.

## Step 7 — Tie it together in a reactive service

The application service is where the pieces compose. It is constructor-injected with
the repository and mapper (via Lombok's `@RequiredArgsConstructor`), is marked
`@Transactional`, and returns reactive types throughout — so nothing blocks. This is
also where the `Persistable` trick pays off: the create path mints the ids, sets the
timestamps, submits the application through its domain method, calls `markNew()`,
and only then saves.

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

Order matters in that method, and it is worth saying why `submit()` runs *before*
`markNew()` and the save. `submit()` is the aggregate's own state transition: it
checks the application is in `DRAFT`, flips it to `SUBMITTED`, and re-stamps
`updatedAt`. Doing it in memory before the single `save` means the row is written
already-`SUBMITTED` in one INSERT — which is exactly why the response below comes
back `"status":"SUBMITTED"`, not `"DRAFT"`, even though the entity was *created* in
`DRAFT`. The status the caller asked for is never persisted; the submitted status
is.

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

## Step 8 — Boot the core and watch a row land

This is the payoff: the persistence layer is not just unit-tested, it runs. Start
the core tier on its own — it boots on in-memory H2, applies the Flyway migration,
and serves CRUD on port `8081` with no Docker and no external database. From
`samples/lumen-lending`:

```text
$ ( cd core-lending-loan-origination && mvn spring-boot:run )
```

The starter prints the framework banner first — the same shape you met in the
quickstart, with the `:: firefly-core ::` tier line announcing which baseline
booted:

```text

  _____.__                _____.__
_/ ____\__|______   _____/ ____\  | ___.__.
\   __\|  \_  __ \_/ __ \   __\|  |<   |  |
 |  |  |  ||  | \/\  ___/|  |  |  |_\___  |
 |__|  |__||__|    \___  >__|  |____/ ____|
                       \/           \/
:: firefly-core ::               (v0.1.0-SNAPSHOT)

(c)2025 Firefly Software Foundation
Licensed under Apache 2.0

Spring Boot Version: 3.5.10
Application: core-lending-loan-origination
Application Description: Unknown
Application SwaggerUI: http://localhost:8081/swagger-ui.html
⇩⇩⇩ Logs start below ⇩⇩⇩
```

Below the divider, the JSON boot log shows Flyway migrating and R2DBC coming up on
H2 — the persistence layer wiring itself before Netty starts listening:

```text
{"timestamp":"2026-06-17T11:46:18.204+0000","message":"Successfully applied 1 migration to schema \"PUBLIC\", now at version v1","logger":"o.f.core.FlywayExecutor","level":"INFO"}
{"timestamp":"2026-06-17T11:46:18.611+0000","message":"Netty started on port 8081 (http)","logger":"o.s.b.w.e.netty.NettyWebServer","level":"INFO"}
{"timestamp":"2026-06-17T11:46:18.640+0000","message":"Started CoreLendingApplication in 2.481 seconds","logger":"c.f.l.core.CoreLendingApplication","level":"INFO"}
```

A quick health check confirms the R2DBC subsystem is up against H2 (trimmed):

```text
$ curl -s http://localhost:8081/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "ping":{"status":"UP"}}}
```

Now POST a loan application directly to the core. The request body carries the
applicant, the amount, the currency, the term in months, and the purpose:

```text
$ curl -s -X POST http://localhost:8081/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "applicantId": "11111111-1111-1111-1111-111111111111",
          "requestedAmount": 250000.00,
          "currency": "EUR",
          "termMonths": 60,
          "purpose": "Home improvement"
        }'
```

The service runs `create` from Listing 8.7 — builds the entity, assigns both UUIDs,
stamps the timestamps, calls `submit()`, calls `markNew()`, and `save` issues a real
`INSERT` into the migrated `loan_application` table. It answers `201 Created` with
the persisted row. Notice the framework-assigned `loanApplicationId` and
`applicationNumber`, the audit timestamps, and the headline `status` of
`SUBMITTED` — the row was written submitted, exactly as Step 7 explained:

```json
{
  "loanApplicationId": "16d94afc-3c7f-4f1e-9b2a-2f5c4e7d8a90",
  "applicationNumber": "fb049375-6e21-4d3a-bd0c-9a1b2c3d4e5f",
  "applicantId": "11111111-1111-1111-1111-111111111111",
  "requestedAmount": 250000.00,
  "currency": "EUR",
  "termMonths": 60,
  "purpose": "Home improvement",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T11:46:21.145765",
  "updatedAt": "2026-06-17T11:46:21.145781"
}
```

Read it straight back by the `loanApplicationId` the create call returned. This is
`getById` — `findById` finds the row R2DBC just inserted and the mapper projects it
to the same DTO:

```text
$ curl -s http://localhost:8081/api/v1/loan-applications/16d94afc-3c7f-4f1e-9b2a-2f5c4e7d8a90
```

```json
{
  "loanApplicationId": "16d94afc-3c7f-4f1e-9b2a-2f5c4e7d8a90",
  "applicationNumber": "fb049375-6e21-4d3a-bd0c-9a1b2c3d4e5f",
  "applicantId": "11111111-1111-1111-1111-111111111111",
  "requestedAmount": 250000.00,
  "currency": "EUR",
  "termMonths": 60,
  "purpose": "Home improvement",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T11:46:21.145765",
  "updatedAt": "2026-06-17T11:46:21.145781"
}
```

That round trip — POST inserts and submits, GET reads the persisted row back
identically — is the `Persistable` trick proven live. The id was non-null before the
save, yet the row exists and reads back: that only happens because `isNew()`
returned `true` and R2DBC issued an INSERT instead of a zero-row UPDATE.

Now the unhappy path. Ask for an id that was never inserted, and `switchIfEmpty`
turns the empty `Mono` into a `ResourceNotFoundException` that the framework renders
as an RFC 7807 problem detail — no hand-written 404, `application/problem+json`, with
trace context tucked into `extensions`:

```text
$ curl -s http://localhost:8081/api/v1/loan-applications/00000000-0000-0000-0000-000000000000
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 00000000-0000-0000-0000-000000000000",
  "instance": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000?traceId=1efc25dec634992124f6a1520970dfef",
  "extensions": {
    "traceId": "1efc25dec634992124f6a1520970dfef",
    "spanId": "6709a187f331beb0",
    "severity": "LOW",
    "retryable": false,
    "path": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000",
    "suggestion": "Verify the resource identifier and ensure it exists.",
    "category": "RESOURCE"
  }
}
```

!!! note "Key term — the system of record"
    This running core is the **system of record** for loan origination: the single
    authoritative store of the data. When the experience tier (port `8080`) accepts
    a channel request and the domain tier (port `8082`) runs its
    `RegisterApplicationSaga`, the saga's root step writes *here*, over HTTP, to this
    same table — and the `applicationId` it returns to the caller is the
    `loanApplicationId` this core minted. The persistence layer you just exercised by
    hand is the bottom of that whole flow. (The domain→core write seam is
    intentionally minimal in this sample: it carries the applicant and amount, so
    some core fields — `currency`, `termMonths`, `purpose` — land as defaults. The
    richer mapping is the generated SDK's job, covered in Chapter 7.)

## Step 9 — Prove it without a server

You do not need the running server to trust any of the above. The reactor ships a
controller slice test that boots the full reactive context against in-memory H2
(R2DBC runtime plus the Flyway migration), drives the real API with
`WebTestClient`, and asserts the create-then-read round trip, the RFC 7807 404, and
validation rejection of a bad payload — all against the real migrated schema, the
*same* code paths you just exercised by hand, run headless. From
`samples/lumen-lending`:

```text
$ mvn -q -pl core-lending-loan-origination test
```

The slice test's three cases pass:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
```

and the whole core module is green — eighteen tests across the domain model, the
reactive helpers, and the web layer:

```text
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Across the whole reactor a `mvn clean verify` runs **33** tests — core 18, domain 6,
experience 9 — all green, with no Docker and no external services.

!!! tip "Checkpoint"
    Run `mvn -q -pl core-lending-loan-origination test` from `samples/lumen-lending`.
    A green `Tests run: 3, Failures: 0` in `LoanApplicationControllerTest`, and
    `Tests run: 18` for the module, means Flyway applied `V1__loan_application.sql`
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
  H2 in tests and in the live service from the same file — migrating over JDBC while
  the request path stays R2DBC.
- A **MapStruct mapper** that generates `toResponse` and hand-writes `toNewEntity`,
  and a `@Transactional` **service** that composes them reactively — with a
  `ResourceNotFoundException` that the framework renders as an RFC 7807 404.
- A **live round trip**: the core booted on H2 on port `8081`, a `POST` inserted and
  submitted a real row (`status: SUBMITTED`), and a `GET` read it back byte for byte
  — the same row the domain saga writes to as the system of record.
- A green `Tests run: 3, Failures: 0` for the controller slice and `Tests run: 18`
  for the module (33 across the whole reactor), proving the round trip against the
  real migrated schema.

## Try it yourself {.exercises}

1. **Watch the trick fail.** In `LoanApplicationService.create`, comment out the
   `application.markNew()` line and rerun `mvn -q -pl core-lending-loan-origination
   test`. Observe how the create test breaks, then restore the line and explain in
   one sentence why the non-null UUID id made R2DBC choose `UPDATE`.
2. **Prove it live.** Boot the core with `( cd core-lending-loan-origination && mvn
   spring-boot:run )`, POST a loan application to
   `http://localhost:8081/api/v1/loan-applications`, then `GET` it back by the
   returned `loanApplicationId`. Confirm the `status` is `SUBMITTED`, then ask for a
   random UUID and confirm you get the RFC 7807 404.
3. **Add a derived finder.** Add `Flux<LoanApplication> findByApplicantId(UUID
   applicantId)` to `LoanApplicationRepository` and a thin `list`-style service
   method that uses it. No SQL — let the method name carry the query.
4. **Extend the schema.** Add a `risk_band VARCHAR(16)` column to
   `V1__loan_application.sql` and a matching `@Column("risk_band")` field on
   `LoanApplication`, then confirm the slice test still goes green against the
   migrated H2 schema.
5. **Map a new field.** Surface your `risk_band` field on `LoanApplicationResponse`
   and confirm MapStruct's generated `toResponse` picks it up automatically by
   name — no change to the mapper interface required.
6. **Sketch the production path.** Without wiring it, write a `LoanApplicationFilter`
   record with a `@FilterableId` applicant id and a status, and note which existing
   service method it would replace once the list endpoint needs paging.

## Where to go next

The core now owns its data, boots on H2, and serves real CRUD on port `8081`. The
next layers up the stack put this service to work: the domain tier orchestrates
these CRUD operations into business flows over generated SDKs, and the
`RegisterApplicationSaga` coordinates the multi-step decision path with compensation
when a step fails — writing, ultimately, to the very table you built here. The
persistence layer is the system of record everything above it writes to, and the
live exp → domain → core submit flow you will trace later bottoms out right here.
