package com.example.aics.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPermissionCatalogTest {

    @Test
    void sensitiveCapabilitiesAreNotDelegable() {
        assertThat(AdminPermissionCatalog.codes())
                .doesNotContain("config.secrets", "admin.accounts")
                .contains("report.view", "quality.manage", "knowledge.manage", "agents.manage");
    }

    @Test
    void permissionCodesAreUnique() {
        assertThat(AdminPermissionCatalog.codes()).doesNotHaveDuplicates();
    }
}
