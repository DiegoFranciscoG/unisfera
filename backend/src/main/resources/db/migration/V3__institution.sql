-- =============================================================================
-- V3 · Institución, carreras, mallas versionadas, asignaturas, requisitos,
--      períodos académicos y aulas (RRA arts. 9, 10, 22-25, 54, 71, 81).
-- =============================================================================

create table institution
(
    id                 uuid primary key      default uuidv7(),
    name               varchar(200) not null,
    legal_name         varchar(200) not null,
    ruc                char(13)     not null check (ruc ~ '^[0-9]{10}001$'),
    kind               varchar(30)  not null check (kind in ('PRIVATE_COFINANCED', 'PRIVATE_SELF_FINANCED', 'PUBLIC')),
    city               varchar(80)  not null,
    address            varchar(300) not null,
    email_domain       varchar(120) not null,
    sri_establishment  char(3)      not null check (sri_establishment ~ '^[0-9]{3}$'),
    sri_emission_point char(3)      not null check (sri_emission_point ~ '^[0-9]{3}$'),
    created_at         timestamptz  not null default now(),
    created_by         uuid,
    updated_at         timestamptz,
    updated_by         uuid,
    version            bigint       not null default 0
);

create table campus
(
    id             uuid primary key      default uuidv7(),
    institution_id uuid         not null references institution (id),
    code           varchar(10)  not null unique,
    name           varchar(120) not null,
    city           varchar(80)  not null,
    address        varchar(300),
    created_at     timestamptz  not null default now(),
    created_by     uuid,
    updated_at     timestamptz,
    updated_by     uuid,
    version        bigint       not null default 0
);

create table faculty
(
    id             uuid primary key      default uuidv7(),
    institution_id uuid         not null references institution (id),
    code           varchar(10)  not null unique,
    name           varchar(160) not null,
    created_at     timestamptz  not null default now(),
    created_by     uuid,
    updated_at     timestamptz,
    updated_by     uuid,
    version        bigint       not null default 0
);

create table career
(
    id               uuid primary key      default uuidv7(),
    faculty_id       uuid         not null references faculty (id),
    campus_id        uuid         not null references campus (id),
    code             varchar(12)  not null unique,
    senescyt_code    varchar(40)  not null unique,
    name             varchar(160) not null,
    degree_title     varchar(200) not null,
    modality         varchar(20)  not null check (modality in ('PRESENCIAL', 'SEMIPRESENCIAL', 'EN_LINEA', 'A_DISTANCIA', 'HIBRIDA', 'DUAL')),
    education_level  varchar(30)  not null check (education_level in ('TERCER_NIVEL_GRADO', 'TERCER_NIVEL_TECNOLOGICO')),
    duration_periods smallint     not null check (duration_periods > 0),
    status           varchar(10)  not null default 'ACTIVE' check (status in ('ACTIVE', 'CLOSED')),
    created_at       timestamptz  not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid,
    version          bigint       not null default 0
);

create table curriculum
(
    id                  uuid primary key      default uuidv7(),
    career_id           uuid         not null references career (id),
    version_code        varchar(20)  not null,
    name                varchar(160) not null,
    valid_from          date         not null,
    valid_to            date,
    approval_resolution varchar(80)  not null,
    status              varchar(10)  not null default 'DRAFT' check (status in ('DRAFT', 'ACTIVE', 'CLOSED')),
    created_at          timestamptz  not null default now(),
    created_by          uuid,
    updated_at          timestamptz,
    updated_by          uuid,
    version             bigint       not null default 0,
    constraint uq_curriculum_version unique (career_id, version_code),
    check (valid_to is null or valid_to > valid_from)
);

create table curriculum_level
(
    id            uuid primary key     default uuidv7(),
    curriculum_id uuid        not null references curriculum (id),
    number        smallint    not null check (number between 1 and 20),
    name          varchar(60) not null,
    created_at    timestamptz not null default now(),
    created_by    uuid,
    constraint uq_curriculum_level unique (curriculum_id, number)
);

create table subject
(
    id                    uuid primary key      default uuidv7(),
    curriculum_id         uuid         not null references curriculum (id),
    level_id              uuid         not null references curriculum_level (id),
    code                  varchar(20)  not null,
    name                  varchar(160) not null,
    teacher_contact_hours int          not null check (teacher_contact_hours >= 0),
    practical_hours       int          not null check (practical_hours >= 0),
    autonomous_hours      int          not null check (autonomous_hours >= 0),
    total_hours           int generated always as (teacher_contact_hours + practical_hours + autonomous_hours) stored,
    credits               numeric(5, 2) not null check (credits > 0),
    organization_unit     varchar(30)  not null check (organization_unit in ('BASICA', 'PROFESIONAL', 'INTEGRACION_CURRICULAR')),
    is_titling            boolean      not null default false,
    created_at            timestamptz  not null default now(),
    created_by            uuid,
    updated_at            timestamptz,
    updated_by            uuid,
    version               bigint       not null default 0,
    constraint uq_subject_code unique (curriculum_id, code),
    check (teacher_contact_hours + practical_hours + autonomous_hours > 0)
);
create index ix_subject_level on subject (level_id);

