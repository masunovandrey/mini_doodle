package com.masunov.task1;

import org.springframework.boot.SpringApplication;

public class TestTask1Application {

    public static void main(String[] args) {
        SpringApplication.from(Task1Application::main).with(TestcontainersConfiguration.class).run(args);
    }

}
