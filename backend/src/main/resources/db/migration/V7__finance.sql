-- =============================================================================
-- V7 · Financiero: tarifario versionado, rubros, descuentos, cuotas, libro mayor,
--      pagos idempotentes, bitácora de pasarela, facturas, notas de crédito y conciliación.
-- RN-09 · RN-21 · RN-24 · RN-28 · RN-29
-- =============================================================================

create table charge_concept
(
    id           uuid primary key      default uuidv7(),
    code         varchar(30)  not null unique,
    name         varchar(120) not null,
    kind         varchar(15)  not null check (kind in ('ENROLLMENT', 'TUITION', 'SURCHARGE', 'LATE_FEE', 'CERTIFICATE', 'SERVICE', 'OTHER')),
    tax_category varchar(10)  not null check (tax_category in ('EDUCATION', 'GENERAL')),
    discountable boolean      not null default true,
    active       boolean      not null default true,
    created_at   timestamptz  not null default now(),
    created_by   uuid,
    updated_at   timestamptz,
    updated_by   uuid,
    version      bigint       not null default 0
);

create table tariff
(
    id               uuid primary key        default uuidv7(),
    concept_id       uuid           not null references charge_concept (id),
    career_id        uuid references career (id),
    level_number     smallint check (level_number between 1 and 20),
    period_id        uuid           not null references academic_period (id),
    calc_mode        varchar(12)    not null check (calc_mode in ('FLAT', 'PER_CREDIT', 'PER_SUBJECT')),
    amount           numeric(12, 2) not null check (amount >= 0),
    tariff_version   int            not null default 1 check (tariff_version > 0),
    status           varchar(12)    not null default 'ACTIVE' check (status in ('ACTIVE', 'SUPERSEDED')),
    superseded_by_id uuid references tariff (id),
    change_reason    text,
    created_at       timestamptz    not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid,
    version          bigint         not null default 0,
    constraint uq_tariff_version unique nulls not distinct (concept_id, career_id, level_number, period_id, tariff_version),
    check (status <> 'SUPERSEDED' or superseded_by_id is not null)
);
create unique index uq_tariff_active on tariff (concept_id, career_id, level_number, period_id) nulls not distinct where status = 'ACTIVE';

-- Un precio publicado no se reescribe: se reemplaza por una nueva versión
create or replace function guard_tariff() returns trigger
    language plpgsql as
$$
begin
    if (to_jsonb(new) - 'status' - 'superseded_by_id' - 'updated_at' - 'updated_by' - 'version')
        is distinct from (to_jsonb(old) - 'status' - 'superseded_by_id' - 'updated_at' - 'updated_by' - 'version')
        or old.status = 'SUPERSEDED' then
        raise exception 'LEY-1: una tarifa publicada no se edita; publique una nueva versión' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_tariff_guard before update on tariff for each row execute function guard_tariff();

create table payment_plan
(
    id                 uuid primary key        default uuidv7(),
    student_id         uuid           not null references student (id),
    period_id          uuid           not null references academic_period (id),
    enrollment_id      uuid           not null unique references enrollment (id),
    installments_count smallint       not null check (installments_count > 0),
    total              numeric(12, 2) not null check (total >= 0),
    status             varchar(10)    not null default 'ACTIVE' check (status in ('ACTIVE', 'CANCELLED')),
    created_at         timestamptz    not null default now(),
    created_by         uuid,
    updated_at         timestamptz,
    updated_by         uuid,
    version            bigint         not null default 0
);

create table installment
(
    id               uuid primary key        default uuidv7(),
    plan_id          uuid           not null references payment_plan (id),
    student_id       uuid           not null references student (id),
    period_id        uuid           not null references academic_period (id),
    number           smallint       not null check (number > 0),
    due_date         date           not null,
    amount_due       numeric(12, 2) not null check (amount_due >= 0),
    late_fee_accrued numeric(12, 2) not null default 0 check (late_fee_accrued >= 0),
    amount_paid      numeric(12, 2) not null default 0 check (amount_paid >= 0),
    status           varchar(10)    not null default 'PENDING' check (status in ('PENDING', 'PAID', 'VOID')),
    last_late_fee_on date,
    created_at       timestamptz    not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid,
    version          bigint         not null default 0,
    constraint uq_installment unique (plan_id, number),
    constraint ck_installment_paid check (amount_paid <= amount_due + late_fee_accrued),
    constraint ck_installment_status check (status <> 'PAID' or amount_paid = amount_due + late_fee_accrued)
);
create index ix_installment_student on installment (student_id, status, due_date);

