package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.security.HostileInputTestBase;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Shifts and the shift roster through the API: permissions, tenants, overlaps, hostile input, and the engine using a night shift that crosses midnight. */
class ShiftRosterApiTest extends HostileInputTestBase {

    UUID defaultShift;

    @BeforeEach
    void defaults() {
        defaultShift = jdbc.query("SELECT id FROM shifts WHERE code = 'IN_DAY' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (defaultShift == null) {
            defaultShift = UUID.randomUUID();
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_DAY', 'India Day', '09:30', '18:30', 15, 'IN')", defaultShift);
        }
        for (UUID business : List.of(alpha.business(), beta.business())) {
            jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{\"trackingStartDate\":\"2026-04-01\"}', now()) ON CONFLICT (business_id, key) DO NOTHING",
                    business);
        }
        jdbc.update("UPDATE employees SET shift_id = ? WHERE id IN (?, ?, ?)", defaultShift, alphaWide.id(), alphaPlain.id(), alphaPlainOther.id());
    }

    JsonNode data(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    String createShift(Person person, String name, String start, String end) throws Exception {
        return data(send(person, new Call("POST", "/api/v1/attendance/shifts", "{\"name\":" + quote(name) + ",\"startLocal\":\"" + start + "\",\"endLocal\":\"" + end + "\"}")), 201).get("id").asText();
    }

    static String assignment(List<UUID> employeeIds, String shiftId, String from, String to) {
        return "{\"employeeIds\":" + employeeIds.stream().map(id -> "\"" + id + "\"").toList() + ",\"shiftId\":\"" + shiftId + "\",\"fromDate\":\"" + from + "\"" + (to == null ? "" : ",\"toDate\":\"" + to + "\"") + "}";
    }

    static String in(int days) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(days).toString();
    }

