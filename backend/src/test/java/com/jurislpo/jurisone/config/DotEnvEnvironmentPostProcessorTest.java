package com.jurislpo.jurisone.config;

import com.jurislpo.jurisone.config.DotEnvEnvironmentPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvEnvironmentPostProcessorTest {

    @Test
    void convertsPrismaDatabaseUrl() {
        Map<String, String> ds = DotEnvEnvironmentPostProcessor.datasourceFrom("postgresql://app:p%40ss@db.local:5436/jurisone?schema=public");
        assertThat(ds)
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db.local:5436/jurisone?currentSchema=public")
                .containsEntry("spring.datasource.username", "app")
                .containsEntry("spring.datasource.password", "p@ss");
    }

    @Test
    void parsesDotEnv(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".env");
        Files.writeString(f, String.join("\n",
                "# comment",
                "A=\"quoted # not a comment\"",
                "B=plain # trailing",
                "export C='single'",
                "",
                "EMPTY=\"\""));
        assertThat(DotEnvEnvironmentPostProcessor.parse(f))
                .containsEntry("A", "quoted # not a comment")
                .containsEntry("B", "plain")
                .containsEntry("C", "single")
                .containsEntry("EMPTY", "");
    }
}
