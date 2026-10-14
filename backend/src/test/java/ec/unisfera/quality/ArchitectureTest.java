package ec.unisfera.quality;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reglas de arquitectura verificadas en cada build (ArchUnit usado como librería sobre JUnit 6). */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("ec.unisfera");
    }

    @Test
    void domainIsPureJavaWithoutSpringJpaOrJdbc() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "java.sql..",
                        "org.hibernate..", "jakarta.servlet..")
                .because("el dominio contiene las reglas que no se pueden romper y se prueba sin infraestructura")
                .check(classes);
    }

    @Test
    void controllersDelegateAndNeverTouchPersistenceDirectly() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("..persistence..", "org.springframework.jdbc..")
                .because("los controladores solo validan y delegan a servicios")
                .check(classes);
    }

    @Test
    void academicModuleDoesNotDependOnFinance() {
        noClasses().that().resideInAPackage("ec.unisfera.academic..")
                .should().dependOnClassesThat().resideInAPackage("ec.unisfera.finance..")
                .because("Académico publica eventos y define puertos; Financiero los implementa (inversión de dependencias)")
                .check(classes);
    }

    @Test
    void reportingNeverDependsOnWelfareSensitiveData() throws IOException {
        noClasses().that().resideInAPackage("ec.unisfera.reporting..")
                .should().dependOnClassesThat().haveSimpleName("WelfareCareService")
                .check(classes);
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/ec/unisfera/reporting"))) {
            List<Path> offenders = files.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    return Files.readString(f).contains("welfare_attention");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(offenders).as("RN-23: ningún reporte consulta atenciones de bienestar").isEmpty();
        }
    }

    @Test
    void noSystemOutOrPrintStackTrace() {
        noClasses().should().accessField(System.class, "out").orShould().accessField(System.class, "err")
                .because("los registros van por SLF4J con request-id").check(classes);
    }

    @Test
    void servicesLiveInApplicationPackages() {
        classes().that().haveSimpleNameEndingWith("Service").and().resideOutsideOfPackages("..manuals..", "..shared..", "..security..")
                .should().resideInAPackage("..application..").check(classes);
    }
}