create table charge
(
    id              uuid primary key        default uuidv7(),
    student_id      uuid           not null references student (id),
    period_id       uuid           not null references academic_period (id),
    enrollment_id   uuid references enrollment (id),
    concept_id      uuid           not null references charge_concept (id),
    tariff_id       uuid references tariff (id),
    description     varchar(200)   not null,
    quantity        numeric(8, 2)  not null default 1 check (quantity > 0),
    unit_amount     numeric(12, 2) not null check (unit_amount >= 0),
    base_amount     numeric(12, 2) not null check (base_amount >= 0),
    discount_amount numeric(12, 2) not null default 0 check (discount_amount >= 0),
    net_amount      numeric(12, 2) generated always as (base_amount - discount_amount) stored,
    tax_rate        numeric(5, 2)  not null default 0 check (tax_rate >= 0),
    status          varchar(10)    not null default 'ACTIVE' check (status in ('ACTIVE', 'REVERSED')),
    reversed_at     timestamptz,
    reversed_by     uuid,
    reversal_reason text,
    created_at      timestamptz    not null default now(),
    created_by      uuid,
    updated_at      timestamptz,
    updated_by      uuid,
    version         bigint         not null default 0,
    check (discount_amount <= base_amount),
    check (status <> 'REVERSED' or (reversed_at is not null and reversal_reason is not null))
);
create index ix_charge_student on charge (student_id, period_id);

create table discount_applied
(
    id          uuid primary key        default uuidv7(),
    charge_id   uuid           not null references charge (id),
    student_id  uuid           not null references student (id),
    period_id   uuid           not null references academic_period (id),
    origin_type varchar(15)    not null check (origin_type in ('SCHOLARSHIP', 'EARLY_PAYMENT', 'SIBLING', 'AGREEMENT', 'FINANCIAL_AID')),
    origin_id   uuid,
    percentage  numeric(5, 2) check (percentage > 0 and percentage <= 100),
    amount      numeric(12, 2) not null check (amount > 0),
    precedence  smallint       not null,
    status      varchar(10)    not null default 'ACTIVE' check (status in ('ACTIVE', 'REVERSED')),
    created_at  timestamptz    not null default now(),
    created_by  uuid,
    updated_at  timestamptz,
    updated_by  uuid,
    version     bigint         not null default 0
);
create index ix_discount_charge on discount_applied (charge_id);

-- Libro mayor del estudiante (append-only): la fuente de verdad del saldo (RN-29)
create table account_entry
(
    id                uuid primary key        default uuidv7(),
    student_id        uuid           not null references student (id),
    period_id         uuid references academic_period (id),
    entry_type        varchar(12)    not null check (entry_type in ('CHARGE', 'DISCOUNT', 'PAYMENT', 'LATE_FEE', 'REVERSAL', 'CREDIT_NOTE', 'ADJUSTMENT')),
    direction         smallint       not null check (direction in (1, -1)),
    amount            numeric(12, 2) not null check (amount > 0),
    charge_id         uuid references charge (id),
    installment_id    uuid references installment (id),
    payment_id        uuid,
    reverses_entry_id uuid unique references account_entry (id),
    description       varchar(200)   not null,
    occurred_at       timestamptz    not null default now(),
    created_at        timestamptz    not null default now(),
    created_by        uuid,
    constraint ck_entry_direction check (
        (entry_type in ('CHARGE', 'LATE_FEE') and direction = 1)
            or (entry_type in ('DISCOUNT', 'PAYMENT', 'CREDIT_NOTE') and direction = -1)
            or entry_type in ('REVERSAL', 'ADJUSTMENT')),
    check (entry_type <> 'REVERSAL' or reverses_entry_id is not null)
);
create index ix_entry_student on account_entry (student_id, occurred_at);

create or replace function guard_reversal_entry() returns trigger
    language plpgsql as
$$
declare
    r account_entry%rowtype;
begin
    if new.entry_type = 'REVERSAL' then
        select * into r from account_entry where id = new.reverses_entry_id;
        if r.student_id <> new.student_id or r.amount <> new.amount or r.direction <> -new.direction then
            raise exception 'RN-29: un reverso debe anular exactamente el movimiento original' using errcode = 'P0001';
        end if;
    end if;
    return new;
end
$$;
create trigger trg_entry_reversal before insert on account_entry for each row execute function guard_reversal_entry();
create trigger trg_entry_no_update before update on account_entry for each row execute function forbid_update();

