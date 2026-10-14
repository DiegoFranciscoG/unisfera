package ec.unisfera.seed;

import ec.unisfera.identity.domain.CedulaValidator;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Personas ficticias: combinaciones aleatorias de nombres y apellidos frecuentes y cédulas con
 * dígito verificador válido generadas con semilla fija. Ninguna corresponde a una persona real.
 */
final class SyntheticPeople {

    record Person(String idNumber, String firstNames, String lastNames, LocalDate birthDate, String sex, String phone) {
    }

    private static final List<String> FEMALE = List.of("María", "Ana", "Lucía", "Camila", "Valentina", "Daniela", "Gabriela", "Sofía", "Paula",
            "Andrea", "Carolina", "Fernanda", "Isabel", "Mariana", "Verónica", "Paola", "Karla", "Jimena", "Doménica", "Emilia", "Natalia", "Antonella");
    private static final List<String> MALE = List.of("José", "Juan", "Luis", "Carlos", "Andrés", "Diego", "Mateo", "Sebastián", "Santiago",
            "Fernando", "Pablo", "Esteban", "Jorge", "David", "Alejandro", "Martín", "Ricardo", "Nicolás", "Joaquín", "Emilio", "Bryan", "Kevin");
    private static final List<String> SURNAMES = List.of("Andrade", "Vásquez", "Ordóñez", "Cárdenas", "Sarmiento", "Crespo", "Peralta", "Jaramillo",
            "Pesántez", "Cordero", "Ochoa", "Quezada", "Guamán", "Tenesaca", "Morocho", "Paredes", "Calle", "Merchán", "Torres", "León", "Zúñiga",
            "Abad", "Espinoza", "Rodas", "Malo", "Vintimilla", "Coronel", "Arízaga", "Molina", "Bravo", "Idrovo", "Neira", "Pacheco", "Sigüenza");

    private final SplittableRandom random;
    private final Set<String> used = new HashSet<>();

    SyntheticPeople(SplittableRandom random) {
        this.random = random;
    }

    Person next(int minAge, int maxAge, LocalDate today) {
        boolean female = random.nextBoolean();
        List<String> names = female ? FEMALE : MALE;
        String first = names.get(random.nextInt(names.size())) + " " + names.get(random.nextInt(names.size()));
        String last = SURNAMES.get(random.nextInt(SURNAMES.size())) + " " + SURNAMES.get(random.nextInt(SURNAMES.size()));
        LocalDate birth = today.minusYears(minAge + random.nextInt(maxAge - minAge + 1)).minusDays(random.nextInt(365));
        return new Person(cedula(), first, last, birth, female ? "MUJER" : "HOMBRE", "09" + (10_000_000 + random.nextInt(89_999_999)));
    }

    /** Provincia del Azuay (01) mayoritaria, como en una universidad de Cuenca; tercer dígito de persona natural. */
    String cedula() {
        while (true) {
            int province = random.nextInt(10) < 7 ? 1 : 1 + random.nextInt(24);
            String nine = String.format("%02d%d%06d", province, random.nextInt(6), random.nextInt(1_000_000));
            String cedula = nine + CedulaValidator.checkDigit(nine);
            if (used.add(cedula)) {
                return cedula;
            }
        }
    }
}
