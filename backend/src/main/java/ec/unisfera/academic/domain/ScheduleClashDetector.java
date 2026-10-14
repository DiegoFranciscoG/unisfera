package ec.unisfera.academic.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** RN-06: detecta solapamientos reales entre los bloques de paralelos distintos. */
public final class ScheduleClashDetector {

    public record Scheduled(UUID sectionId, UUID subjectId, String label, List<TimeBlock> blocks) {
    }

    public record Clash(Scheduled first, Scheduled second, TimeBlock at) {
    }

    private ScheduleClashDetector() {
    }

    public static List<Clash> clashes(List<Scheduled> sections) {
        List<Clash> result = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            for (int j = i + 1; j < sections.size(); j++) {
                Scheduled a = sections.get(i);
                Scheduled b = sections.get(j);
                outer:
                for (TimeBlock x : a.blocks()) {
                    for (TimeBlock y : b.blocks()) {
                        if (x.overlaps(y)) {
                            result.add(new Clash(a, b, x));
                            break outer;
                        }
                    }
                }
            }
        }
        return result;
    }
}
