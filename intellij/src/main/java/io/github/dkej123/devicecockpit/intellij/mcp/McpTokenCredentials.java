package io.github.dkej123.devicecockpit.intellij.mcp;

import com.intellij.credentialStore.CredentialAttributes;

/**
 * Builds the MCP token's {@link CredentialAttributes} from Java on purpose: {@code new
 * CredentialAttributes(String)} binds to the one-argument constructor that exists on every
 * supported platform (a {@code @JvmOverloads} overload on 242, an explicit one from 2026.x), while
 * a Kotlin call compiled against 242 binds to the defaults-synthetic constructor that 2026.x
 * deprecates — which the Marketplace verifier reports.
 */
final class McpTokenCredentials {
    static final CredentialAttributes TOKEN = new CredentialAttributes("ADB Toolbox MCP token");

    private McpTokenCredentials() {
    }
}
