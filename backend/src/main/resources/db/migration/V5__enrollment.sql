-- =============================================================================
-- V5 · Paralelos, horarios, autorizaciones, matrícula y su motor de reglas en BD.
-- RN-01 ventanas · RN-03 malla · RN-04 carga · RN-05 cupo · RN-06 choques
-- RN-07 número de matrícula · RN-08 deuda · RN-10 anulación
-- =============================================================================

create table section
(
    id             uuid primary key     default uuidv7(),
    period_id      uuid        not null references academic_period (id),
    subject_id     uuid        not null references subject (id),
    code           varchar(5)  not null,
    capacity       int         not null check (capacity > 0),
    enrolled_count int         not null default 0,
    teacher_id     uuid references teacher (id),
    classroom_id   uuid references classroom (id),
    status         varchar(10) not null default 'OPEN' check (status in ('OPEN', 'CLOSED', 'CANCELLED')),
    created_at     timestamptz not null default now(),
    created_by     uuid,
    updated_at     timestamptz,
    updated_by     uuid,
    version        bigint      not null default 0,
    constraint uq_section unique (period_id, subject_id, code),
    -- RN-05: la última línea de defensa contra el sobrecupo
    constraint ck_section_capacity check (enrolled_count between 0 and capacity)
);
create index ix_section_teacher on section (teacher_id, period_id);
create index ix_section_subject on section (subject_id, period_id);

create table schedule_block
(
    id           uuid primary key     default uuidv7(),
    section_id   uuid        not null references section (id),
    period_id    uuid        not null references academic_period (id),
    weekday      smallint    not null check (weekday between 1 and 7),
    start_time   time        not null,
    end_time     time        not null,
    minutes      int4range   not null,
    classroom_id uuid references classroom (id),
    created_at   timestamptz not null default now(),
    created_by   uuid,
    check (start_time < end_time),
    -- Un aula no puede tener dos clases a la misma hora
    constraint ex_classroom_clash exclude using gist (classroom_id with =, period_id with =, weekday with =, minutes with &&)
        where (classroom_id is not null)
);
create index ix_schedule_block_section on schedule_block (section_id);

create or replace function fill_schedule_block() returns trigger
    language plpgsql as
$$
begin
    new.period_id := (select period_id from section where id = new.section_id);
    new.minutes := int4range((extract(hour from new.start_time) * 60 + extract(minute from new.start_time))::int,
                             (extract(hour from new.end_time) * 60 + extract(minute from new.end_time))::int, '[)');
    return new;
end
$$;
create trigger trg_schedule_block_fill before insert or update on schedule_block for each row execute function fill_schedule_block();

-- Tipos de excepción y el permiso que debe tener quien autoriza (permisos en BD)
create table authorization_type
(
    type                varchar(30) primary key,
    required_permission varchar(40) not null references permission (code),
    description         text        not null
);

create table exception_authorization
(
    id               uuid primary key     default uuidv7(),
    type             varchar(30) not null references authorization_type (type),
    student_id       uuid references student (id),
    period_id        uuid references academic_period (id),
    subject_id       uuid references subject (id),
    section_id       uuid references section (id),
    cause_code       varchar(40) not null,
    reason           text        not null check (length(trim(reason)) >= 10),
    resolution_ref   varchar(80),
    authorized_by    uuid        not null references user_account (id),
    authorized_at    timestamptz not null default now(),
    expires_at       timestamptz,
    used_at          timestamptz,
    annulled_at      timestamptz,
    annulled_by      uuid,
    annulment_reason text,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid,
    version          bigint      not null default 0
);
create index ix_authorization_student on exception_authorization (student_id, type);

create or replace function guard_authorization() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1
                   from user_role ur
                            join role_permission rp on rp.role_id = ur.role_id
                            join permission p on p.id = rp.permission_id
                            join authorization_type t on t.required_permission = p.code
                   where ur.user_id = new.authorized_by
                     and ur.revoked_at is null
                     and t.type = new.type) then
        raise exception 'SEC-05: quien autoriza % no tiene el permiso requerido', new.type using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_authorization_guard before insert on exception_authorization for each row execute function guard_authorization();

