-- 증빙 검수 Agent 초기 스키마 (PostgreSQL)

create table users (
    id            bigserial primary key,
    username      varchar(60)  not null unique,
    password_hash varchar(120) not null,
    name          varchar(60)  not null,
    role          varchar(20)  not null,           -- EMPLOYEE | HR | ADMIN
    employee_no   varchar(30),
    hire_date     date,
    department    varchar(60)
);

-- 지원 종류 설정(규정)의 버전. 판정 당시 적용한 버전을 신청 건이 참조하므로 개정 뒤에도 근거를 추적할 수 있다.
create table policy_version (
    id              bigserial primary key,
    support_type    varchar(40)  not null,
    name            varchar(100) not null,
    version         varchar(40)  not null,
    effective_date  date,
    status          varchar(20)  not null,          -- DRAFT | ACTIVE | RETIRED
    yaml_text       text         not null,
    regulation_text text         not null,
    regulation_key  varchar(300),                   -- 규정집 원문 S3 객체 키
    clauses         jsonb,
    regression      jsonb,
    indexed_at      timestamptz,
    created_by      varchar(60),
    created_at      timestamptz  not null,
    activated_at    timestamptz,
    note            text,
    unique (support_type, version)
);

create table application (
    id                bigserial primary key,
    application_no    varchar(40)  not null unique,
    applicant_id      bigint       not null references users (id),
    support_type      varchar(40)  not null,
    policy_version_id bigint       not null references policy_version (id),
    form              jsonb        not null,
    status            varchar(30)  not null,        -- DRAFT | AUTO_APPROVED | SUPPLEMENT_REQUIRED | NEEDS_REVIEW | APPROVED | REJECTED
    applied_on        date,
    auto_verdict      varchar(20),                  -- APPROVE | REJECT | REVIEW
    confidence        double precision,
    guidance          text,
    final_decision    varchar(20),
    decided_by        varchar(60),
    decided_at        timestamptz,
    decision_comment  text,
    dedup_key         varchar(300),
    last_run_no       integer      not null default 0,
    created_at        timestamptz  not null,
    submitted_at      timestamptz,
    updated_at        timestamptz  not null
);
create index idx_application_applicant on application (applicant_id);
create index idx_application_status on application (status, confidence);
create index idx_application_dedup on application (dedup_key);

create table evidence_file (
    id             bigserial primary key,
    application_id bigint       not null references application (id) on delete cascade,
    doc_id         varchar(80)  not null,
    filename       varchar(255) not null,
    content_type   varchar(100),
    size_bytes     bigint       not null,
    sha256         varchar(64)  not null,
    storage_key    varchar(300) not null,           -- 원본 S3 객체 키(비공개)
    page_keys      jsonb,
    extraction     jsonb,
    quality        double precision,
    engine         varchar(60),
    title_matched  boolean      not null default true,
    active         boolean      not null default true,
    uploaded_at    timestamptz  not null
);
create index idx_evidence_application on evidence_file (application_id, active);

-- 검수 실행 이력: 결과 전체(대조표·조건별 판정·근거·확신도·모델 버전)를 불변 스냅샷으로 보관한다.
create table review_run (
    id                bigserial primary key,
    application_id    bigint      not null references application (id) on delete cascade,
    run_no            integer     not null,
    policy_version_id bigint      not null,
    trigger_type      varchar(20) not null,
    verdict           varchar(20) not null,
    confidence        double precision,
    provider          varchar(60),
    model             varchar(100),
    result            jsonb       not null,
    created_by        varchar(60),
    created_at        timestamptz not null,
    unique (application_id, run_no)
);

create table audit_log (
    id             bigserial primary key,
    application_id bigint,
    actor          varchar(60)  not null,
    actor_role     varchar(20),
    action         varchar(60)  not null,
    detail         jsonb,
    created_at     timestamptz  not null
);
create index idx_audit_application on audit_log (application_id, created_at);

create table evaluation_run (
    id                bigserial primary key,
    kind              varchar(20) not null,          -- EVALUATION | REGRESSION
    status            varchar(20) not null,          -- RUNNING | DONE | FAILED
    policy_version_id bigint,
    progress_done     integer     not null default 0,
    progress_total    integer     not null default 0,
    summary           jsonb,
    cases             jsonb,
    provider          varchar(60),
    model             varchar(100),
    triggered_by      varchar(60),
    error             text,
    started_at        timestamptz not null,
    finished_at       timestamptz
);