create table payment
(
    id                uuid primary key        default uuidv7(),
    student_id        uuid           not null references student (id),
    amount            numeric(12, 2) not null check (amount > 0),
    currency          char(3)        not null,
    method            varchar(10)    not null check (method in ('GATEWAY', 'TRANSFER', 'CASH', 'CARD_POS')),
    gateway           varchar(30),
    gateway_reference varchar(120),
    status            varchar(10)    not null default 'PENDING' check (status in ('PENDING', 'CONFIRMED', 'REJECTED', 'ANNULLED')),
    idempotency_key   varchar(80)    not null,
    payer_ip          varchar(64),
    registered_by     uuid,
    confirmed_at      timestamptz,
    rejected_at       timestamptz,
    rejection_reason  varchar(200),
    annulled_at       timestamptz,
    annulled_by       uuid,
    annulment_reason  text,
    created_at        timestamptz    not null default now(),
    created_by        uuid,
    updated_at        timestamptz,
    updated_by        uuid,
    version           bigint         not null default 0,
    -- RN-24: un pago nunca se registra dos veces
    constraint uq_payment_idempotency unique (idempotency_key),
    constraint uq_payment_gateway_ref unique (gateway, gateway_reference),
    check (status <> 'CONFIRMED' or confirmed_at is not null),
    check (status <> 'ANNULLED' or (annulled_at is not null and annulled_by is not null and length(trim(annulment_reason)) >= 10))
);
create index ix_payment_student on payment (student_id, created_at desc);

-- RN-24 / RN-28: transiciones válidas del pago
create or replace function guard_payment() returns trigger
    language plpgsql as
$$
begin
    if new.amount <> old.amount or new.student_id <> old.student_id or new.idempotency_key <> old.idempotency_key or new.currency <> old.currency then
        raise exception 'LEY-2: el monto, el estudiante y la clave de un pago no se editan' using errcode = 'P0001';
    end if;
    if new.status = old.status then
        if old.status <> 'PENDING' and (to_jsonb(new) - 'updated_at' - 'updated_by' - 'version') is distinct from (to_jsonb(old) - 'updated_at' - 'updated_by' - 'version') then
            raise exception 'RN-28: un pago % es definitivo', old.status using errcode = 'P0001';
        end if;
        return new;
    end if;
    if not ((old.status = 'PENDING' and new.status in ('CONFIRMED', 'REJECTED'))
        or (old.status = 'CONFIRMED' and new.status = 'ANNULLED')) then
        raise exception 'RN-28: transición de pago no permitida (% → %)', old.status, new.status using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_payment_guard before update on payment for each row execute function guard_payment();

create table payment_allocation
(
    id             uuid primary key        default uuidv7(),
    payment_id     uuid           not null references payment (id),
    installment_id uuid           not null references installment (id),
    kind           varchar(10)    not null check (kind in ('LATE_FEE', 'PRINCIPAL')),
    amount         numeric(12, 2) not null check (amount > 0),
    created_at     timestamptz    not null default now(),
    created_by     uuid
);
create index ix_allocation_payment on payment_allocation (payment_id);
create trigger trg_allocation_no_update before update on payment_allocation for each row execute function forbid_update();

create table gateway_transaction
(
    id               uuid primary key     default uuidv7(),
    payment_id       uuid references payment (id),
    gateway          varchar(30) not null,
    operation        varchar(15) not null check (operation in ('CREATE', 'NOTIFICATION', 'QUERY')),
    event_id         varchar(120),
    request_payload  text,
    response_payload text,
    outcome          varchar(30),
    signature_valid  boolean,
    attempts         int         not null default 1 check (attempts > 0),
    first_seen_at    timestamptz not null default now(),
    last_seen_at     timestamptz not null default now(),
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid
);
-- RN-24: una misma notificación se registra una vez y se cuentan sus reintentos
create unique index uq_gateway_event on gateway_transaction (gateway, event_id) where operation = 'NOTIFICATION';

create table invoice_sequence
(
    establishment  char(3) not null,
    emission_point char(3) not null,
    doc_type       char(2) not null check (doc_type in ('01', '04')),
    next_value     bigint  not null check (next_value > 0),
    primary key (establishment, emission_point, doc_type)
);