create table enrollment
(
    id                       uuid primary key     default uuidv7(),
    student_id               uuid        not null references student (id),
    period_id                uuid        not null references academic_period (id),
    type                     varchar(15) not null check (type in ('ORDINARY', 'EXTRAORDINARY', 'SPECIAL')),
    status                   varchar(10) not null default 'CONFIRMED' check (status in ('CONFIRMED', 'ANNULLED')),
    enrolled_at              timestamptz not null default now(),
    registered_by            uuid,
    special_authorization_id uuid references exception_authorization (id),
    load_authorization_id    uuid references exception_authorization (id),
    debt_authorization_id    uuid references exception_authorization (id),
    annulled_at              timestamptz,
    annulled_by              uuid,
    annulment_reason         text,
    created_at               timestamptz not null default now(),
    created_by               uuid,
    updated_at               timestamptz,
    updated_by               uuid,
    version                  bigint      not null default 0,
    check (type <> 'SPECIAL' or special_authorization_id is not null),
    check (status <> 'ANNULLED' or (annulled_at is not null and annulled_by is not null and length(trim(annulment_reason)) >= 10))
);
create unique index uq_enrollment_active on enrollment (student_id, period_id) where status = 'CONFIRMED';
create index ix_enrollment_period on enrollment (period_id, status);

-- RN-01 (ventanas) y RN-08 (deuda vencida) al insertar la matrícula
create or replace function guard_enrollment_insert() returns trigger
    language plpgsql as
$$
declare
    p        academic_period%rowtype;
    v_day    date := (new.enrolled_at at time zone 'America/Guayaquil')::date;
    v_status varchar;
begin
    select * into p from academic_period where id = new.period_id;
    if new.type = 'ORDINARY' and not (v_day between p.ordinary_enrollment_from and p.ordinary_enrollment_to) then
        raise exception 'RN-01: la matrícula ordinaria solo se registra entre % y %', p.ordinary_enrollment_from, p.ordinary_enrollment_to using errcode = 'P0001';
    elsif new.type = 'EXTRAORDINARY' and not (v_day > p.ordinary_enrollment_to and v_day <= p.extraordinary_enrollment_to) then
        raise exception 'RN-01: la matrícula extraordinaria solo se registra hasta %', p.extraordinary_enrollment_to using errcode = 'P0001';
    elsif new.type = 'SPECIAL' and not (v_day >= p.ordinary_enrollment_from and v_day <= p.special_enrollment_to) then
        raise exception 'RN-01: la matrícula especial solo se registra hasta %', p.special_enrollment_to using errcode = 'P0001';
    end if;

    select status into v_status from student where id = new.student_id;
    if v_status not in ('ADMITTED', 'ENROLLED', 'REGULAR') then
        raise exception 'RN-30: un estudiante en estado % no puede matricularse', v_status using errcode = 'P0001';
    end if;

    if param_bool('finance.block_enrollment_on_overdue_debt', new.enrolled_at) and new.debt_authorization_id is null then
        if exists (select 1
                   from installment i
                   where i.student_id = new.student_id
                     and i.status = 'PENDING'
                     and i.due_date + param_numeric('finance.grace_days', i.due_date::timestamptz)::int < v_day
                     and i.amount_due + i.late_fee_accrued - i.amount_paid > 0) then
            raise exception 'RN-08: el estudiante tiene valores vencidos; regularice su pago o solicite una excepción financiera' using errcode = 'P0001';
        end if;
    end if;
    return new;
end
$$;
create trigger trg_enrollment_insert before insert on enrollment for each row execute function guard_enrollment_insert();

create table enrollment_detail
(
    id                                uuid primary key     default uuidv7(),
    enrollment_id                     uuid        not null references enrollment (id),
    student_id                        uuid        not null references student (id),
    period_id                         uuid        not null references academic_period (id),
    subject_id                        uuid        not null references subject (id),
    section_id                        uuid        not null references section (id),
    attempt_number                    smallint    not null,
    third_enrollment_authorization_id uuid references exception_authorization (id),
    status                            varchar(10) not null default 'ENROLLED' check (status in ('ENROLLED', 'WITHDRAWN', 'ANNULLED')),
    status_changed_at                 timestamptz,
    created_at                        timestamptz not null default now(),
    created_by                        uuid,
    updated_at                        timestamptz,
    updated_by                        uuid,
    version                           bigint      not null default 0,
    constraint uq_enrollment_subject unique (enrollment_id, subject_id),
    -- RN-07: la cuarta matrícula es imposible por diseño
    constraint ck_attempt_range check (attempt_number between 1 and 3),
    constraint ck_third_attempt_authorized check (attempt_number < 3 or third_enrollment_authorization_id is not null)
);
create index ix_detail_student on enrollment_detail (student_id, subject_id);
create index ix_detail_section on enrollment_detail (section_id) where status = 'ENROLLED';

