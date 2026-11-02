package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.engine.PeopleRules.Actor;
import com.jurislpo.jurisone.engine.PeopleRules.Target;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.AdditionalRolesRepository;
import com.jurislpo.jurisone.repository.AdditionalRolesRepository.RoleRow;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * More than one role per person. The main role ({@code employees.role_id}) keeps deciding the owner / HR rules, the assignable-roles list
 * and the sidebar; an additional role only ADDS its permissions. Who may change them is who may change a role at all (the admin and HR
 * with {@code people.add}), for people they may edit, and only with roles they could hand out as a main role (plus the JurisTrack manager role, which both may hand out). The owner role is never an
 * additional role, and a role that exists only with a module (for example the JurisTrack manager) is offered only to businesses that have it.
 */
@Service
public class AdditionalRolesService {

    static final int MAXIMUM_ADDITIONAL_ROLES = 5;

    public record RoleView(String id, String key, String name) {}

    public record AdditionalRoles(RoleView mainRole, List<RoleView> additionalRoles, List<RoleView> offeredRoles, boolean canChange) {}

    private final AdditionalRolesRepository repo;
    private final ActorService actors;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final JurisTrackRosterTrigger rosterTrigger;

    public AdditionalRolesService(AdditionalRolesRepository repo, ActorService actors, SubscriptionService subscriptions, AuditService audit, JurisTrackRosterTrigger rosterTrigger) {
        this.rosterTrigger = rosterTrigger;
        this.repo = repo;
        this.actors = actors;
        this.subscriptions = subscriptions;
        this.audit = audit;
    }

    /** The main and additional roles of an employee of this business, and (for the admin and HR) the roles that could be added. */
    @Transactional(readOnly = true)
    public AdditionalRoles get(CurrentUser user, String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        RoleRow main = repo.findMainRole(employeeId).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Person not found."));
        Actor actor = actors.actor(user);
        Target target = actors.target(employeeId);
        boolean canChange = target != null && mayChange(actor, target);
        List<RoleView> offered = canChange ? candidates(user, actor, main) : List.of();
        return new AdditionalRoles(view(main), repo.findAdditionalRoles(employeeId).stream().map(AdditionalRolesService::view).toList(), offered, canChange);
    }

    /** Replaces the additional roles of an employee with exactly {@code roleIdsRaw} (an empty list removes them all). */
    @Transactional
    public AdditionalRoles replace(CurrentUser user, String employeeIdRaw, List<String> roleIdsRaw, String ipAddress) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        RoleRow main = repo.findMainRole(employeeId).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Person not found."));
        Actor actor = actors.actor(user);
        Target target = actors.target(employeeId);
        if (target == null || !mayChange(actor, target)) throw new UserException(HttpStatus.FORBIDDEN, "You can't change the roles of this person.");

        if (roleIdsRaw == null) throw new UserException(HttpStatus.BAD_REQUEST, "Send the list of role ids.");
        if (roleIdsRaw.size() > MAXIMUM_ADDITIONAL_ROLES) throw new UserException(HttpStatus.BAD_REQUEST, "A person can have at most " + MAXIMUM_ADDITIONAL_ROLES + " additional roles.");
        Set<UUID> roleIds = new LinkedHashSet<>();
        for (String raw : roleIdsRaw) roleIds.add(Check.uuid(raw, "Pick roles from the list."));
        List<RoleRow> roles = repo.findRoles(new ArrayList<>(roleIds));
        if (roles.size() != roleIds.size()) throw new UserException("One of those roles doesn't exist.");
        Set<UUID> allowedIds = candidates(user, actor, main).stream().map(role -> UUID.fromString(role.id())).collect(Collectors.toSet());
        for (RoleRow role : roles) {
            if (role.id().equals(main.id())) throw new UserException("The " + role.name() + " role is already their main role.");
            if (PeopleRules.isAdminRole(role.key())) throw new UserException("The " + role.name() + " role can't be given as an additional role.");
            if (!allowedIds.contains(role.id())) throw new UserException("You can't give people the " + role.name() + " role.");
        }

        List<String> before = repo.findAdditionalRoles(employeeId).stream().map(role -> role.id().toString()).toList();
        repo.deleteAll(employeeId);
        for (RoleRow role : roles) repo.insert(employeeId, role.id(), user.id());
        Map<String, Object> beforeLog = new LinkedHashMap<>();
        beforeLog.put("additionalRoleIds", before);
        Map<String, Object> afterLog = new LinkedHashMap<>();
        afterLog.put("additionalRoleIds", roles.stream().map(role -> role.id().toString()).toList());
        audit.log(user.id(), "employee.additional_roles", "employee", employeeId.toString(), beforeLog, afterLog, ipAddress);
        rosterTrigger.rosterChanged(user.businessId()); // the JurisTrack manager role changes the person's role in Juris Track
        return get(user, employeeIdRaw);
    }

    /** The admin and HR (with {@code people.add}) for people they may edit; nobody for themselves, a past employee or the super admin. */
    private static boolean mayChange(Actor actor, Target target) {
        return PeopleRules.canManageStatusAndRole(actor) && PeopleRules.canEditEmployee(actor, target) && target.status() != EmployeeStatus.EXITED;
    }

    /** The roles this actor may add: assignable by them, not the owner role, not the main role, and available to this business. */
    private List<RoleView> candidates(CurrentUser user, Actor actor, RoleRow main) {
        List<RoleRow> all = repo.findAllRoles();
        Map<UUID, List<String>> permissions = repo.findPermissionKeys(all.stream().map(RoleRow::id).toList());
        Set<String> modules = subscriptions.activeModules(user.businessId());
        List<RoleView> out = new ArrayList<>();
        for (RoleRow role : all) {
            if (role.id().equals(main.id()) || PeopleRules.isAdminRole(role.key())) continue;
            List<String> roleKeys = permissions.getOrDefault(role.id(), List.of());
            // the JurisTrack manager role may be handed out by anybody who may change roles at all (the owner and HR), not only by those with it in their assignable list
            boolean canHandOut = PeopleRules.isAdmin(actor) || actor.assignable().contains(role.id().toString()) || roleKeys.contains(Permissions.JURISTRACK_MANAGER);
            if (canHandOut && isOffered(roleKeys, modules)) out.add(view(role));
        }
        return out;
    }

    /** A role is offered only when every module its permissions belong to is active for the business. */
    public static boolean isOffered(List<String> permissionKeys, Set<String> activeModules) {
        for (String key : permissionKeys) {
            String module = Permissions.MODULE_OF.get(key);
            if (module != null && !activeModules.contains(module)) return false;
        }
        return true;
    }

    private static RoleView view(RoleRow role) {
        return new RoleView(role.id().toString(), role.key(), role.name());
    }
}
