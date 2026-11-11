import {inject, Injectable} from "@angular/core";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TranslocoService} from "@jsverse/transloco";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {filter, tap} from "rxjs/operators";

import * as VersionActions from "../version/version.actions";
import * as TenantActions from "../tenant/tenant.actions";
import * as TenantUserActions from "../tenant-user/tenant-user.actions";
import * as TenantModelActions from "../tenant-model/tenant-model.actions";
import * as AccessRequestActions from "../access-request/access-request.actions";
import * as BrandingActions from "../branding/branding.actions";
import * as AccountActions from "../account/account.actions";
import * as AssistantActions from "../assistant/assistant.actions";
import * as McpKeyActions from "../mcp-key/mcp-key.actions";
import * as McpConnectionActions from "../mcp-connection/mcp-connection.actions";
import * as FileStoreActions from "../file-store/file-store.actions";
import * as KnowledgeSourceActions from "../knowledge-source/knowledge-source.actions";
import * as TaskActions from "../task/task.actions";
import * as AiModelActions from "../ai-model/ai-model.actions";
import * as ProviderKeyActions from "../provider-key/provider-key.actions";
import * as UsageActions from "../usage/usage.actions";
import * as ApplicationLogActions from "../application-log/application-log.actions";
import * as TenantAiActions from "../tenant-ai/tenant-ai.actions";
import * as DocumentActions from "../document/document.actions";
import * as KnowledgeAreaActions from "../knowledge-area/knowledge-area.actions";
import * as DocSiteActions from "../doc-site/doc-site.actions";
import * as UserGroupActions from "../user-group/user-group.actions";
import * as ChatActions from "../chat/chat.actions";

/** A repeated failure (e.g. a periodic reload against a dead backend) is shown at most this often. */
const REPEAT_SUPPRESSION_MS = 15000;

/**
 * Surfaces failed *read* requests globally. Without this, a `load*Failure` only flips the loading
 * flag off and writes `error` into state that nothing renders - the user reads a stale list off a
 * screen that looks perfectly healthy.
 *
 * Register every new feature's `load*Failure` action in the ofType() below. Mutations
 * (create/update/delete) keep their per-component snackbars instead: those are tied to a button the
 * user just pressed and carry action-specific wording. Backend-health failures are excluded - they
 * are expected while the backend is down and already shown as the offline banner in the dashboard -
 * and so is the installation's look, loaded at start: without it the build's own look is shown.
 */
@Injectable()
export class LoadFailureEffects {
    private actions$ = inject(Actions);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    /**
     * Last time a snackbar was shown per failing action type. Keyed by the type alone, not by
     * type + message: server messages are not guaranteed to be stable, and a varying one would both
     * defeat the suppression and make this map grow without bound.
     */
    private readonly lastShownAt = new Map<string, number>();

    loadFailure$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(
                    VersionActions.loadVersionFailure,
                    TenantActions.loadTenantsFailure,
                    TenantActions.loadApiKeysFailure,
                    TenantActions.checkSandboxFailure,
                    TenantUserActions.loadUsersFailure,
                    TenantUserActions.loadOtherTenantUsersFailure,
                    TenantModelActions.loadTenantModelsFailure,
                    AccessRequestActions.loadAccessRequestsFailure,
                    BrandingActions.loadTenantBrandingFailure,
                    BrandingActions.loadAdminBrandingFailure,
                    BrandingActions.loadOwnLoginKeyFailure,
                    AccountActions.loadAccountFailure,
                    AssistantActions.loadAssistantFailure,
                    McpKeyActions.loadMcpKeysFailure,
                    McpConnectionActions.loadConnectionsFailure,
                    McpConnectionActions.loadAccessFailure,
                    FileStoreActions.loadStoresFailure,
                    KnowledgeSourceActions.loadSourcesFailure,
                    KnowledgeSourceActions.loadFilesFailure,
                    TaskActions.loadTasksFailure,
                    TaskActions.loadTaskFailure,
                    TaskActions.loadRunsFailure,
                    TaskActions.loadRunFailure,
                    AiModelActions.loadModelsFailure,
                    ProviderKeyActions.loadKeysFailure,
                    UsageActions.loadUsageFailure,
                    ApplicationLogActions.loadEntriesFailure,
                    TenantAiActions.loadModelsFailure,
                    TenantAiActions.loadSettingsFailure,
                    DocumentActions.loadDocumentsFailure,
                    KnowledgeAreaActions.loadAreasFailure,
                    DocSiteActions.loadSitesFailure,
                    DocSiteActions.loadHandbookFailure,
                    DocSiteActions.openSiteFailure,
                    UserGroupActions.loadGroupsFailure,
                    ChatActions.loadConversationsFailure,
                    ChatActions.loadTemplatesFailure,
                    ChatActions.loadSoftwarePromptsFailure,
                    ChatActions.loadSoftwareFailure,
                    ChatActions.loadMessagesFailure,
                    ChatActions.loadFilesFailure,
                ),
                filter(({type}) => {
                    const now = Date.now();
                    const last = this.lastShownAt.get(type) ?? 0;
                    if (now - last < REPEAT_SUPPRESSION_MS) {
                        return false;
                    }
                    this.lastShownAt.set(type, now);
                    return true;
                }),
                tap(({error}) => {
                    this.snackBar.open(
                        this.transloco.translate("errors.loadFailed", {error}),
                        this.transloco.translate("common.ok"),
                        {
                            duration: 10000,
                            panelClass: "error-snackbar",
                        },
                    );
                }),
            ),
        {dispatch: false},
    );
}
