package com.masunov.task1;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDeliveryDocumentationContractTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void composeStartsTheApplicationAndHealthyPersistentPostgresDependency() throws IOException {
        Path composeFile = composeFile();
        assertTrue(Files.isRegularFile(composeFile),
                "Provide compose.yaml (or docker-compose.yml) at the project root.");

        String compose = content(composeFile);
        assertContains(compose, "services:");
        assertContains(compose, "postgres:");
        assertContains(compose, "image: postgres:");
        assertContains(compose, "healthcheck:");
        assertContains(compose, "pg_isready");
        assertContains(compose, "volumes:");
        assertContains(compose, "app:");
        assertContains(compose, "build:");
        assertContains(compose, "depends_on:");
        assertContains(compose, "service_healthy");
        assertContains(compose, "spring_datasource_url");
        assertContains(compose, "8080");
    }

    @Test
    void containerImageCanBuildTheSpringBootApplicationFromTheRepository() throws IOException {
        Path dockerfile = PROJECT_ROOT.resolve("Dockerfile");
        assertTrue(Files.isRegularFile(dockerfile), "Provide a Dockerfile at the project root.");

        String image = content(dockerfile);
        assertContains(image, "from ");
        assertContains(image, "mvn");
        assertContains(image, "java");
        assertContains(image, "jar");
        assertTrue(image.contains("entrypoint") || image.contains("cmd"),
                "The image must declare how the Spring Boot jar starts.");
    }

    @Test
    void readmeDocumentsLocalLifecyclePersistenceAndCoreApiConsumption() throws IOException {
        Path readme = PROJECT_ROOT.resolve("README.md");
        assertTrue(Files.isRegularFile(readme), "Provide README.md at the project root.");

        String documentation = content(readme);
        assertContains(documentation, "docker compose up --build");
        assertContains(documentation, "docker compose down");
        assertContains(documentation, "8080");
        assertContains(documentation, "postgres");
        assertContains(documentation, "volume");
        assertContains(documentation, "spring_datasource");
        assertContains(documentation, "post /users");
        assertContains(documentation, "post /calendars/{calendarid}/slots");
        assertContains(documentation, "post /calendars/{calendarid}/slots/{slotid}/meeting");
        assertContains(documentation, "get /calendars/{calendarid}/availability");
        assertContains(documentation, "400");
        assertContains(documentation, "404");
        assertContains(documentation, "409");
    }

    private Path composeFile() {
        Path composeYaml = PROJECT_ROOT.resolve("compose.yaml");
        if (Files.isRegularFile(composeYaml)) {
            return composeYaml;
        }
        return PROJECT_ROOT.resolve("docker-compose.yml");
    }

    private String content(Path path) throws IOException {
        return Files.readString(path).toLowerCase(Locale.ROOT);
    }

    private void assertContains(String text, String expected) {
        assertFalse(text.isBlank());
        assertTrue(text.contains(expected), () -> "Expected documentation/configuration to contain: " + expected);
    }
}