create table enrollment_time_slot
(
    id                   uuid primary key   default uuidv7(),
    enrollment_detail_id uuid      not null references enrollment_detail (id),
    student_id           uuid      not null,
    period_id            uuid      not null,
    weekday              smallint  not null,
    minutes              int4range not null,
    active               boolean   not null default true,
    -- RN-06: un estudiante no puede estar en dos clases a la vez
    constraint ex_student_schedule_clash exclude using gist (student_id with =, period_id with =, weekday with =, minutes with &&)
        where (active)
);
create index ix_time_slot_detail on enrollment_time_slot (enrollment_detail_id);

create or replace function guard_detail_insert() returns trigger
    language plpgsql as
$$
declare
    e         enrollment%rowtype;
    s         section%rowtype;
    v_prev    int;
    v_curr    uuid;
begin
    select * into e from enrollment where id = new.enrollment_id;
    if e.status <> 'CONFIRMED' then
        raise exception 'RN-10: no se agregan asignaturas a una matrícula anulada' using errcode = 'P0001';
    end if;
    new.student_id := e.student_id;
    new.period_id := e.period_id;

    select * into s from section where id = new.section_id;
    if s.period_id <> e.period_id or s.subject_id <> new.subject_id then
        raise exception 'RN-05: el paralelo no corresponde a la asignatura o al período' using errcode = 'P0001';
    end if;

    select curriculum_id into v_curr from student where id = e.student_id;
    if (select curriculum_id from subject where id = new.subject_id) <> v_curr then
        raise exception 'RN-03: la asignatura no pertenece a la malla del estudiante' using errcode = 'P0001';
    end if;

    if exists (select 1 from v_kardex k where k.student_id = e.student_id and k.subject_id = new.subject_id and k.result = 'APPROVED') then
        raise exception 'RN-02: la asignatura ya está aprobada' using errcode = 'P0001';
    end if;

    -- RN-07: el número de matrícula lo decide la base contando los intentos que sí cuentan
    select count(*) into v_prev
    from enrollment_detail d
    where d.student_id = e.student_id
      and d.subject_id = new.subject_id
      and d.status = 'ENROLLED'
      and d.enrollment_id <> new.enrollment_id;
    new.attempt_number := v_prev + 1;
    if new.attempt_number > 3 then
        raise exception 'RN-07: cuarta matrícula imposible: la asignatura ya se cursó tres veces' using errcode = 'P0001';
    end if;
    if new.attempt_number = 3 then
        if new.third_enrollment_authorization_id is null or not exists (
            select 1 from exception_authorization a
            where a.id = new.third_enrollment_authorization_id
              and a.type = 'THIRD_ENROLLMENT'
              and a.student_id = e.student_id
              and a.subject_id = new.subject_id
              and a.annulled_at is null) then
            raise exception 'RN-07: la tercera matrícula requiere una autorización registrada' using errcode = 'P0001';
        end if;
    end if;

    -- RN-05: reserva atómica del cupo; nunca "consultar y luego insertar"
    update section
    set enrolled_count = enrolled_count + 1
    where id = new.section_id
      and status = 'OPEN'
      and enrolled_count < capacity;
    if not found then
        raise exception 'RN-05: el paralelo % de esta asignatura ya no tiene cupos', s.code using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_detail_insert before insert on enrollment_detail for each row execute function guard_detail_insert();

create or replace function after_detail_insert() returns trigger
    language plpgsql as
$$
declare
    v_count int;
    v_load  uuid;
    v_when  timestamptz;