create or replace function guard_subject_level() returns trigger
    language plpgsql as
$$
begin
    if (select curriculum_id from curriculum_level where id = new.level_id) <> new.curriculum_id then
        raise exception 'RN-03: el nivel no pertenece a la malla de la asignatura' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_subject_level before insert or update of level_id, curriculum_id on subject
    for each row execute function guard_subject_level();

create table subject_prerequisite
(
    subject_id          uuid        not null references subject (id),
    required_subject_id uuid        not null references subject (id),
    created_at          timestamptz not null default now(),
    created_by          uuid,
    primary key (subject_id, required_subject_id),
    check (subject_id <> required_subject_id)
);
create index ix_prerequisite_required on subject_prerequisite (required_subject_id);

-- Un prerrequisito vive en la misma malla y en un nivel estrictamente inferior: garantiza que no haya ciclos.
create or replace function guard_prerequisite() returns trigger
    language plpgsql as
$$
declare
    v_ok boolean;
begin
    select s.curriculum_id = r.curriculum_id and ls.number > lr.number
    into v_ok
    from subject s
             join curriculum_level ls on ls.id = s.level_id,
         subject r
             join curriculum_level lr on lr.id = r.level_id
    where s.id = new.subject_id and r.id = new.required_subject_id;
    if not coalesce(v_ok, false) then
        raise exception 'RN-02: el prerrequisito debe ser de la misma malla y de un nivel inferior' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_prerequisite_guard before insert on subject_prerequisite for each row execute function guard_prerequisite();

create table subject_corequisite
(
    subject_id            uuid        not null references subject (id),
    corequisite_subject_id uuid       not null references subject (id),
    created_at            timestamptz not null default now(),
    created_by            uuid,
    primary key (subject_id, corequisite_subject_id),
    check (subject_id <> corequisite_subject_id)
);

create or replace function guard_corequisite() returns trigger
    language plpgsql as
$$
begin
    if (select curriculum_id from subject where id = new.subject_id) <>
       (select curriculum_id from subject where id = new.corequisite_subject_id) then
        raise exception 'RN-02: el correquisito debe pertenecer a la misma malla' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_corequisite_guard before insert on subject_corequisite for each row execute function guard_corequisite();

create table curriculum_equivalence
(
    id                  uuid primary key      default uuidv7(),
    from_subject_id     uuid          not null references subject (id),
    to_subject_id       uuid          not null references subject (id),
    coverage_percentage numeric(5, 2) not null check (coverage_percentage between 0 and 100),
    created_at          timestamptz   not null default now(),
    created_by          uuid,
    constraint uq_equivalence unique (from_subject_id, to_subject_id),
    check (from_subject_id <> to_subject_id)
);

create table academic_period
(
    id                       uuid primary key     default uuidv7(),
    code                     varchar(20) not null unique check (code ~ '^[0-9]{4}-[A-Z0-9]{1,4}$'),
    name                     varchar(80) not null,
    type                     varchar(15) not null check (type in ('ORDINARY', 'EXTRAORDINARY')),
    ordinary_enrollment_from date        not null,
    ordinary_enrollment_to   date        not null,
    extraordinary_enrollment_to date     not null,
    special_enrollment_to    date        not null,
    classes_start            date        not null,
    classes_end              date        not null,
    grades_close_deadline    date        not null,
    status                   varchar(12) not null default 'PLANNED' check (status in ('PLANNED', 'ENROLLMENT', 'IN_PROGRESS', 'CLOSED')),
    created_at               timestamptz not null default now(),
    created_by               uuid,
    updated_at               timestamptz,
    updated_by               uuid,
    version                  bigint      not null default 0,
    -- RRA art. 71 a): la ordinaria nunca termina después del inicio de clases
    constraint ck_period_ordinary check (ordinary_enrollment_from <= ordinary_enrollment_to and ordinary_enrollment_to <= classes_start),
    constraint ck_period_extraordinary check (ordinary_enrollment_to < extraordinary_enrollment_to and extraordinary_enrollment_to <= special_enrollment_to),
    constraint ck_period_classes check (classes_start < classes_end and classes_end <= grades_close_deadline)
);

create table classroom
(
    id         uuid primary key     default uuidv7(),
    campus_id  uuid        not null references campus (id),
    code       varchar(20) not null,
    name       varchar(80) not null,
    capacity   int         not null check (capacity > 0),
    kind       varchar(15) not null check (kind in ('AULA', 'LABORATORIO', 'AUDITORIO', 'VIRTUAL')),
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz,
    updated_by uuid,
    version    bigint      not null default 0,
    constraint uq_classroom unique (campus_id, code)
);