    @Test
    void onlyShiftManagersCreateAndChangeShiftsAndOnlyTheirOwn() throws Exception {
        String body = "{\"name\":\"Evening\",\"startLocal\":\"14:00\",\"endLocal\":\"23:00\"}";
        assertRefused(alphaPlain, new Call("POST", "/api/v1/attendance/shifts", body), 403);
        assertRefused(null, new Call("POST", "/api/v1/attendance/shifts", body), 401);
        String id = createShift(alphaWide, "Evening " + suffix(), "14:00", "23:00");
        assertThat(data(send(alphaPlain, new Call("GET", "/api/v1/attendance/shifts")), 200).toString()).contains(id);
        // the other business neither sees it nor can change it
        assertThat(data(send(betaWide, new Call("GET", "/api/v1/attendance/shifts")), 200).toString()).doesNotContain(id);
        assertRefused(betaWide, new Call("PATCH", "/api/v1/attendance/shifts/" + id, "{\"name\":\"Hijacked\"}"), 404);
        // the platform default is read-only
        assertRefused(alphaWide, new Call("PATCH", "/api/v1/attendance/shifts/" + defaultShift, "{\"name\":\"Hijacked\"}"), 422);
        assertThat(data(send(alphaWide, new Call("PATCH", "/api/v1/attendance/shifts/" + id, "{\"endLocal\":\"22:00\"}")), 200).get("endLocal").asText()).isEqualTo("22:00");
        // same start and end, a bad time, a duplicate name
        assertRefused(alphaWide, new Call("POST", "/api/v1/attendance/shifts", "{\"name\":\"Same\",\"startLocal\":\"10:00\",\"endLocal\":\"10:00\"}"), 422);
        assertRefused(alphaWide, new Call("POST", "/api/v1/attendance/shifts", "{\"name\":\"Late\",\"startLocal\":\"25:00\",\"endLocal\":\"10:00\"}"), 400);
        assertRefused(alphaWide, new Call("POST", "/api/v1/attendance/shifts", "{\"name\":\"india day\",\"startLocal\":\"10:00\",\"endLocal\":\"11:00\"}"), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('shift.create', 'shift.update') AND entity_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void aNightShiftThatCrossesMidnightIsRecorded() throws Exception {
        JsonNode shift = data(send(alphaWide, new Call("POST", "/api/v1/attendance/shifts", "{\"name\":\"Late night " + suffix() + "\",\"startLocal\":\"22:00\",\"endLocal\":\"06:00\"}")), 201);
        assertThat(shift.get("crossesMidnight").asBoolean()).isTrue();
        assertThat(shift.get("isCustom").asBoolean()).isTrue();
    }

    @Test
    void bulkAssignmentShowsOnTheRosterAndOverlapsAreRefusedAsAWhole() throws Exception {
        String shiftId = createShift(alphaWide, "Roster " + suffix(), "20:00", "05:00");
        JsonNode ids = data(send(alphaWide, new Call("POST", "/api/v1/attendance/shift-assignments", assignment(List.of(alphaPlain.id(), alphaPlainOther.id()), shiftId, in(2), in(6)))), 201);
        assertThat(ids.size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'shift_assignment.create' AND entity_id = ?", Integer.class, ids.get(0).asText())).isEqualTo(1);

        // an overlapping range for one of them (and a free person) refuses everything
        assertRefused(alphaWide, new Call("POST", "/api/v1/attendance/shift-assignments", assignment(List.of(alphaPlain.id(), alphaWide.id()), shiftId, in(5), in(9))), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_assignments WHERE employee_id = ?", Integer.class, alphaWide.id())).isZero();
        // open ended overlaps too, the day after is free
        assertRefused(alphaWide, new Call("POST", "/api/v1/attendance/shift-assignments", assignment(List.of(alphaPlain.id()), shiftId, in(1), null)), 409);
        data(send(alphaWide, new Call("POST", "/api/v1/attendance/shift-assignments", assignment(List.of(alphaPlain.id()), shiftId, in(7), null))), 201);

        // the whole roster shows it; an employee's own roster has only their row
        JsonNode roster = data(send(alphaWide, new Call("GET", "/api/v1/attendance/shift-roster?scope=all&from=" + in(0) + "&to=" + in(8))), 200);
        assertThat(roster.get("canManage").asBoolean()).isTrue();
        JsonNode row = null;
        for (JsonNode employee : roster.get("employees")) if (employee.get("id").asText().equals(alphaPlain.id().toString())) row = employee;
        assertThat(row).isNotNull();
        assertThat(row.get("days").get(0).get("shiftId").asText()).isEqualTo(defaultShift.toString()); // nothing today: the employee's own shift
        assertThat(row.get("days").get(0).get("assignmentId").isNull()).isTrue();
        assertThat(row.get("days").get(3).get("shiftId").asText()).isEqualTo(shiftId);
        assertThat(row.get("days").get(3).get("assignmentId").asText()).isNotBlank();
        JsonNode mine = data(send(alphaPlain, new Call("GET", "/api/v1/attendance/shift-roster?scope=mine&from=" + in(0) + "&to=" + in(8))), 200);
        assertThat(mine.get("canManage").asBoolean()).isFalse();
        assertThat(mine.get("employees").size()).isEqualTo(1);
        assertThat(mine.get("employees").get(0).get("id").asText()).isEqualTo(alphaPlain.id().toString());
        assertRefused(alphaPlain, new Call("GET", "/api/v1/attendance/shift-roster?scope=all&from=" + in(0) + "&to=" + in(8)), 403);
        assertRefused(alphaPlain, new Call("POST", "/api/v1/attendance/shift-assignments", assignment(List.of(alphaPlain.id()), shiftId, in(20), in(21))), 403);
        // another business sees none of it
        assertThat(send(betaWide, new Call("GET", "/api/v1/attendance/shift-roster?scope=all&from=" + in(0) + "&to=" + in(8))).getResponse().getContentAsString()).doesNotContain(alphaPlain.id().toString());

        // removing one frees the range, and writes an audit row
        String assignmentId = ids.get(0).asText();
        assertRefused(alphaPlain, new Call("DELETE", "/api/v1/attendance/shift-assignments/" + assignmentId), 403);
        assertRefused(betaWide, new Call("DELETE", "/api/v1/attendance/shift-assignments/" + assignmentId), 404);
        assertThat(send(alphaWide, new Call("DELETE", "/api/v1/attendance/shift-assignments/" + assignmentId)).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'shift_assignment.delete' AND entity_id = ?", Integer.class, assignmentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_assignments WHERE id = ?::uuid", Integer.class, assignmentId)).isZero();
    }

    @Test
    void assignmentsRefuseThePastWrongTenantsAndBadInput() throws Exception {
        String shiftId = createShift(alphaWide, "Checks " + suffix(), "08:00", "17:00");
        String assign = "/api/v1/attendance/shift-assignments";
        assertRefused(alphaWide, new Call("POST", assign, assignment(List.of(alphaPlain.id()), shiftId, in(-5), in(3))), 422); // history is not rewritten
        assertRefused(alphaWide, new Call("POST", assign, assignment(List.of(alphaPlain.id()), shiftId, in(3), in(2))), 400);
        assertRefused(alphaWide, new Call("POST", assign, assignment(List.of(alphaPlain.id()), shiftId, in(3), in(3 + 366 * 4))), 400);
        assertRefused(alphaWide, new Call("POST", assign, assignment(List.of(betaPlain.id()), shiftId, in(3), in(4))), 404); // another business's employee
        String betaShift = createShift(betaWide, "Beta only " + suffix(), "08:00", "17:00");
        assertRefused(alphaWide, new Call("POST", assign, assignment(List.of(alphaPlain.id()), betaShift, in(3), in(4))), 404); // another business's shift
        assertRefused(alphaWide, new Call("POST", assign, "{\"employeeIds\":[],\"shiftId\":\"" + shiftId + "\",\"fromDate\":\"" + in(3) + "\"}"), 400);
        assertRefused(alphaWide, new Call("POST", assign, "{\"shiftId\":\"" + shiftId + "\",\"fromDate\":\"" + in(3) + "\"}"), 400);
        assertRefused(alphaWide, new Call("POST", assign, "{\"employeeIds\":[\"x\"],\"shiftId\":\"" + shiftId + "\",\"fromDate\":\"" + in(3) + "\"}"), 400);
        assertRefused(alphaWide, new Call("POST", assign, "{\"employeeIds\":[\"" + alphaPlain.id() + "\"],\"shiftId\":\"" + shiftId + "\",\"fromDate\":\"2026-02-30\"}"), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_assignments WHERE employee_id IN (?, ?)", Integer.class, alphaPlain.id(), betaPlain.id())).isZero();
        // a finished assignment stays as history
        UUID finished = UUID.randomUUID();
        jdbc.update("INSERT INTO shift_assignments (id, business_id, employee_id, shift_id, from_date, to_date) VALUES (?, ?, ?, ?, ?, ?)", finished, alpha.business(), alphaPlain.id(), UUID.fromString(shiftId),
                Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(10)), Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(5)));
        assertRefused(alphaWide, new Call("DELETE", assign + "/" + finished), 422);
    }

    @Test
    void hostileInputIsRefusedWithoutAnErrorOrALeak() throws Exception {
        List<Call> calls = new ArrayList<>();
        for (String bad : BAD_IDS) {
            calls.add(new Call("PATCH", "/api/v1/attendance/shifts/" + enc(bad), "{\"name\":\"x\"}"));
            calls.add(new Call("DELETE", "/api/v1/attendance/shift-assignments/" + enc(bad)));
        }
        for (String text : HOSTILE_TEXT) {
            calls.add(new Call("POST", "/api/v1/attendance/shifts", "{\"name\":" + quote(text) + ",\"startLocal\":" + quote(text) + ",\"endLocal\":\"10:00\",\"region\":" + quote(text) + "}"));
            calls.add(new Call("POST", "/api/v1/attendance/shift-assignments", "{\"employeeIds\":[" + quote(text) + "],\"shiftId\":" + quote(text) + ",\"fromDate\":" + quote(text) + ",\"toDate\":" + quote(text) + "}"));
            calls.add(new Call("GET", "/api/v1/attendance/shift-roster?scope=" + enc(text) + "&from=" + enc(text) + "&to=" + enc(text)));
        }
        calls.add(new Call("GET", "/api/v1/attendance/shift-roster?scope=all&from=2026-01-01&to=2027-12-31")); // far more than 42 days
        calls.add(new Call("POST", "/api/v1/attendance/shifts", "{\"name\":\"" + "n".repeat(5000) + "\",\"startLocal\":\"10:00\",\"endLocal\":\"11:00\"}"));
        for (Call call : calls) {
            MvcResult result = assertSurvives(alphaWide, call);
            int status = result.getResponse().getStatus();
            if (!(status == 400 && result.getResponse().getContentAsString().isEmpty())) assertThat(status).as(call.toString()).isBetween(400, 499);
        }
        // a hostile name is stored as plain text and never executes anything
        String name = "'; DROP TABLE shifts; -- " + suffix();
        String id = createShift(alphaWide, name, "10:00", "11:00");
        assertThat(jdbc.queryForObject("SELECT name FROM shifts WHERE id = ?::uuid", String.class, id)).isEqualTo(name);
        assertDatabaseIntact();
    }

    @Test
    void theEngineUsesTheAssignedNightShiftThatCrossesMidnight() throws Exception {
        // the employee's own shift is the day shift; for Mon 14 Sep 2026 a 22:00 - 06:00 night shift is assigned
        String nightShift = createShift(alphaWide, "Night engine " + suffix(), "22:00", "06:00");
        jdbc.update("INSERT INTO shift_assignments (id, business_id, employee_id, shift_id, from_date, to_date) VALUES (?, ?, ?, ?, '2026-09-14', '2026-09-14')", UUID.randomUUID(), alpha.business(),
                alphaPlain.id(), UUID.fromString(nightShift));
        Instant in = AttendanceEngine.atLocal(LocalDate.parse("2026-09-14"), "22:30", "Asia/Kolkata"), out = AttendanceEngine.atLocal(LocalDate.parse("2026-09-15"), "07:00", "Asia/Kolkata");
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')", UUID.randomUUID(), alphaPlain.id(), Timestamp.from(in),
                UUID.randomUUID(), alphaPlain.id(), Timestamp.from(out));
        JsonNode page = data(send(alphaPlain, new Call("GET", "/api/v1/attendance/mine?month=2026-09&date=2026-09-14")), 200);
        JsonNode day = null;
        for (JsonNode dayNode : page.get("days")) if (dayNode.get("date").asText().equals("2026-09-14")) day = dayNode;
        assertThat(day.get("firstIn").asText()).isEqualTo("10:30 PM");
        assertThat(day.get("lastOut").asText()).isEqualTo("7:00 AM");
        assertThat(day.get("late").asBoolean()).as("22:30 is after the night shift's 22:00 start plus grace").isTrue();
        assertThat(day.get("status").asText()).isEqualTo("PRESENT");
    }
}