begin
    insert into enrollment_time_slot (enrollment_detail_id, student_id, period_id, weekday, minutes)
    select new.id, new.student_id, new.period_id, b.weekday, b.minutes
    from schedule_block b
    where b.section_id = new.section_id;

    select load_authorization_id, enrolled_at into v_load, v_when from enrollment where id = new.enrollment_id;
    select count(*) into v_count from enrollment_detail where enrollment_id = new.enrollment_id and status = 'ENROLLED';
    if v_load is null and v_count > param_numeric('enrollment.max_subjects_per_period', v_when) then
        raise exception 'RN-04: se superó el máximo de % asignaturas por período sin excepción autorizada',
            param_numeric('enrollment.max_subjects_per_period', v_when) using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_detail_after_insert after insert on enrollment_detail for each row execute function after_detail_insert();

-- Un detalle solo pasa de ENROLLED a WITHDRAWN o ANNULLED; al hacerlo libera cupo y franjas.
create or replace function guard_detail_update() returns trigger
    language plpgsql as
$$
begin
    if (to_jsonb(new) - 'status' - 'status_changed_at' - 'updated_at' - 'updated_by' - 'version')
        is distinct from (to_jsonb(old) - 'status' - 'status_changed_at' - 'updated_at' - 'updated_by' - 'version') then
        raise exception 'LEY-2: el detalle de matrícula no se edita; se retira o se anula' using errcode = 'P0001';
    end if;
    if new.status <> old.status then
        if old.status <> 'ENROLLED' then
            raise exception 'LEY-2: un detalle % es definitivo', old.status using errcode = 'P0001';
        end if;
        new.status_changed_at := now();
        update section set enrolled_count = enrolled_count - 1 where id = new.section_id;
        update enrollment_time_slot set active = false where enrollment_detail_id = new.id;
    end if;
    return new;
end
$$;
create trigger trg_detail_update before update on enrollment_detail for each row execute function guard_detail_update();

-- RN-10: anular la matrícula anula sus detalles (y con ello libera cupos)
create or replace function guard_enrollment_update() returns trigger
    language plpgsql as
$$
begin
    if old.status = 'ANNULLED' then
        raise exception 'LEY-2: una matrícula anulada es definitiva' using errcode = 'P0001';
    end if;
    if (to_jsonb(new) - 'status' - 'annulled_at' - 'annulled_by' - 'annulment_reason' - 'updated_at' - 'updated_by' - 'version')
        is distinct from (to_jsonb(old) - 'status' - 'annulled_at' - 'annulled_by' - 'annulment_reason' - 'updated_at' - 'updated_by' - 'version') then
        raise exception 'LEY-2: la matrícula no se edita; se anula con motivo' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_enrollment_update before update on enrollment for each row execute function guard_enrollment_update();

create or replace function cascade_enrollment_annulment() returns trigger
    language plpgsql as
$$
begin
    if new.status = 'ANNULLED' and old.status = 'CONFIRMED' then
        update enrollment_detail set status = 'ANNULLED' where enrollment_id = new.id and status = 'ENROLLED';
    end if;
    return new;
end
$$;
create trigger trg_enrollment_annul after update of status on enrollment for each row execute function cascade_enrollment_annulment();

create table enrollment_annulment
(
    id            uuid primary key      default uuidv7(),
    enrollment_id uuid         not null unique references enrollment (id),
    legal_basis   varchar(160) not null,
    reason        text         not null,
    annulled_by   uuid         not null,
    annulled_at   timestamptz  not null default now(),
    created_at    timestamptz  not null default now(),
    created_by    uuid
);

