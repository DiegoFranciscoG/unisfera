package ec.unisfera.parameters.application;

/** Nombres de los parámetros. Son claves, no valores: los valores viven en la tabla parameter. */
public final class ParameterKeys {

    public static final String SCALE_MIN = "grading.scale_min";
    public static final String SCALE_MAX = "grading.scale_max";
    public static final String PASSING_GRADE = "grading.passing_grade";
    public static final String MAX_COMPONENT_WEIGHT = "grading.max_component_weight";
    public static final String MIN_COMPONENTS = "grading.min_components";
    public static final String MIN_ATTENDANCE = "attendance.min_percentage";
    public static final String ATTENDANCE_WARNING_MARGIN = "attendance.warning_margin_pct";
    public static final String MAX_SUBJECTS = "enrollment.max_subjects_per_period";
    public static final String REGULAR_MIN_LOAD = "enrollment.regular_min_load_pct";
    public static final String WITHDRAWAL_DEADLINE_DAYS = "academic.withdrawal_deadline_days";
    public static final String HOMOLOGATION_MIN_CONTENT = "homologation.min_content_pct";
    public static final String HOMOLOGATION_MAX_YEARS = "homologation.max_years";
    public static final String CURRENCY = "finance.currency";
    public static final String BLOCK_ON_DEBT = "finance.block_enrollment_on_overdue_debt";
    public static final String GRACE_DAYS = "finance.grace_days";
    public static final String LATE_FEE_MONTHLY_RATE = "finance.late_fee_monthly_rate";
    public static final String EXTRAORDINARY_SURCHARGE = "finance.extraordinary_surcharge_pct";
    public static final String INSTALLMENTS = "finance.installments_count";
    public static final String INSTALLMENT_INTERVAL_DAYS = "finance.installment_interval_days";
    public static final String FIRST_INSTALLMENT_DAYS = "finance.first_installment_due_days";
    public static final String DISCOUNT_PRECEDENCE = "finance.discount_precedence";
    public static final String ALLOCATION_ORDER = "finance.payment_allocation_order";
    public static final String IVA_GENERAL = "tax.iva_rate_general";
    public static final String IVA_EDUCATION = "tax.iva_rate_education";
    public static final String SRI_ENVIRONMENT = "sri.environment";
    public static final String ACCESS_TOKEN_MINUTES = "security.access_token_minutes";
    public static final String REFRESH_TOKEN_HOURS = "security.refresh_token_hours";
    public static final String INACTIVITY_MINUTES = "security.inactivity_minutes";
    public static final String CHALLENGE_TOKEN_MINUTES = "security.challenge_token_minutes";
    public static final String MAX_FAILED_ATTEMPTS = "security.max_failed_attempts";
    public static final String LOCKOUT_BASE_SECONDS = "security.lockout_base_seconds";
    public static final String LOCKOUT_MAX_SECONDS = "security.lockout_max_seconds";
    public static final String PASSWORD_MIN_LENGTH = "security.password_min_length";
    public static final String PASSWORD_MAX_AGE_DAYS = "security.password_max_age_days";
    public static final String PASSWORD_HISTORY = "security.password_history_count";
    public static final String TEMP_PASSWORD_HOURS = "security.temp_password_hours";
    public static final String RESET_TOKEN_MINUTES = "security.reset_token_minutes";
    public static final String RATE_LIMIT_LOGIN = "security.rate_limit_login_per_minute";
    public static final String RATE_LIMIT_API = "security.rate_limit_api_per_minute";
    public static final String COMMITTEE_MIN_MEMBERS = "welfare.committee_min_members";
    public static final String MIN_SCHOLARSHIP_COVERAGE = "welfare.min_scholarship_coverage_pct";
    public static final String SMALL_CELL_THRESHOLD = "reports.small_cell_threshold";
    public static final String REQUEST_DUE_DAYS = "requests.default_due_days";
    public static final String DROPOUT_MAX_FAILED = "dropout.max_failed_subjects";
    public static final String DROPOUT_ATTENDANCE = "dropout.attendance_alert_pct";
    public static final String UPLOAD_MAX_BYTES = "uploads.max_size_bytes";
    public static final String UPLOAD_TYPES = "uploads.allowed_types";

    private ParameterKeys() {
    }
}
