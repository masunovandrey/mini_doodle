package com.masunov.task1.concurrency.user;

import com.masunov.task1.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserCreationConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsExactlyOneUserAndCalendarWhenEmailCreationRaces() throws Exception {
        String email = "duplicate-email-" + UUID.randomUUID() + "@example.com";
        List<Integer> statuses = concurrentlyCreateUsers(
                Map.of("email", email, "name", "First contender"),
                Map.of("email", email, "name", "Second contender")
        );

        assertOneCreatedAndOneConflict(statuses);
        assertEquals(1L, userCount("email = ?", email));
        assertEquals(1L, calendarCountForUsers("email = ?", email));
    }

    @Test
    void createsExactlyOneUserAndCalendarWhenCaseInsensitiveNameCreationRaces() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String name = "Case Insensitive Name " + suffix;
        List<Integer> statuses = concurrentlyCreateUsers(
                Map.of("email", "name-race-first-" + suffix + "@example.com", "name", name),
                Map.of("email", "name-race-second-" + suffix + "@example.com",
                        "name", name.toLowerCase(Locale.ROOT))
        );

        assertOneCreatedAndOneConflict(statuses);
        assertEquals(1L, userCount("lower(name) = lower(?)", name));
        assertEquals(1L, calendarCountForUsers("lower(name) = lower(?)", name));
    }

    private List<Integer> concurrentlyCreateUsers(Map<String, String> first, Map<String, String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> firstResponse = createUser(executor, ready, start, first);
            Future<Integer> secondResponse = createUser(executor, ready, start, second);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both user-creation contenders must become ready");
            start.countDown();
            return List.of(
                    firstResponse.get(20, TimeUnit.SECONDS),
                    secondResponse.get(20, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private Future<Integer> createUser(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            Map<String, String> request
    ) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        });
    }

    private void assertOneCreatedAndOneConflict(List<Integer> statuses) {
        assertTrue(statuses.stream().allMatch(status -> status == 201 || status == 409),
                () -> "Unexpected concurrent user-creation statuses: " + statuses);
        assertEquals(1L, statuses.stream().filter(status -> status == 201).count());
        assertEquals(1L, statuses.stream().filter(status -> status == 409).count());
    }

    private long userCount(String predicate, String value) {
        return jdbcTemplate.queryForObject(
                "select count(*) from app_user where " + predicate,
                Long.class,
                value
        );
    }

    private long calendarCountForUsers(String predicate, String value) {
        return jdbcTemplate.queryForObject(
                "select count(*) from personal_calendar calendar "
                        + "join app_user user_account on user_account.calendar_id = calendar.id "
                        + "where " + predicate,
                Long.class,
                value
        );
    }
}
