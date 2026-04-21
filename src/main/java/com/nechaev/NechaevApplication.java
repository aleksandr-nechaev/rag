package com.nechaev;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NechaevApplication {

    public static void main(String[] args) {
        SpringApplication.run(NechaevApplication.class, args);
    }
}
