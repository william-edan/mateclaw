package vip.mate.workspace.core;

import org.junit.jupiter.api.Test;
import vip.mate.plugin.controller.PluginController;
import vip.mate.system.controller.SystemSettingController;
import vip.mate.system.featureflag.FeatureFlagController;
import vip.mate.tool.guard.controller.SecurityController;
import vip.mate.tool.mcp.controller.McpServerController;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * System-wide configuration may only be mutated by a GLOBAL admin
 * ({@code mate_user.role='admin'}), never by a mere workspace admin/owner.
 *
 * <p>Registration makes every new user the {@code owner} of their own
 * workspace while keeping the global role {@code "user"}. So
 * {@code @RequireWorkspaceRole("admin")} on a global-config write lets ANY
 * registered user flip the system default model, disable the tool-safety
 * guard, toggle feature flags, or enable plugins. These reflection assertions
 * pin the requirement and guard against silently loosening it back.
 */
class GlobalConfigRequiresGlobalAdminTest {

    @Test
    void systemSettingWritesRequireGlobalAdmin() {
        assertGlobalAdmin(SystemSettingController.class, "saveSettings");
        assertGlobalAdmin(SystemSettingController.class, "saveSidecar");
    }

    @Test
    void featureFlagWritesRequireGlobalAdmin() {
        assertGlobalAdmin(FeatureFlagController.class, "update");
    }

    @Test
    void pluginWritesRequireGlobalAdmin() {
        assertGlobalAdmin(PluginController.class, "disable");
        assertGlobalAdmin(PluginController.class, "enable");
        assertGlobalAdmin(PluginController.class, "updateConfig");
    }

    @Test
    void toolGuardWritesRequireGlobalAdmin() {
        for (String method : List.of(
                "updateGuardConfig",
                "updateFileGuardConfig",
                "createRule",
                "updateRule",
                "toggleRule",
                "deleteRule",
                "deleteRuleByPk",
                "importRules")) {
            assertGlobalAdmin(SecurityController.class, method);
        }
    }

    @Test
    void mcpServerEndpointsRequireGlobalAdmin() {
        // mate_mcp_server is a GLOBAL table (no workspace_id). A workspace admin
        // (every registered user is admin/owner of their own workspace) must not
        // read or mutate global external-integration config / credentials.
        for (String method : List.of(
                "list", "get", "create", "update", "delete", "toggle",
                "setDisclosureTier", "test", "listTools", "refresh")) {
            assertGlobalAdmin(McpServerController.class, method);
        }
    }

    private void assertGlobalAdmin(Class<?> controller, String methodName) {
        Method method = findUniqueMethod(controller, methodName);
        assertNotNull(method.getAnnotation(RequireGlobalAdmin.class),
                controller.getSimpleName() + "." + methodName
                        + " must be @RequireGlobalAdmin — it mutates global system config");
        assertNull(method.getAnnotation(RequireWorkspaceRole.class),
                controller.getSimpleName() + "." + methodName
                        + " must NOT be @RequireWorkspaceRole — any workspace owner could change global config");
    }

    private Method findUniqueMethod(Class<?> controller, String methodName) {
        List<Method> matches = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
        assertEquals(1, matches.size(),
                "expected exactly one method named " + methodName + " on " + controller.getSimpleName());
        return matches.get(0);
    }
}
