package jp.roastme;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  @Test
  void domainDoesNotDependOnFrameworkOrOuterLayers() {
    noClasses()
      .that()
      .resideInAPackage("..domain..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "org.springframework..",
        "jp.roastme.api..",
        "jp.roastme.application..",
        "jp.roastme.media..",
        "jp.roastme.budget.."
      )
      .check(new ClassFileImporter().importPackages("jp.roastme"));
  }
}
