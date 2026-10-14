package ec.unisfera.academic.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Motor de reglas de matrícula, puro y sin dependencias: recibe una fotografía del estado y
 * devuelve todas las violaciones (para la validación en vivo) o ninguna. La base vuelve a
 * verificar lo crítico al insertar (Ley 3).
 */
public final class EnrollmentPolicy {

    public record SubjectRules(UUID id, String code, String name, UUID curriculumId, Set<UUID> prerequisites, Set<UUID> corequisites) {
    }

    public record Selection(UUID sectionId, UUID subjectId, String sectionCode, int capacity, int enrolled, boolean open,
                            List<TimeBlock> blocks) {
    }

    public record Context(
            LocalDate today,
            EnrollmentWindow window,
            boolean hasSpecialAuthorization,
            String studentStatus,
            UUID studentCurriculumId,
            Map<UUID, SubjectRules> subjects,
            Set<UUID> approvedSubjects,
            Map<UUID, Integer> countedAttempts,
            Set<UUID> thirdAttemptAuthorizations,
            List<Selection> selections,
            int maxSubjects,
            boolean hasLoadException,
            boolean blockOnOverdueDebt,
            boolean hasOverdueDebt,
            boolean hasDebtException) {
    }

    public record Evaluation(Optional<EnrollmentWindow.Type> type, List<Violation> violations, Map<UUID, Integer> attempts) {

        public boolean ok() {
            return violations.isEmpty();
        }
    }

    private EnrollmentPolicy() {
    }

    public static Evaluation evaluate(Context c) {
        List<Violation> v = new ArrayList<>();
        Map<UUID, Integer> attempts = new java.util.HashMap<>();

        Optional<EnrollmentWindow.Type> type = c.window().typeOn(c.today(), c.hasSpecialAuthorization());
        if (type.isEmpty()) {
            v.add(new Violation("RN-01", null, "Hoy no hay una ventana de matrícula abierta para usted"));
        }
        if (!Set.of("ADMITTED", "ENROLLED", "REGULAR").contains(c.studentStatus())) {
            v.add(new Violation("RN-30", null, "Su estado actual (" + c.studentStatus() + ") no permite matricularse"));
        }
        if (c.blockOnOverdueDebt() && c.hasOverdueDebt() && !c.hasDebtException()) {
            v.add(new Violation("RN-08", null, "Tiene valores vencidos: regularice su pago para matricularse"));
        }
        if (c.selections().isEmpty()) {
            v.add(new Violation("RN-04", null, "Seleccione al menos una asignatura"));
        }
        if (c.selections().size() > c.maxSubjects() && !c.hasLoadException()) {
            v.add(new Violation("RN-04", null, "Puede tomar como máximo " + c.maxSubjects() + " asignaturas en el período"));
        }

        Set<UUID> selectedSubjects = new HashSet<>();
        for (Selection s : c.selections()) {
            if (!selectedSubjects.add(s.subjectId())) {
                v.add(new Violation("RN-04", s.subjectId(), "Eligió dos paralelos de la misma asignatura"));
            }
        }

        for (Selection s : c.selections()) {
            SubjectRules subject = c.subjects().get(s.subjectId());
            if (subject == null || !subject.curriculumId().equals(c.studentCurriculumId())) {
                v.add(new Violation("RN-03", s.subjectId(), "La asignatura no pertenece a su malla"));
                continue;
            }
            if (c.approvedSubjects().contains(subject.id())) {
                v.add(new Violation("RN-02", subject.id(), subject.name() + " ya está aprobada"));
                continue;
            }
            Set<UUID> missing = PrerequisiteChecker.missingPrerequisites(subject.prerequisites(), c.approvedSubjects());
            if (!missing.isEmpty()) {
                v.add(new Violation("RN-02", subject.id(), subject.name() + " requiere aprobar antes: " + names(missing, c.subjects())));
            }
            Set<UUID> missingCo = PrerequisiteChecker.missingCorequisites(subject.corequisites(), c.approvedSubjects(), selectedSubjects);
            if (!missingCo.isEmpty()) {
                v.add(new Violation("RN-02", subject.id(), subject.name() + " debe tomarse junto con: " + names(missingCo, c.subjects())));
            }
            int counted = c.countedAttempts().getOrDefault(subject.id(), 0);
            attempts.put(subject.id(), AttemptPolicy.nextAttempt(counted));
            switch (AttemptPolicy.evaluate(counted, c.thirdAttemptAuthorizations().contains(subject.id()))) {
                case EXHAUSTED -> v.add(new Violation("RN-07", subject.id(), subject.name() + " ya se cursó tres veces: no hay cuarta matrícula"));
                case NEEDS_AUTHORIZATION -> v.add(new Violation("RN-07", subject.id(),
                        subject.name() + " sería su tercera matrícula: necesita una autorización registrada"));
                case ALLOWED -> { }
            }
            if (!s.open()) {
                v.add(new Violation("RN-05", subject.id(), "El paralelo " + s.sectionCode() + " de " + subject.name() + " está cerrado"));
            } else if (s.enrolled() >= s.capacity()) {
                v.add(new Violation("RN-05", subject.id(), "El paralelo " + s.sectionCode() + " de " + subject.name() + " ya no tiene cupos"));
            }
        }

        List<ScheduleClashDetector.Scheduled> scheduled = c.selections().stream()
                .map(s -> new ScheduleClashDetector.Scheduled(s.sectionId(), s.subjectId(), label(s, c.subjects()), s.blocks()))
                .toList();
        for (ScheduleClashDetector.Clash clash : ScheduleClashDetector.clashes(scheduled)) {
            v.add(new Violation("RN-06", clash.second().subjectId(), "Choque de horario entre " + clash.first().label()
                    + " y " + clash.second().label()));
        }
        return new Evaluation(type, List.copyOf(v), Map.copyOf(attempts));
    }

    private static String label(Selection s, Map<UUID, SubjectRules> subjects) {
        SubjectRules r = subjects.get(s.subjectId());
        return (r == null ? "?" : r.name()) + " (" + s.sectionCode() + ")";
    }

    private static String names(Set<UUID> ids, Map<UUID, SubjectRules> subjects) {
        return ids.stream().map(id -> subjects.containsKey(id) ? subjects.get(id).name() : id.toString()).sorted()
                .reduce((a, b) -> a + ", " + b).orElse("");
    }
}
