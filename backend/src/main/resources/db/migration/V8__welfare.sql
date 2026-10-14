-- =============================================================================
-- V8 · Bienestar: becas de punta a punta, ayudas, atenciones (dato sensible) y alertas.
-- RN-18 · RN-19 · RN-20 · RN-21 · RN-22 · RN-23
-- =============================================================================

create table scholarship_type
(
    id                          uuid primary key        default uuidv7(),
    code                        varchar(30)    not null unique,
    name                        varchar(120)   not null,
    description                 text           not null,
    mode                        varchar(10)    not null check (mode in ('PERCENT', 'AMOUNT')),
    value                       numeric(12, 2) not null check (value > 0),
    min_average                 numeric(5, 2)  check (min_average >= 0),
    min_attendance_pct          numeric(5, 2)  check (min_attendance_pct between 0 and 100),
    partial_min_average         numeric(5, 2)  check (partial_min_average >= 0),
    partial_value               numeric(12, 2) check (partial_value > 0),
    renewable                   boolean        not null,
    max_periods                 smallint check (max_periods > 0),
    active                      boolean        not null default true,
    created_at                  timestamptz    not null default now(),
    created_by                  uuid,
    updated_at                  timestamptz,
    updated_by                  uuid,
    version                     bigint         not null default 0,
    check (mode <> 'PERCENT' or value <= 100),
    check (partial_min_average is null or (min_average is not null and partial_min_average < min_average and partial_value is not null))
);

-- Rubros que cubre cada tipo de beca
create table scholarship_type_concept
(
    scholarship_type_id uuid not null references scholarship_type (id),
    concept_id          uuid not null references charge_concept (id),
    primary key (scholarship_type_id, concept_id)
);

create table scholarship_call
(
    id         uuid primary key      default uuidv7(),
    type_id    uuid         not null references scholarship_type (id),
    period_id  uuid         not null references academic_period (id),
    name       varchar(160) not null,
    quota      int          not null check (quota > 0),
    opens_at   timestamptz  not null,
    closes_at  timestamptz  not null,
    results_by date         not null,
    status     varchar(10)  not null default 'OPEN' check (status in ('DRAFT', 'OPEN', 'CLOSED', 'RESOLVED')),
    created_at timestamptz  not null default now(),
    created_by uuid,
    updated_at timestamptz,
    updated_by uuid,
    version    bigint       not null default 0,
    check (closes_at > opens_at)
);

create table call_required_document
(
    id        uuid primary key      default uuidv7(),
    call_id   uuid         not null references scholarship_call (id),
    code      varchar(40)  not null,
    name      varchar(160) not null,
    mandatory boolean      not null default true,
    constraint uq_call_document unique (call_id, code)
);

create table evaluation_criterion
(
    id         uuid primary key       default uuidv7(),
    call_id    uuid          not null references scholarship_call (id),
    name       varchar(120)  not null,
    max_score  numeric(6, 2) not null check (max_score > 0),
    weight     numeric(5, 2) not null check (weight > 0 and weight <= 100),
    sort_order smallint      not null,
    constraint uq_criterion unique (call_id, sort_order)
);

create table committee_resolution
(
    id           uuid primary key     default uuidv7(),
    call_id      uuid        not null references scholarship_call (id),
    act_number   varchar(40) not null unique,
    session_date date        not null,
    summary      text        not null,
    created_at   timestamptz not null default now(),
    created_by   uuid
);

create table committee_member
(
    id            uuid primary key      default uuidv7(),
    resolution_id uuid         not null references committee_resolution (id),
    member_name   varchar(160) not null,
    member_role   varchar(80)  not null,
    user_id       uuid references user_account (id),
    created_at    timestamptz  not null default now()
);

