package ec.unisfera.academic.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EnrollmentPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final EnrollmentWindow WINDOW = new EnrollmentWindow(TODAY.minusDays(2), TODAY.plusDays(2), TODAY.plusDays(7), TODAY.plusDays(10));
    private final UUID curriculum = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private Map<UUID, EnrollmentPolicy.SubjectRules> rules() {
        return Map.of(
                a, new EnrollmentPolicy.SubjectRules(a, "A", "Cálculo I", curriculum, Set.of(), Set.of()),
                b, new EnrollmentPolicy.SubjectRules(b, "B", "Física I", curriculum, Set.of(), Set.of()),
                c, new EnrollmentPolicy.SubjectRules(c, "C", "Cálculo II", curriculum, Set.of(a), Set.of()));
    }

    private EnrollmentPolicy.Selection section(UUID subject, int weekday, int start, int end) {
        return new EnrollmentPolicy.Selection(UUID.randomUUID(), subject, "A1", 30, 10, true, List.of(new TimeBlock(weekday, start, end)));
    }

    private EnrollmentPolicy.Context context(LocalDate today, String status, Set<UUID> approved, Map<UUID, Integer> attempts, Set<UUID> third,
                                             List<EnrollmentPolicy.Selection> selections, boolean debt) {
        return new EnrollmentPolicy.Context(today, WINDOW, false, status, curriculum, rules(), approved, attempts, third, selections, 3, false,
                true, debt, false);
    }

    @Test
    void validSelectionPassesAndComputesFirstAttempt() {
        var eval = EnrollmentPolicy.evaluate(context(TODAY, "ADMITTED", Set.of(), Map.of(), Set.of(), List.of(section(a, 1, 420, 540)), false));
        assertThat(eval.ok()).isTrue();
        assertThat(eval.type()).contains(EnrollmentWindow.Type.ORDINARY);
        assertThat(eval.attempts()).containsEntry(a, 1);
    }

    @Test
    void rn01_noWindowNoEnrollment() {
        var eval = EnrollmentPolicy.evaluate(context(TODAY.plusDays(30), "REGULAR", Set.of(), Map.of(), Set.of(), List.of(section(a, 1, 420, 540)), false));
        assertThat(eval.violations()).extracting(Violation::code).contains("RN-01");
    }

    @Test
    void rn01_windowsResolveOrdinaryExtraordinaryAndSpecial() {
        assertThat(WINDOW.typeOn(TODAY, false)).contains(EnrollmentWindow.Type.ORDINARY);
        assertThat(WINDOW.typeOn(TODAY.plusDays(5), false)).contains(EnrollmentWindow.Type.EXTRAORDINARY);
        assertThat(WINDOW.typeOn(TODAY.plusDays(9), false)).isEmpty();
        assertThat(WINDOW.typeOn(TODAY.plusDays(9), true)).contains(EnrollmentWindow.Type.SPECIAL);
        assertThat(WINDOW.typeOn(TODAY.plusDays(11), true)).isEmpty();
    }

    @Test
    void rn03_subjectFromAnotherCurriculumIsRejected() {
        var foreign = new EnrollmentPolicy.Selection(UUID.randomUUID(), UUID.randomUUID(), "X", 30, 0, true, List.of());
        var eval = EnrollmentPolicy.evaluate(context(TODAY, "REGULAR", Set.of(), Map.of(), Set.of(), List.of(foreign), false));
        assertThat(eval.violations()).extracting(Violation::code).contains("RN-03");
    }

    @Test
    void rn04_loadLimitFromParameter() {
        var eval = EnrollmentPolicy.evaluate(new EnrollmentPolicy.Context(TODAY, WINDOW, false, "REGULAR", curriculum, rules(), Set.of(a), Map.of(),
                Set.of(), List.of(section(a, 1, 420, 480), section(b, 2, 420, 480), section(c, 3, 420, 480)), 2, false, true, false, false));
        assertThat(eval.violations()).extracting(Violation::code).contains("RN-04");
    }

    @Test
    void rn05_fullOrClosedSectionsAreRejected() {
        var full = new EnrollmentPolicy.Selection(UUID.randomUUID(), a, "A1", 30, 30, true, List.of());
        var closed = new EnrollmentPolicy.Selection(UUID.randomUUID(), b, "B1", 30, 0, false, List.of());
        var eval = EnrollmentPolicy.evaluate(context(TODAY, "REGULAR", Set.of(), Map.of(), Set.of(), List.of(full, closed), false));
        assertThat(eval.violations()).filteredOn(v -> v.code().equals("RN-05")).hasSize(2);
    }

    @Test
    void rn06_overlappingBlocksClashButAdjacentOnesDoNot() {
        assertThat(EnrollmentPolicy.evaluate(context(TODAY, "REGULAR", Set.of(), Map.of(), Set.of(),
                List.of(section(a, 1, 420, 540), section(b, 1, 480, 600)), false)).violations()).extracting(Violation::code).contains("RN-06");
        assertThat(EnrollmentPolicy.evaluate(context(TODAY, "REGULAR", Set.of(), Map.of(), Set.of(),
                List.of(section(a, 1, 420, 540), section(b, 1, 540, 600)), false)).ok()).isTrue();
        assertThat(new TimeBlock(1, 420, 540).overlaps(new TimeBlock(2, 420, 540))).isFalse();
        assertThatThrownBy(() -> new TimeBlock(1, 540, 420)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rn07_thirdNeedsAuthorizationFourthIsImpossible() {
        assertThat(AttemptPolicy.evaluate(1, false)).isEqualTo(AttemptPolicy.Outcome.ALLOWED);
        assertThat(AttemptPolicy.evaluate(2, false)).isEqualTo(AttemptPolicy.Outcome.NEEDS_AUTHORIZATION);
        assertThat(AttemptPolicy.evaluate(2, true)).isEqualTo(AttemptPolicy.Outcome.ALLOWED);
        assertThat(AttemptPolicy.evaluate(3, true)).isEqualTo(AttemptPolicy.Outcome.EXHAUSTED);
        var eval = EnrollmentPolicy.evaluate(context(TODAY, "REGULAR", Set.of(), Map.of(a, 2), Set.of(), List.of(section(a, 1, 420, 540)), false));
        assertThat(eval.violations()).extracting(Violation::code).containsExactly("RN-07");
    }

    @Test
    void rn08_overdueDebtBlocksUnlessExcepted() {
        var eval = EnrollmentPolicy.evaluate(context(TODAY, "REGULAR", Set.of(), Map.of(), Set.of(), List.of(section(a, 1, 420, 540)), true));
        assertThat(eval.violations()).extracting(Violation::code).contains("RN-08");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "SUSPENDED", "COMPLETED", "GRADUATED"})
    void rn30_onlyActiveStatusesCanEnroll(String status) {
        var eval = EnrollmentPolicy.evaluate(context(TODAY, status, Set.of(), Map.of(), Set.of(), List.of(section(a, 1, 420, 540)), false));
        assertThat(eval.violations()).extracting(Violation::code).contains("RN-30");
    }

    @Test
    void rn30_stateMachineFollowsTheCatalog() {
        var machine = new StudentStateMachine(Set.of(
                new StudentStateMachine.Transition("ADMITTED", "ENROLLED", false),
                new StudentStateMachine.Transition("SUSPENDED", "REGULAR", true)));
        machine.check("ADMITTED", "ENROLLED", null);
        assertThatThrownBy(() -> machine.check("ENROLLED", "ADMITTED", null)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("RN-30".substring(0, 0));
        assertThatThrownBy(() -> machine.check("SUSPENDED", "REGULAR", " ")).hasMessageContaining("proceso explícito");
        machine.check("SUSPENDED", "REGULAR", "REINSTATEMENT");
        assertThat(machine.canEnroll("REGULAR")).isTrue();
        assertThat(machine.canEnroll("GRADUATED")).isFalse();
    }

    /**
     * T-05 · RN-02: se generan miles de mallas, estados y selecciones al azar; ninguna combinación
     * permite matricular una asignatura con un prerrequisito pendiente, y nunca se bloquea una válida.
     */
    @Test
    void rn02_noGeneratedCombinationAllowsAPendingPrerequisite() {
        SplittableRandom random = new SplittableRandom(20261013);
        for (int round = 0; round < 3_000; round++) {
            int levels = 2 + random.nextInt(5);
            Map<UUID, EnrollmentPolicy.SubjectRules> subjects = new HashMap<>();
            Map<Integer, List<UUID>> byLevel = new HashMap<>();
            for (int level = 1; level <= levels; level++) {
                for (int k = 0; k < 1 + random.nextInt(4); k++) {
                    UUID id = UUID.randomUUID();
                    Set<UUID> prereqs = new HashSet<>();
                    for (int lower = 1; lower < level; lower++) {
                        for (UUID candidate : byLevel.getOrDefault(lower, List.of())) {
                            if (random.nextInt(4) == 0) {
                                prereqs.add(candidate);
                            }
                        }
                    }
                    subjects.put(id, new EnrollmentPolicy.SubjectRules(id, "S" + level + k, "Asignatura " + level + "." + k, curriculum, prereqs, Set.of()));
                    byLevel.computeIfAbsent(level, x -> new ArrayList<>()).add(id);
                }
            }
            List<UUID> all = new ArrayList<>(subjects.keySet());
            Set<UUID> approved = new HashSet<>();
            all.forEach(id -> {
                if (random.nextInt(3) == 0) {
                    approved.add(id);
                }
            });
            List<EnrollmentPolicy.Selection> selection = new ArrayList<>();
            int day = 1;
            for (UUID id : all) {
                if (!approved.contains(id) && random.nextInt(3) == 0 && selection.size() < 6) {
                    selection.add(new EnrollmentPolicy.Selection(UUID.randomUUID(), id, "P", 40, 0, true, List.of(new TimeBlock(day++, 420, 480))));
                }
            }
            var eval = EnrollmentPolicy.evaluate(new EnrollmentPolicy.Context(TODAY, WINDOW, false, "REGULAR", curriculum, subjects, Set.copyOf(approved),
                    Map.of(), Set.of(), selection, 10, false, false, false, false));
            for (EnrollmentPolicy.Selection s : selection) {
                boolean pending = !approved.containsAll(subjects.get(s.subjectId()).prerequisites());
                boolean flagged = eval.violations().stream().anyMatch(v -> "RN-02".equals(v.code()) && s.subjectId().equals(v.subjectId()));
                assertThat(flagged).as("ronda %d: RN-02 debe marcarse si y solo si falta un prerrequisito", round).isEqualTo(pending);
            }
        }
    }

    @Test
    void rn02_corequisitesMustBeSelectedTogether() {
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        Map<UUID, EnrollmentPolicy.SubjectRules> subjects = Map.of(
                d, new EnrollmentPolicy.SubjectRules(d, "D", "Laboratorio", curriculum, Set.of(), Set.of(e)),
                e, new EnrollmentPolicy.SubjectRules(e, "E", "Teoría", curriculum, Set.of(), Set.of(d)));
        var alone = EnrollmentPolicy.evaluate(new EnrollmentPolicy.Context(TODAY, WINDOW, false, "REGULAR", curriculum, subjects, Set.of(), Map.of(), Set.of(),
                List.of(section(d, 1, 420, 480)), 5, false, false, false, false));
        assertThat(alone.violations()).extracting(Violation::code).contains("RN-02");
        var together = EnrollmentPolicy.evaluate(new EnrollmentPolicy.Context(TODAY, WINDOW, false, "REGULAR", curriculum, subjects, Set.of(), Map.of(),
                Set.of(), List.of(section(d, 1, 420, 480), section(e, 2, 420, 480)), 5, false, false, false, false));
        assertThat(together.ok()).isTrue();
    }
}
