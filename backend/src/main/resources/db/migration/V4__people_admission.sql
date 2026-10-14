-- =============================================================================
-- V4 · Docentes, admisión, estudiantes y su máquina de estados (RN-30).
-- =============================================================================

create table teacher
(
    id             uuid primary key      default uuidv7(),
    person_id      uuid         not null unique references person (id),
    code           varchar(20)  not null unique,
    highest_degree varchar(200) not null,
    dedication     varchar(20)  not null check (dedication in ('TIEMPO_COMPLETO', 'MEDIO_TIEMPO', 'TIEMPO_PARCIAL')),
    category       varchar(15)  not null check (category in ('AUXILIAR', 'AGREGADO', 'PRINCIPAL', 'OCASIONAL')),
    status         varchar(10)  not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at     timestamptz  not null default now(),
    created_by     uuid,
    updated_at     timestamptz,
    updated_by     uuid,
    version        bigint       not null default 0
);

create table admission_process
(
    id         uuid primary key      default uuidv7(),
    period_id  uuid          not null references academic_period (id),
    career_id  uuid          not null references career (id),
    quota      int           not null check (quota > 0),
    opens_on   date          not null,
    closes_on  date          not null,
    min_score  numeric(5, 2) not null check (min_score >= 0),
    status     varchar(10)   not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
    created_at timestamptz   not null default now(),
    created_by uuid,
    updated_at timestamptz,
    updated_by uuid,
    version    bigint        not null default 0,
    constraint uq_admission_process unique (period_id, career_id),
    check (opens_on < closes_on)
);

create table applicant
(
    id               uuid primary key     default uuidv7(),
    process_id       uuid        not null references admission_process (id),
    person_id        uuid        not null references person (id),
    application_date date        not null default current_date,
    status           varchar(15) not null default 'REGISTERED' check (status in ('REGISTERED', 'EVALUATED', 'ADMITTED', 'NOT_ADMITTED', 'WITHDREW')),
    score            numeric(5, 2) check (score >= 0),
    decided_at       timestamptz,
    decided_by       uuid,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid,
    version          bigint      not null default 0,
    constraint uq_applicant unique (process_id, person_id),
    check (status not in ('ADMITTED', 'NOT_ADMITTED') or (score is not null and decided_at is not null))
);

create table student_status_transition
(
    from_status      varchar(15) not null,
    to_status        varchar(15) not null,
    requires_process boolean     not null,
    description      text        not null,
    primary key (from_status, to_status)
);

create table student
(
    id                  uuid primary key     default uuidv7(),
    person_id           uuid        not null references person (id),
    code                varchar(20) not null unique,
    career_id           uuid        not null references career (id),
    curriculum_id       uuid        not null references curriculum (id),
    cohort_period_id    uuid        not null references academic_period (id),
    applicant_id        uuid unique references applicant (id),
    previous_student_id uuid references student (id),
    status              varchar(15) not null default 'ADMITTED' check (status in ('ADMITTED', 'ENROLLED', 'REGULAR', 'WITHDRAWN', 'SUSPENDED', 'COMPLETED', 'GRADUATED')),
    status_changed_at   timestamptz not null default now(),
    institutional_email varchar(160) unique check (institutional_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    created_at          timestamptz not null default now(),
    created_by          uuid,
    updated_at          timestamptz,
    updated_by          uuid,
    version             bigint      not null default 0
);
create unique index uq_student_active_career on student (person_id, career_id) where status not in ('WITHDRAWN', 'GRADUATED');

create table student_status_history
(
    id           uuid primary key     default uuidv7(),
    student_id   uuid        not null references student (id),
    from_status  varchar(15),
    to_status    varchar(15) not null,
    process_code varchar(40),
    changed_at   timestamptz not null default now(),
    changed_by   uuid
);
create index ix_student_status_history on student_status_history (student_id, changed_at);

-- RN-03: la malla, la carrera y la persona de un estudiante no cambian jamás.
-- RN-30: solo transiciones del catálogo; las marcadas requires_process exigen proceso explícito.
create or replace function guard_student() returns trigger
    language plpgsql as
$$
declare
    v_requires boolean;
begin
    if tg_op = 'INSERT' then
        if (select career_id from curriculum where id = new.curriculum_id) <> new.career_id then
            raise exception 'RN-03: la malla no pertenece a la carrera del estudiante' using errcode = 'P0001';
        end if;
        if new.status <> 'ADMITTED' then
            raise exception 'RN-30: todo estudiante nace en estado ADMITTED' using errcode = 'P0001';
        end if;
        return new;
    end if;
    if new.curriculum_id <> old.curriculum_id or new.career_id <> old.career_id or new.person_id <> old.person_id then
        raise exception 'RN-03: la malla y la carrera del estudiante son inmutables; el cambio exige homologación' using errcode = 'P0001';
    end if;
    if new.status <> old.status then
        select requires_process into v_requires
        from student_status_transition
        where from_status = old.status and to_status = new.status;
        if not found then
            raise exception 'RN-30: transición de estado no permitida (% → %)', old.status, new.status using errcode = 'P0001';
        end if;
        if v_requires and app_process() is null then
            raise exception 'RN-30: la transición % → % requiere un proceso explícito', old.status, new.status using errcode = 'P0001';
        end if;
        new.status_changed_at := now();
    end if;
    return new;
end
$$;
create trigger trg_student_guard before insert or update on student for each row execute function guard_student();

create or replace function log_student_status() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' or new.status <> old.status then
        insert into student_status_history (student_id, from_status, to_status, process_code, changed_by)
        values (new.id, case when tg_op = 'UPDATE' then old.status end, new.status, app_process(), app_actor());
    end if;
    return new;
end
$$;
create trigger trg_student_status_log after insert or update of status on student for each row execute function log_student_status();

create table socioeconomic_record
(
    id                uuid primary key       default uuidv7(),
    student_id        uuid           not null references student (id),
    period_id         uuid           not null references academic_period (id),
    household_income  numeric(12, 2) not null check (household_income >= 0),
    household_members smallint       not null check (household_members > 0),
    quintile          smallint       not null check (quintile between 1 and 5),
    housing           varchar(12)    not null check (housing in ('PROPIA', 'ARRENDADA', 'PRESTADA', 'OTRA')),
    works             boolean        not null,
    consent_id        uuid           not null references consent (id),
    created_at        timestamptz    not null default now(),
    created_by        uuid,
    updated_at        timestamptz,
    updated_by        uuid,
    version           bigint         not null default 0,
    constraint uq_socioeconomic unique (student_id, period_id)
);

create table teacher_assignment
(
    id          uuid primary key       default uuidv7(),
    teacher_id  uuid          not null references teacher (id),
    period_id   uuid          not null references academic_period (id),
    activity    varchar(15)   not null check (activity in ('DOCENCIA', 'INVESTIGACION', 'VINCULACION', 'GESTION')),
    hours       numeric(5, 2) not null check (hours > 0),
    description varchar(200)  not null,
    status      varchar(10)   not null default 'ACTIVE' check (status in ('ACTIVE', 'ANNULLED')),
    created_at  timestamptz   not null default now(),
    created_by  uuid,
    updated_at  timestamptz,
    updated_by  uuid,
    version     bigint        not null default 0
);
create index ix_teacher_assignment on teacher_assignment (teacher_id, period_id);