create table scholarship_application
(
    id                      uuid primary key     default uuidv7(),
    call_id                 uuid        not null references scholarship_call (id),
    student_id              uuid        not null references student (id),
    status                  varchar(15) not null default 'DRAFT' check (status in ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    statement               text,
    submitted_at            timestamptz,
    withdrawn_at            timestamptz,
    withdrawal_reason       text,
    total_score             numeric(7, 2),
    committee_resolution_id uuid references committee_resolution (id),
    decided_at              timestamptz,
    created_at              timestamptz not null default now(),
    created_by              uuid,
    updated_at              timestamptz,
    updated_by              uuid,
    version                 bigint      not null default 0,
    constraint uq_application unique (call_id, student_id),
    -- RN-20: aprobar o negar exige resolución del comité
    check (status not in ('APPROVED', 'REJECTED') or (committee_resolution_id is not null and decided_at is not null)),
    check (status <> 'WITHDRAWN' or (withdrawn_at is not null and withdrawal_reason is not null)),
    check (status = 'DRAFT' or status = 'WITHDRAWN' or submitted_at is not null)
);

create table application_document
(
    id                   uuid primary key     default uuidv7(),
    application_id       uuid        not null references scholarship_application (id),
    required_document_id uuid        not null references call_required_document (id),
    file_id              uuid        not null references stored_file (id),
    status               varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE', 'REPLACED')),
    uploaded_at          timestamptz not null default now(),
    created_at           timestamptz not null default now(),
    created_by           uuid,
    updated_at           timestamptz,
    updated_by           uuid
);
create unique index uq_application_document on application_document (application_id, required_document_id) where status = 'ACTIVE';

create table application_score
(
    id             uuid primary key       default uuidv7(),
    application_id uuid          not null references scholarship_application (id),
    criterion_id   uuid          not null references evaluation_criterion (id),
    score          numeric(6, 2) not null check (score >= 0),
    evaluator_id   uuid,
    comment        varchar(300),
    created_at     timestamptz   not null default now(),
    created_by     uuid,
    constraint uq_application_score unique (application_id, criterion_id)
);

create or replace function guard_application_score() returns trigger
    language plpgsql as
$$
begin
    if new.score > (select max_score from evaluation_criterion where id = new.criterion_id) then
        raise exception 'RN-20: el puntaje supera el máximo del criterio' using errcode = 'P0001';
    end if;
    if (select status from scholarship_application where id = new.application_id) not in ('SUBMITTED', 'UNDER_REVIEW') then
        raise exception 'RN-20: solo se evalúan postulaciones enviadas' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_application_score before insert on application_score for each row execute function guard_application_score();
create trigger trg_application_score_no_update before update on application_score for each row execute function forbid_update();

-- RN-18 / RN-19 / RN-20
create or replace function guard_application() returns trigger
    language plpgsql as
$$
declare
    v_members int;
begin
    if tg_op = 'INSERT' then
        if new.status <> 'DRAFT' then
            raise exception 'RN-18: toda postulación nace como borrador' using errcode = 'P0001';
        end if;
        return new;
    end if;
    if old.status <> 'DRAFT' and new.statement is distinct from old.statement then
        raise exception 'RN-19: una postulación enviada ya no se edita' using errcode = 'P0001';
    end if;
    if new.status <> old.status then
        if not ((old.status = 'DRAFT' and new.status in ('SUBMITTED', 'WITHDRAWN'))
            or (old.status = 'SUBMITTED' and new.status in ('UNDER_REVIEW', 'WITHDRAWN'))
            or (old.status = 'UNDER_REVIEW' and new.status in ('APPROVED', 'REJECTED', 'WITHDRAWN'))) then
            raise exception 'RN-19: transición de postulación no permitida (% → %)', old.status, new.status using errcode = 'P0001';
        end if;
        if new.status = 'SUBMITTED' and exists (
            select 1
            from call_required_document r
            where r.call_id = new.call_id
              and r.mandatory
              and not exists (select 1 from application_document d
                              where d.application_id = new.id and d.required_document_id = r.id and d.status = 'ACTIVE')) then
            raise exception 'RN-18: faltan documentos obligatorios; la postulación sigue en borrador' using errcode = 'P0001';
        end if;
        if new.status in ('APPROVED', 'REJECTED') then
            select count(*) into v_members from committee_member where resolution_id = new.committee_resolution_id;
            if v_members < param_numeric('welfare.committee_min_members') then
                raise exception 'RN-20: la resolución del comité necesita al menos % miembros', param_numeric('welfare.committee_min_members') using errcode = 'P0001';
            end if;
        end if;
    end if;
    return new;
end
$$;
create trigger trg_application_guard before insert or update on scholarship_application for each row execute function guard_application();

create or replace function guard_application_document() returns trigger
    language plpgsql as
$$
begin
    if (select status from scholarship_application where id = new.application_id) <> 'DRAFT' then
        raise exception 'RN-19: los documentos solo se cargan mientras la postulación está en borrador' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_application_document before insert or update on application_document for each row execute function guard_application_document();

create table scholarship_award
(
    id                           uuid primary key        default uuidv7(),
    application_id               uuid unique references scholarship_application (id),
    student_id                   uuid           not null references student (id),
    type_id                      uuid           not null references scholarship_type (id),
    start_period_id              uuid           not null references academic_period (id),
    end_period_id                uuid references academic_period (id),
    mode                         varchar(10)    not null check (mode in ('PERCENT', 'AMOUNT')),
    value                        numeric(12, 2) not null check (value > 0),
    status                       varchar(10)    not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'FINISHED', 'REVOKED')),
    retroactive_authorization_id uuid references exception_authorization (id),
    conditions                   text           not null,
    created_at                   timestamptz    not null default now(),
    created_by                   uuid,
    updated_at                   timestamptz,
    updated_by                   uuid,
    version                      bigint         not null default 0,
    check (mode <> 'PERCENT' or value <= 100)
);
create index ix_award_student on scholarship_award (student_id, status);

-- RN-21: el descuento por beca nunca es retroactivo salvo resolución expresa
create or replace function guard_scholarship_discount() returns trigger
    language plpgsql as
