package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.state.SchemaRegistry;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;

/**
 * What content compilation validates against: the schemas being loaded, the scopes this deployment
 * supports, and the registered condition and action types.
 *
 * @param packageId the content package being compiled, for messages and for the v1 bridge types
 *         that resolve relative ids against it
 */
public record CompileContext(
        SchemaRegistry schemas,
        ScopeSupport scopes,
        ConditionTypeRegistry conditions,
        ActionTypeRegistry actions,
        String packageId) {

    public CompileContext inPackage(String packageId) {
        return new CompileContext(schemas, scopes, conditions, actions, packageId);
    }
}