create table invoice
(
    id                   uuid primary key        default uuidv7(),
    payment_id           uuid           not null unique references payment (id),
    establishment        char(3)        not null,
    emission_point       char(3)        not null,
    sequential           bigint         not null check (sequential between 1 and 999999999),
    access_key           char(49)       not null unique check (access_key ~ '^[0-9]{49}$'),
    environment          char(1)        not null check (environment in ('1', '2')),
    issue_date           date           not null,
    buyer_id_type        char(2)        not null,
    buyer_id             varchar(20)    not null,
    buyer_name           varchar(300)   not null,
    subtotal_zero        numeric(12, 2) not null check (subtotal_zero >= 0),
    subtotal_taxed       numeric(12, 2) not null check (subtotal_taxed >= 0),
    tax_amount           numeric(12, 2) not null check (tax_amount >= 0),
    total                numeric(12, 2) not null check (total > 0),
    sri_status           varchar(12)    not null default 'GENERATED' check (sri_status in ('GENERATED', 'SENT', 'AUTHORIZED', 'REJECTED', 'ANNULLED')),
    authorization_number varchar(49),
    authorized_at        timestamptz,
    xml                  text           not null,
    created_at           timestamptz    not null default now(),
    created_by           uuid,
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint         not null default 0,
    constraint uq_invoice_number unique (establishment, emission_point, sequential),
    check (total = subtotal_zero + subtotal_taxed + tax_amount)
);

create table credit_note
(
    id             uuid primary key        default uuidv7(),
    invoice_id     uuid           not null references invoice (id),
    payment_id     uuid           not null unique references payment (id),
    establishment  char(3)        not null,
    emission_point char(3)        not null,
    sequential     bigint         not null check (sequential between 1 and 999999999),
    access_key     char(49)       not null unique check (access_key ~ '^[0-9]{49}$'),
    issue_date     date           not null,
    reason         text           not null check (length(trim(reason)) >= 10),
    amount         numeric(12, 2) not null check (amount > 0),
    sri_status     varchar(12)    not null default 'GENERATED' check (sri_status in ('GENERATED', 'SENT', 'AUTHORIZED', 'REJECTED')),
    xml            text           not null,
    created_at     timestamptz    not null default now(),
    created_by     uuid,
    updated_at     timestamptz,
    updated_by     uuid,
    version        bigint         not null default 0,
    constraint uq_credit_note_number unique (establishment, emission_point, sequential)
);

-- RN-28: un pago anulado tiene siempre su nota de crédito (verificado al confirmar la transacción)
create or replace function check_payment_credit_note() returns trigger
    language plpgsql as
$$
begin
    if new.status = 'ANNULLED' and exists (select 1 from invoice where payment_id = new.id)
        and not exists (select 1 from credit_note where payment_id = new.id) then
        raise exception 'RN-28: el pago facturado se anula únicamente con nota de crédito' using errcode = 'P0001';
    end if;
    return null;
end
$$;
create constraint trigger trg_payment_credit_note after update of status on payment
    deferrable initially deferred for each row execute function check_payment_credit_note();

create table reconciliation
(
    id             uuid primary key     default uuidv7(),
    source         varchar(10) not null check (source in ('BANK', 'GATEWAY')),
    file_id        uuid references stored_file (id),
    statement_date date        not null,
    total_rows     int         not null check (total_rows >= 0),
    matched        int         not null check (matched >= 0),
    discrepancies  int         not null check (discrepancies >= 0),
    processed_by   uuid,
    created_at     timestamptz not null default now(),
    created_by     uuid
);

create table reconciliation_item
(
    id                 uuid primary key        default uuidv7(),
    reconciliation_id  uuid           not null references reconciliation (id),
    line_number        int            not null check (line_number > 0),
    external_reference varchar(120)   not null,
    amount             numeric(12, 2) not null,
    occurred_on        date,
    payment_id         uuid references payment (id),
    outcome            varchar(16)    not null check (outcome in ('MATCHED', 'AMOUNT_MISMATCH', 'NOT_FOUND', 'DUPLICATE')),
    note               varchar(200),
    created_at         timestamptz    not null default now()
);

-- RN-29: estado de cuenta = cargos + mora − descuentos − pagos ± reversos
create view v_account_statement as
select e.student_id,
       coalesce(sum(e.amount) filter (where e.entry_type = 'CHARGE'), 0)                            as charges,
       coalesce(sum(e.amount) filter (where e.entry_type = 'LATE_FEE'), 0)                          as late_fees,
       coalesce(sum(e.amount) filter (where e.entry_type = 'DISCOUNT'), 0)                          as discounts,
       coalesce(sum(e.amount) filter (where e.entry_type = 'PAYMENT'), 0)                           as payments,
       coalesce(sum(e.amount) filter (where e.entry_type = 'CREDIT_NOTE'), 0)                       as credit_notes,
       coalesce(sum(e.direction * e.amount) filter (where e.entry_type in ('REVERSAL', 'ADJUSTMENT')), 0) as reversals,
       coalesce(sum(e.direction * e.amount), 0)                                                     as balance
from account_entry e
group by e.student_id;
