import {computed, effect, inject, Signal} from "@angular/core";
import {Store} from "@ngrx/store";
import {TenantScope} from "../../models/tenant.model";
import * as AiModelActions from "../../store/ai-model/ai-model.actions";
import * as AiModelSelectors from "../../store/ai-model/ai-model.selectors";
import * as TenantAiActions from "../../store/tenant-ai/tenant-ai.actions";
import * as TenantAiSelectors from "../../store/tenant-ai/tenant-ai.selectors";
import * as TenantModelActions from "../../store/tenant-model/tenant-model.actions";
import * as TenantModelSelectors from "../../store/tenant-model/tenant-model.selectors";
import {webSearchAmong, webSearchAssigned} from "../../utils/web-search.util";

/**
 * Whether the tenant of the scope has the web search (utils/web-search.util.ts) - for a panel's switches, called in
 * its injection context. Loads what it needs: a tenant admin its tenant's models, the admin the catalog and the
 * tenant's assigned models.
 */
export function tenantWebSearch(scope: Signal<TenantScope>): Signal<boolean> {
    const store = inject(Store);
    const ownModels = store.selectSignal(TenantAiSelectors.selectTenantModels);
    const catalog = store.selectSignal(AiModelSelectors.selectAiModels);
    const assigned = store.selectSignal(TenantModelSelectors.selectTenantModelState);
    effect(() => {
        const tenant = scope();
        if (tenant === "self") {
            store.dispatch(TenantAiActions.loadModels());
        } else {
            store.dispatch(AiModelActions.loadModels());
            store.dispatch(TenantModelActions.loadTenantModels({tenantId: tenant}));
        }
    });
    return computed(() => {
        const tenant = scope();
        if (tenant === "self") {
            return webSearchAmong(ownModels());
        }
        const state = assigned();
        return webSearchAssigned(catalog(), state.tenantId === tenant ? state.modelIds : null);
    });
}
