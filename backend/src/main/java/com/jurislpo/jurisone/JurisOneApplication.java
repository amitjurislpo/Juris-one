package com.jurislpo.jurisone;

import com.jurislpo.jurisone.cli.Commands;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JurisOne REST API. The web app (juris-one-frontend, port 3005) is its only client; it proxies /api/* here.
 * With a command argument it runs that once and exits instead: {@code migrate}, {@code seed}, {@code reset} or {@code jobs} (see cli.Commands).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JurisOneApplication {
    public static void main(String[] args) {
        String command = Commands.of(args);
        SpringApplication app = new SpringApplication(JurisOneApplication.class);
        if (command == null) {
            app.run(args);
            return;
        }
        app.setWebApplicationType(WebApplicationType.NONE);
        System.setProperty("jurisone.jobs-enabled", "false"); // no scheduler: the command is the only thing that runs
        if ("reset".equals(command)) {
            System.setProperty("jurisone.reset", "true");               // FlywayConfig cleans before migrating
            System.setProperty("spring.flyway.clean-disabled", "false");
        }
        ConfigurableApplicationContext ctx = app.run(args);
        int code = ctx.getBean(Commands.class).run(command);
        System.exit(SpringApplication.exit(ctx, () -> code));
    }
}