$$
declare
    v_award   scholarship_award%rowtype;
    v_charge  date;
    v_start   date;
begin
    if new.origin_type <> 'SCHOLARSHIP' then
        return new;
    end if;
    select * into v_award from scholarship_award where id = new.origin_id;
    if not found or v_award.student_id <> new.student_id then
        raise exception 'RN-21: el descuento no corresponde a una beca del estudiante' using errcode = 'P0001';
    end if;
    if v_award.status <> 'ACTIVE' then
        raise exception 'RN-22: la beca está % y no genera descuentos', v_award.status using errcode = 'P0001';
    end if;
    select p.classes_start into v_charge from academic_period p where p.id = new.period_id;
    select p.classes_start into v_start from academic_period p where p.id = v_award.start_period_id;
    if v_charge < v_start and v_award.retroactive_authorization_id is null then
        raise exception 'RN-21: la beca rige desde % y no se aplica de forma retroactiva', v_start using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_scholarship_discount before insert on discount_applied for each row execute function guard_scholarship_discount();

create table scholarship_followup
(
    id             uuid primary key       default uuidv7(),
    award_id       uuid          not null references scholarship_award (id),
    period_id      uuid          not null references academic_period (id),
    average        numeric(5, 2),
    attendance_pct numeric(5, 2),
    complies       boolean       not null,
    outcome        varchar(10)   not null check (outcome in ('MAINTAINED', 'REDUCED', 'SUSPENDED')),
    reason         text          not null,
    evaluated_at   timestamptz   not null default now(),
    evaluated_by   uuid,
    created_at     timestamptz   not null default now(),
    created_by     uuid,
    constraint uq_followup unique (award_id, period_id),
    check (complies = (outcome = 'MAINTAINED') or outcome = 'REDUCED')
);
create trigger trg_followup_no_update before update on scholarship_followup for each row execute function forbid_update();

create table scholarship_appeal
(
    id            uuid primary key     default uuidv7(),
    followup_id   uuid        not null unique references scholarship_followup (id),
    statement     text        not null check (length(trim(statement)) >= 20),
    filed_at      timestamptz not null default now(),
    status        varchar(10) not null default 'FILED' check (status in ('FILED', 'ACCEPTED', 'REJECTED')),
    decided_at    timestamptz,
    decided_by    uuid,
    decision_note text,
    created_at    timestamptz not null default now(),
    created_by    uuid,
    updated_at    timestamptz,
    updated_by    uuid,
    version       bigint      not null default 0,
    check (status = 'FILED' or (decided_at is not null and decision_note is not null))
);

create table financial_aid
(
    id         uuid primary key        default uuidv7(),
    student_id uuid           not null references student (id),
    period_id  uuid           not null references academic_period (id),
    kind       varchar(12)    not null check (kind in ('EMERGENCY', 'TRANSPORT', 'FOOD', 'DEVICE', 'CREDIT')),
    amount     numeric(12, 2) not null check (amount > 0),
    reason     text           not null,
    status     varchar(10)    not null default 'REQUESTED' check (status in ('REQUESTED', 'APPROVED', 'REJECTED', 'DISBURSED')),
    decided_at timestamptz,
    decided_by uuid,
    created_at timestamptz    not null default now(),
    created_by uuid,
    updated_at timestamptz,
    updated_by uuid,
    version    bigint         not null default 0
);

-- RN-23: DATO SENSIBLE (LOPDP art. 26). Contenido cifrado con AES-256-GCM en la aplicación.
create table welfare_attention
(
    id                   uuid primary key     default uuidv7(),
    student_id           uuid        not null references student (id),
    category             varchar(15) not null check (category in ('HEALTH', 'PSYCHOLOGY', 'ORIENTATION', 'SOCIAL_WORK')),
    attended_at          timestamptz not null,
    professional_user_id uuid        not null references user_account (id),
    summary_enc          bytea       not null,
    details_enc          bytea,
    follow_up_required   boolean     not null default false,
    consent_id           uuid        not null references consent (id),
    created_at           timestamptz not null default now(),
    created_by           uuid,
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint      not null default 0
);
create index ix_welfare_attention_student on welfare_attention (student_id, attended_at desc);

create table dropout_alert
(
    id          uuid primary key     default uuidv7(),
    student_id  uuid        not null references student (id),
    period_id   uuid        not null references academic_period (id),
    risk_level  varchar(10) not null check (risk_level in ('LOW', 'MEDIUM', 'HIGH')),
    reasons     jsonb       not null,
    status      varchar(15) not null default 'OPEN' check (status in ('OPEN', 'IN_FOLLOW_UP', 'CLOSED')),
    detected_at timestamptz not null default now(),
    closed_at   timestamptz,
    closed_by   uuid,
    note        text,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    updated_at  timestamptz,
    updated_by  uuid,
    version     bigint      not null default 0,
    constraint uq_dropout_alert unique (student_id, period_id)
);
