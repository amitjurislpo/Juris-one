package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Files on tasks (plan §11): type by first bytes, 10 MB, 20 per task, soft delete, safe download. */
class TaskAttachmentApiTest extends TaskApiTestBase {

    static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);

    ResultActions upload(Cookie cookie, String taskId, String fileName, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments").file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes))
                .header("origin", "http://localhost:3005").header("host", "localhost:3005").cookie(cookie));
    }

    String uploaded(Cookie cookie, String taskId, String fileName, byte[] bytes) throws Exception {
        return read(upload(cookie, taskId, fileName, bytes).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    @Test
    void typesAreCheckedByTheirFirstBytes() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "ATT", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Files\"").get("id").asText();
        String pdf = uploaded(hr.cookie(), id, "contract.pdf", PDF);
        byte[] zip = {0x50, 0x4b, 0x03, 0x04, 1, 2, 3};
        String docx = uploaded(hr.cookie(), id, "notes.docx", zip);
        uploaded(hr.cookie(), id, "list.csv", "name,amount\nRiya,10\n".getBytes(StandardCharsets.UTF_8));
        upload(hr.cookie(), id, "setup.pdf", new byte[] {'M', 'Z', 0, 0}).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Upload a PDF, image, Word, Excel, PowerPoint, text or CSV file."));
        upload(hr.cookie(), id, "archive.zip", zip).andExpect(status().isBadRequest());
        upload(hr.cookie(), id, "binary.txt", new byte[] {'a', 0, 'b'}).andExpect(status().isBadRequest());
        upload(hr.cookie(), id, "empty.pdf", new byte[0]).andExpect(status().isBadRequest());
        byte[] big = Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);
        upload(hr.cookie(), id, "big.pdf", big).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Files up to 10 MB."));

        mockMvc.perform(get("/api/v1/tasks/" + id + "/attachments/" + pdf + "/file").cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        mockMvc.perform(get("/api/v1/tasks/" + id + "/attachments/" + docx + "/file").cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        JsonNode detail = getData(hr.cookie(), "/api/v1/tasks/" + id);
        assertThat(detail.get("attachments").size()).isEqualTo(3);
        assertThat(detail.get("attachmentCount").asInt()).isEqualTo(3);
        assertThat(detail.get("attachments").get(0).get("canDelete").asBoolean()).isTrue();
        // downloads are in the request audit trail; uploads in the history and the audit log
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE path = ?", Integer.class, "/api/v1/tasks/" + id + "/attachments/" + pdf + "/file")).isEqualTo(1);
        assertThat(auditRows("task.attachment_add", id)).isEqualTo(3);
        String stored = jdbc.queryForObject("SELECT file_path FROM task_attachments WHERE id = ?::uuid", String.class, pdf);
        assertThat(stored).startsWith("tasks/" + data.business() + "/" + id + "/");
    }

    @Test
    void whoMayAttachDownloadAndRemove() throws Exception {
        Person hr = person(hrRole, "hr"), viewer = person(employeeRole, "viewer"), member = person(employeeRole, "member");
        String projectId = project(hr.cookie(), "WHO", "BUSINESS");
        addMember(hr.cookie(), projectId, member, "MEMBER");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Shared files\"").get("id").asText();
        upload(viewer.cookie(), id, "mine.pdf", PDF).andExpect(status().isForbidden());
        String file = uploaded(member.cookie(), id, "member.pdf", PDF);
        mockMvc.perform(get("/api/v1/tasks/" + id + "/attachments/" + file + "/file").cookie(viewer.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(delete("/api/v1/tasks/" + id + "/attachments/" + file)).cookie(viewer.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/v1/tasks/" + id + "/attachments/" + file)).cookie(member.cookie())).andExpect(status().isOk()); // the uploader
        mockMvc.perform(get("/api/v1/tasks/" + id + "/attachments/" + file + "/file").cookie(hr.cookie())).andExpect(status().isNotFound());
        // soft delete: the row and the stored file stay
        String stored = jdbc.queryForObject("SELECT file_path FROM task_attachments WHERE id = ?::uuid AND deleted_at IS NOT NULL", String.class, file);
        assertThat(Files.exists(Path.of("target/test-storage").toAbsolutePath().normalize().resolve(stored))).isTrue();
        assertThat(getData(hr.cookie(), "/api/v1/tasks/" + id + "/activity").get("rows").get(0).get("kind").asText()).isEqualTo("ATTACHMENT_REMOVED");
        // the project owner may remove anyone's file
        String other = uploaded(member.cookie(), id, "second.pdf", PDF);
        mockMvc.perform(write(delete("/api/v1/tasks/" + id + "/attachments/" + other)).cookie(hr.cookie())).andExpect(status().isOk());
    }

    @Test
    void atMostTwentyFilesAndNoPathTraversal() throws Exception {
        Person hr = person(hrRole, "hr");
        String id = task(hr.cookie(), "\"title\":\"Personal files\"").get("id").asText();
        String first = null;
        for (int i = 0; i < 20; i++) {
            String file = uploaded(hr.cookie(), id, "file" + i + ".pdf", PDF);
            if (first == null) first = file;
        }
        upload(hr.cookie(), id, "file21.pdf", PDF).andExpect(status().isUnprocessableEntity());
        jdbc.update("UPDATE task_attachments SET file_path = '../../../pom.xml' WHERE id = ?::uuid", first);
        mockMvc.perform(get("/api/v1/tasks/" + id + "/attachments/" + first + "/file").cookie(hr.cookie())).andExpect(status().isNotFound());
    }
}
