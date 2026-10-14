package ec.unisfera.academic.domain;

/** Bloque horario semanal en minutos desde la medianoche, intervalo semiabierto [inicio, fin). */
public record TimeBlock(int weekday, int startMinute, int endMinute) {

    public TimeBlock {
        if (startMinute >= endMinute) {
            throw new IllegalArgumentException("El bloque debe terminar después de empezar");
        }
    }

    public boolean overlaps(TimeBlock other) {
        return weekday == other.weekday && startMinute < other.endMinute && other.startMinute < endMinute;
    }
}