create table withdrawal
(
    id                   uuid primary key     default uuidv7(),
    enrollment_detail_id uuid        not null references enrollment_detail (id),
    type                 varchar(15) not null check (type in ('VOLUNTARY', 'FORCE_MAJEURE')),
    reason               text        not null check (length(trim(reason)) >= 10),
    document_file_id     uuid references stored_file (id),
    status               varchar(10) not null default 'REQUESTED' check (status in ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_at         timestamptz not null default now(),
    requested_by         uuid,
    decided_at           timestamptz,
    decided_by           uuid,
    decision_note        text,
    created_at           timestamptz not null default now(),
    created_by           uuid,
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint      not null default 0,
    check (type <> 'FORCE_MAJEURE' or document_file_id is not null or status = 'REQUESTED'),
    check (status = 'REQUESTED' or (decided_at is not null and decided_by is not null))
);
create unique index uq_withdrawal_open on withdrawal (enrollment_detail_id) where status in ('REQUESTED', 'APPROVED');

create table homologation
(
    id                  uuid primary key       default uuidv7(),
    student_id          uuid          not null references student (id),
    target_subject_id   uuid          not null references subject (id),
    method              varchar(25)   not null check (method in ('CONTENT_ANALYSIS', 'KNOWLEDGE_VALIDATION', 'PROFESSIONAL_PRACTICE', 'INTERNAL_RECOGNITION')),
    source_institution  varchar(200)  not null,
    source_subject_name varchar(200)  not null,
    source_hours        int check (source_hours > 0),
    source_grade        numeric(6, 2) check (source_grade >= 0),
    source_scale_max    numeric(6, 2) check (source_scale_max > 0),
    source_approved_on  date,
    coverage_percentage numeric(5, 2) check (coverage_percentage between 0 and 100),
    grade               numeric(5, 2) not null check (grade >= 0),
    status              varchar(10)   not null default 'DRAFT' check (status in ('DRAFT', 'APPROVED', 'REJECTED', 'ANNULLED')),
    resolution_ref      varchar(80),
    decided_at          timestamptz,
    decided_by          uuid,
    annulled_at         timestamptz,
    annulled_by         uuid,
    annulment_reason    text,
    created_at          timestamptz   not null default now(),
    created_by          uuid,
    updated_at          timestamptz,
    updated_by          uuid,
    version             bigint        not null default 0,
    check (status <> 'APPROVED' or (resolution_ref is not null and decided_at is not null)),
    check (status <> 'ANNULLED' or annulment_reason is not null)
);
create unique index uq_homologation_approved on homologation (student_id, target_subject_id) where status = 'APPROVED';

-- RRA art. 82: análisis comparativo ≥ porcentaje mínimo y antigüedad máxima (ambos parámetros)
create or replace function guard_homologation() returns trigger
    language plpgsql as
$$
begin
    if (select curriculum_id from subject where id = new.target_subject_id) <> (select curriculum_id from student where id = new.student_id) then
        raise exception 'RN-03: la asignatura homologada debe pertenecer a la malla del estudiante' using errcode = 'P0001';
    end if;
    if new.status = 'APPROVED' and new.method in ('CONTENT_ANALYSIS', 'INTERNAL_RECOGNITION') then
        if coalesce(new.coverage_percentage, 0) < param_numeric('homologation.min_content_pct') then
            raise exception 'RN-02: la homologación por contenidos exige al menos % %% de cobertura', param_numeric('homologation.min_content_pct') using errcode = 'P0001';
        end if;
        if new.source_approved_on is null or new.source_approved_on < current_date - (param_numeric('homologation.max_years')::int * interval '1 year') then
            raise exception 'RN-02: solo se homologa hasta % años después de aprobada; use validación de conocimientos', param_numeric('homologation.max_years') using errcode = 'P0001';
        end if;
    end if;
    return new;
end
$$;
create trigger trg_homologation_guard before insert or update on homologation for each row execute function guard_homologation();

create table titling
(
    id                 uuid primary key       default uuidv7(),
    student_id         uuid          not null unique references student (id),
    option             varchar(35)   not null check (option in ('TRABAJO_INTEGRACION_CURRICULAR', 'EXAMEN_COMPLEXIVO')),
    topic              varchar(300),
    advisor_teacher_id uuid references teacher (id),
    status             varchar(12)   not null default 'IN_PROGRESS' check (status in ('IN_PROGRESS', 'DEFENDED', 'APPROVED', 'FAILED')),
    grade              numeric(5, 2) check (grade >= 0),
    defended_on        date,
    act_number         varchar(40),
    title_registered_on date,
    created_at         timestamptz   not null default now(),
    created_by         uuid,
    updated_at         timestamptz,
    updated_by         uuid,
    version            bigint        not null default 0,
    check (status not in ('APPROVED', 'FAILED') or (grade is not null and defended_on is not null and act_number is not null))
);
