-- Loan-origination core schema (teaching subset).
--
-- Portable, H2-compatible DDL. The real firefly-oss service targets Postgres and
-- evolves the schema across many migrations (BIGSERIAL -> UUID, enum types, etc.);
-- this single migration models the trimmed slice in a way both H2 (tests) and
-- Postgres (production-shaped) accept. Enums are stored as VARCHAR, which is how
-- Spring Data R2DBC maps the ApplicationStatus enum by default.

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

CREATE TABLE proposed_offer (
    proposed_offer_id    UUID PRIMARY KEY,
    loan_application_id  UUID NOT NULL,
    offered_amount       NUMERIC(19, 2) NOT NULL,
    currency             VARCHAR(3) NOT NULL,
    term_months          INTEGER NOT NULL,
    annual_interest_rate NUMERIC(5, 2) NOT NULL,
    monthly_payment      NUMERIC(19, 2) NOT NULL,
    created_at           TIMESTAMP NOT NULL
);

CREATE INDEX ix_proposed_offer_loan_application_id
    ON proposed_offer (loan_application_id);
