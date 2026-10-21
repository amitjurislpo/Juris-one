package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.RoleTemplateRepository;
import com.jurislpo.jurisone.repository.RoleTemplateRepository.Template;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The super admin's catalogue of role templates. A business gets copies of the templates picked for it when it is onboarded
 * (the business owner can't create or edit roles), so what each kind of business can do is decided here.
 */
@Service
public class RoleTemplateService {

    public record TemplateInput(String name, List<String> permissions, List<String> assignable, Boolean active) {}

    public record TemplateView(String key, String name, boolean locked, boolean mandatory, boolean active, List<String> permissions, List<String> assignable,
                               int businesses) {}

    private final RoleTemplateRepository repo;
    private final AuditService audit;

    public RoleTemplateService(RoleTemplateRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** The templates with their usage, and the permission catalogue (groups with labels) to render the checkboxes. */
    public Map<String, Object> list() {
        List<TemplateView> templates = repo.findAll().stream().map(t -> new TemplateView(t.key(), t.name(), t.locked(), t.mandatory(), t.active(),
                t.permissions(), t.assignable(), repo.businessesUsing(t.key()))).toList();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Permissions.Group group : Permissions.GROUPS) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", group.name());
            view.put("permissions", group.keys().stream().map(k -> Map.of("key", k, "label", Permissions.LABELS.get(k))).toList());
            groups.add(view);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", templates);
        out.put("groups", groups);
        return out;
    }

    static String slug(String name) {
        String text = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
        if (text.isEmpty() || !Character.isLetter(text.charAt(0))) text = "r_" + text;
        return text.substring(0, Math.min(40, text.length()));
    }

    private List<String> permissions(String name, String key, List<String> in) {
        List<String> perms = new ArrayList<>(new LinkedHashSet<>(in == null ? List.of() : in));
        for (String p : perms) if (p == null || !Permissions.isPermission(p)) throw new UserException("Unknown permission: " + p + ".");
        // HR roles never see salary or tax figures (REQUIREMENTS §3.1)
        boolean hrLike = key.startsWith("hr") || name.toLowerCase(Locale.ROOT).startsWith("hr ");
        if (hrLike && perms.stream().anyMatch(Permissions.SALARY::contains)) throw new UserException("HR roles can't hold salary, payroll or tax permissions.");
        return perms;
    }

    private List<String> assignable(String key, List<String> in) {
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(in == null ? List.of() : in));
        for (String k : keys) {
            if (BusinessSetupService.OWNER.equals(k)) throw new UserException("The Owner role can't be handed out.");
            if (k.equals(key)) throw new UserException("A role can't be set to add people of its own kind.");
            if (repo.find(k).isEmpty()) throw new UserException("Unknown role template: " + k + ".");
        }
        return keys;
    }

    private String name(String raw, String exceptKey) {
        String name = Check.trimmed(raw, 2, "Role name is required.", 60, "Keep the name under 60 characters.");
        if (repo.nameTaken(name, exceptKey)) throw new UserException(HttpStatus.CONFLICT, "A role template with that name already exists.");
        return name;
    }

    public Map<String, String> create(CurrentUser user, TemplateInput input, String ip) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Role details are missing.");
        String name = name(input.name(), null);
        String key = slug(name);
        if (!repo.find(key).isEmpty() || "super_admin".equals(key)) throw new UserException(HttpStatus.CONFLICT, "A role template with that name already exists.");
        List<String> perms = permissions(name, key, input.permissions());
        List<String> assign = assignable(key, input.assignable());
        repo.insert(key, name, perms, assign, repo.nextSortOrder());
        audit.log(user.id(), "role_template.create", "role_template", key, null, Map.of("name", name, "permissions", perms, "assignable", assign), ip);
        return Map.of("key", key);
    }

    public void update(CurrentUser user, String key, TemplateInput input, String ip) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Role details are missing.");
        Template template = repo.find(key).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Role template not found."));
        if (template.locked()) throw new UserException(template.name() + " is fixed and can't be changed.");
        String name = name(input.name(), key);
        boolean active = input.active() == null ? template.active() : input.active();
        if (template.mandatory() && !active) throw new UserException(template.name() + " is part of every business and can't be switched off.");
        List<String> perms = permissions(name, key, input.permissions());
        List<String> assign = assignable(key, input.assignable());
        repo.update(key, name, perms, assign, active);
        audit.log(user.id(), "role_template.update", "role_template", key,
                Map.of("name", template.name(), "permissions", template.permissions(), "assignable", template.assignable(), "active", template.active()),
                Map.of("name", name, "permissions", perms, "assignable", assign, "active", active), ip);
    }

    public void delete(CurrentUser user, String key, String ip) {
        Template template = repo.find(key).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Role template not found."));
        if (template.locked() || template.mandatory()) throw new UserException(template.name() + " is part of every business and can't be deleted.");
        int count = repo.businessesUsing(key);
        if (count > 0) throw new UserException(template.name() + " is used by " + count + " business" + (count == 1 ? "" : "es") + ". Remove it from them first, or switch it off.");
        repo.delete(key);
        audit.log(user.id(), "role_template.delete", "role_template", key, Map.of("name", template.name()), null, ip);
    }

    /** Active templates for a new business: every mandatory one plus the chosen optional keys (unknown or inactive keys are refused). */
    List<Template> forNewBusiness(List<String> chosen) {
        List<Template> all = repo.findAll();
        Set<String> want = new LinkedHashSet<>(chosen == null ? List.of() : chosen);
        for (String k : want) {
            if (all.stream().noneMatch(t -> t.key().equals(k) && t.active())) throw new UserException("Unknown or switched-off role: " + k + ".");
        }
        return all.stream().filter(t -> t.active() && (t.mandatory() || want.contains(t.key()))).toList();
    }

    /** Every active template (the demo seed and the platform company). */
    List<Template> allActive() {
        return repo.findAll().stream().filter(Template::active).toList();
    }

    /** The template (any state) or null. */
    Template any(String key) {
        return repo.find(key).stream().findFirst().orElse(null);
    }
}
