package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.jurisone.engine.LetterTemplateEngine.MissingFieldsException;
import com.jurislpo.jurisone.engine.LetterTemplateEngine.Mode;
import com.jurislpo.jurisone.exception.UserException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HR letter template merge engine")
class LetterTemplateEngineTest {

    static Map<String, String> values() {
        Map<String, String> fieldValues = new HashMap<>();
        fieldValues.put("employee.name", "Asha Rao");
        fieldValues.put("employee.code", "JC-007");
        fieldValues.put("designation", "Analyst");
        fieldValues.put("business.name", "Juris Consultants");
        return fieldValues;
    }

    @Test
    @DisplayName("fills known fields, with or without spaces inside the braces")
    void fills() {
        assertThat(LetterTemplateEngine.render("Dear {{employee.name}}, ID {{ employee.code }} - {{designation}}", values(), Mode.TEXT))
                .isEqualTo("Dear Asha Rao, ID JC-007 - Analyst");
    }

    @Test
    @DisplayName("rejects an unknown field when validating and when rendering, naming it")
    void unknownField() {
        assertThatThrownBy(() -> LetterTemplateEngine.validate("Hello {{employee.name}} {{salary}} {{foo.bar}}"))
                .isInstanceOf(UserException.class).hasMessageContaining("{{salary}}").hasMessageContaining("{{foo.bar}}").hasMessageContaining("Unknown merge fields");
        assertThatThrownBy(() -> LetterTemplateEngine.render("Hi {{nope}}", values(), Mode.TEXT)).isInstanceOf(UserException.class).hasMessageContaining("{{nope}}");
        // a value for an unknown field doesn't make it known
        Map<String, String> fieldValues = values();
        fieldValues.put("nope", "x");
        assertThatThrownBy(() -> LetterTemplateEngine.render("Hi {{nope}}", fieldValues, Mode.TEXT)).isInstanceOf(UserException.class);
    }

    @Test
    @DisplayName("rejects braces without their pair")
    void strayBraces() {
        assertThatThrownBy(() -> LetterTemplateEngine.validate("Dear {{employee.name")).isInstanceOf(UserException.class).hasMessageContaining("without its pair");
        assertThatThrownBy(() -> LetterTemplateEngine.validate("Dear employee.name}}")).isInstanceOf(UserException.class);
    }

    @Test
    @DisplayName("HTML mode escapes the values and the template text; text mode keeps them as typed")
    void escaping() {
        Map<String, String> fieldValues = values();
        fieldValues.put("employee.name", "<script>alert('x')</script> & \"Co\"");
        String html = LetterTemplateEngine.render("<b>Dear</b> {{employee.name}}", fieldValues, Mode.HTML);
        assertThat(html).isEqualTo("&lt;b&gt;Dear&lt;/b&gt; &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;Co&quot;");
        assertThat(html).doesNotContain("<");
        assertThat(LetterTemplateEngine.render("Dear {{employee.name}}", fieldValues, Mode.TEXT)).isEqualTo("Dear <script>alert('x')</script> & \"Co\"");
    }

    @Test
    @DisplayName("a known field without a value is an error naming every missing field")
    void missingValue() {
        Map<String, String> fieldValues = values();
        fieldValues.put("department", null);
        fieldValues.put("lastWorkingDate", "   ");
        assertThatThrownBy(() -> LetterTemplateEngine.render("{{employee.name}} {{department}} {{lastWorkingDate}} {{joiningDate}}", fieldValues, Mode.TEXT))
                .isInstanceOf(MissingFieldsException.class)
                .satisfies(e -> assertThat(((MissingFieldsException) e).fields()).containsExactly("department", "lastWorkingDate", "joiningDate"))
                .hasMessageContaining("{{department}}").hasMessageContaining("{{joiningDate}}");
    }

    @Test
    @DisplayName("merges once: a value that looks like a field is not merged again; new lines in values become spaces")
    void singlePass() {
        Map<String, String> fieldValues = values();
        fieldValues.put("designation", "{{employee.code}}");
        fieldValues.put("business.name", "Line one\nLine two");
        assertThat(LetterTemplateEngine.render("{{designation}} / {{business.name}}", fieldValues, Mode.TEXT)).isEqualTo("{{employee.code}} / Line one Line two");
    }

    @Test
    @DisplayName("knows which fields state a salary")
    void salaryFields() {
        assertThat(LetterTemplateEngine.usesSalary("Your CTC is {{ctc}}")).isTrue();
        assertThat(LetterTemplateEngine.usesSalary("Subject", "Rupees {{ ctcInWords }}")).isTrue();
        assertThat(LetterTemplateEngine.usesSalary("Dear {{employee.name}}")).isFalse();
        assertThat(LetterTemplateEngine.fieldsIn("{{a}} {{ b }} {{a}}")).containsExactly("a", "b");
    }
}
