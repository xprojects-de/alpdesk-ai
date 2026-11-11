import {
    ApplicationConfig,
    DOCUMENT,
    inject,
    isDevMode,
    LOCALE_ID,
    provideAppInitializer,
    provideBrowserGlobalErrorListeners,
} from "@angular/core";
import {firstValueFrom, of} from "rxjs";
import {take, timeout} from "rxjs/operators";
import {provideRouter, withComponentInputBinding} from "@angular/router";
import {provideHttpClient, withInterceptors, withXhr} from "@angular/common/http";
import {provideAnimationsAsync} from "@angular/platform-browser/animations/async";
import {MAT_FORM_FIELD_DEFAULT_OPTIONS} from "@angular/material/form-field";
import {MAT_TABS_CONFIG} from "@angular/material/tabs";
import {MatPaginatorIntl} from "@angular/material/paginator";
import {MatIconRegistry} from "@angular/material/icon";
import {registerLocaleData} from "@angular/common";
import localeDe from "@angular/common/locales/de";
import localeEnGb from "@angular/common/locales/en-GB";
import {provideTransloco, provideTranslocoTranspiler, TranslocoService} from "@jsverse/transloco";
import {provideTranslocoLocale} from "@jsverse/transloco-locale";

import {routes} from "./app.routes";
import {clearOnLogout} from "./store/logout.meta-reducer";
import {provideStore, Store} from "@ngrx/store";
import {Actions, ofType, provideEffects} from "@ngrx/effects";
import {provideStoreDevtools} from "@ngrx/store-devtools";

import {authReducer} from "./store/auth/auth.reducer";
import {versionReducer} from "./store/version/version.reducer";
import {backendHealthReducer} from "./store/backend-health/backend-health.reducer";
import {tenantReducer} from "./store/tenant/tenant.reducer";
import {tenantUserReducer} from "./store/tenant-user/tenant-user.reducer";
import {assistantReducer} from "./store/assistant/assistant.reducer";
import {mcpKeyReducer} from "./store/mcp-key/mcp-key.reducer";
import {mcpConnectionReducer} from "./store/mcp-connection/mcp-connection.reducer";
import {fileStoreReducer} from "./store/file-store/file-store.reducer";
import {knowledgeSourceReducer} from "./store/knowledge-source/knowledge-source.reducer";
import {taskReducer} from "./store/task/task.reducer";
import {tenantModelReducer} from "./store/tenant-model/tenant-model.reducer";
import {accessRequestReducer} from "./store/access-request/access-request.reducer";
import {brandingReducer} from "./store/branding/branding.reducer";
import {accountReducer} from "./store/account/account.reducer";
import {aiModelReducer} from "./store/ai-model/ai-model.reducer";
import {providerKeyReducer} from "./store/provider-key/provider-key.reducer";
import {usageReducer} from "./store/usage/usage.reducer";
import {applicationLogReducer} from "./store/application-log/application-log.reducer";
import {tenantAiReducer} from "./store/tenant-ai/tenant-ai.reducer";
import {documentReducer} from "./store/document/document.reducer";
import {knowledgeAreaReducer} from "./store/knowledge-area/knowledge-area.reducer";
import {docSiteReducer} from "./store/doc-site/doc-site.reducer";
import {userGroupReducer} from "./store/user-group/user-group.reducer";
import {chatReducer} from "./store/chat/chat.reducer";
import {AuthEffects} from "./store/auth/auth.effects";
import {VersionEffects} from "./store/version/version.effects";
import {BackendHealthEffects} from "./store/backend-health/backend-health.effects";
import {TenantEffects} from "./store/tenant/tenant.effects";
import {TenantUserEffects} from "./store/tenant-user/tenant-user.effects";
import {AssistantEffects} from "./store/assistant/assistant.effects";
import {McpKeyEffects} from "./store/mcp-key/mcp-key.effects";
import {McpConnectionEffects} from "./store/mcp-connection/mcp-connection.effects";
import {FileStoreEffects} from "./store/file-store/file-store.effects";
import {KnowledgeSourceEffects} from "./store/knowledge-source/knowledge-source.effects";
import {TaskEffects} from "./store/task/task.effects";
import {TenantModelEffects} from "./store/tenant-model/tenant-model.effects";
import {AccessRequestEffects} from "./store/access-request/access-request.effects";
import {BrandingEffects} from "./store/branding/branding.effects";
import * as BrandingActions from "./store/branding/branding.actions";
import {AccountEffects} from "./store/account/account.effects";
import {AiModelEffects} from "./store/ai-model/ai-model.effects";
import {ProviderKeyEffects} from "./store/provider-key/provider-key.effects";
import {UsageEffects} from "./store/usage/usage.effects";
import {ApplicationLogEffects} from "./store/application-log/application-log.effects";
import {TenantAiEffects} from "./store/tenant-ai/tenant-ai.effects";
import {DocumentEffects} from "./store/document/document.effects";
import {KnowledgeAreaEffects} from "./store/knowledge-area/knowledge-area.effects";
import {DocSiteEffects} from "./store/doc-site/doc-site.effects";
import {UserGroupEffects} from "./store/user-group/user-group.effects";
import {ChatEffects} from "./store/chat/chat.effects";
import {LoadFailureEffects} from "./store/load-failure/load-failure.effects";
import {authInterceptor} from "./interceptors/auth.interceptor";
import {timeoutInterceptor} from "./interceptors/timeout.interceptor";
import {applyColorScheme, localStorageOf, readColorScheme} from "./utils/color-scheme.util";
import {LanguageEffects} from "./store/language/language.effects";
import {languageReducer} from "./store/language/language.reducer";
import * as LanguageActions from "./store/language/language.actions";
import {browserLanguage, DEFAULT_LANGUAGE, LANGUAGES, localeOf} from "./utils/language.util";
import {TranslationLoader} from "./i18n/i18n.loader";
import {TranslatedPaginatorIntl} from "./i18n/paginator-intl";
import {SinglePassTranspiler} from "./i18n/single-pass-transpiler";

// Locale data for what Angular formats itself; dates and numbers in templates go through transloco-locale's pipes,
// which follow the active language (docs/architecture/i18n.md).
registerLocaleData(localeDe);
registerLocaleData(localeEnGb);

/** The language before any login: the browser's. A tenant user's account follows after the login (LanguageEffects). */
function startLanguage(document: Document) {
    return browserLanguage(document.defaultView?.navigator.languages ?? []);
}

export const appConfig: ApplicationConfig = {
    providers: [
        provideBrowserGlobalErrorListeners(),
        // Route data such as a page's scope reaches the component as an input.
        provideRouter(routes, withComponentInputBinding()),
        provideHttpClient(withXhr(), withInterceptors([authInterceptor, timeoutInterceptor])),
        provideAnimationsAsync(),
        provideTransloco({
            config: {
                availableLangs: LANGUAGES.map(language => language.value),
                defaultLang: DEFAULT_LANGUAGE,
                // A missing key shows as the key - i18n.spec.ts keeps every language complete, so nothing falls back
                // silently to another language.
                missingHandler: {useFallbackTranslation: false, logMissingKey: isDevMode()},
                reRenderOnLangChange: true,
                prodMode: !isDevMode(),
            },
            loader: TranslationLoader,
        }),
        // Params are written in once - a typed name with "{{...}}" stays text (single-pass-transpiler.ts).
        provideTranslocoTranspiler(SinglePassTranspiler),
        provideTranslocoLocale({
            langToLocaleMapping: Object.fromEntries(LANGUAGES.map(language => [language.value, language.locale])),
        }),
        // For Angular's own formatting only - fixed at start; templates use transloco-locale's pipes.
        {provide: LOCALE_ID, useFactory: () => localeOf(startLanguage(inject(DOCUMENT)))},
        {provide: MatPaginatorIntl, useClass: TranslatedPaginatorIntl},
        // Hints take the room they need below a field (several lines are common) - the gap after it is styles.scss'.
        {provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: {subscriptSizing: "dynamic"}},
        // Tabs switch at once, without sliding.
        {provide: MAT_TABS_CONFIG, useValue: {animationDuration: "0ms"}},
        provideStore(
            {
                auth: authReducer,
                version: versionReducer,
                backendHealth: backendHealthReducer,
                tenant: tenantReducer,
                tenantUser: tenantUserReducer,
                assistant: assistantReducer,
                mcpKey: mcpKeyReducer,
                mcpConnection: mcpConnectionReducer,
                fileStore: fileStoreReducer,
                knowledgeSource: knowledgeSourceReducer,
                task: taskReducer,
                tenantModel: tenantModelReducer,
                accessRequest: accessRequestReducer,
                branding: brandingReducer,
                account: accountReducer,
                aiModel: aiModelReducer,
                providerKey: providerKeyReducer,
                usage: usageReducer,
                applicationLog: applicationLogReducer,
                tenantAi: tenantAiReducer,
                document: documentReducer,
                knowledgeArea: knowledgeAreaReducer,
                docSite: docSiteReducer,
                userGroup: userGroupReducer,
                chat: chatReducer,
                language: languageReducer,
            },
            {metaReducers: [clearOnLogout]},
        ),
        provideEffects([
            AuthEffects,
            VersionEffects,
            BackendHealthEffects,
            TenantEffects,
            TenantUserEffects,
            AssistantEffects,
            McpKeyEffects,
            McpConnectionEffects,
            FileStoreEffects,
            KnowledgeSourceEffects,
            TaskEffects,
            TenantModelEffects,
            AccessRequestEffects,
            BrandingEffects,
            AccountEffects,
            AiModelEffects,
            ProviderKeyEffects,
            UsageEffects,
            ApplicationLogEffects,
            TenantAiEffects,
            DocumentEffects,
            KnowledgeAreaEffects,
            DocSiteEffects,
            UserGroupEffects,
            ChatEffects,
            LoadFailureEffects,
            LanguageEffects,
        ]),
        // Light or dark before anything is shown, as chosen in this browser; icons outlined (the active menu entry's filled).
        provideAppInitializer(() => {
            const document = inject(DOCUMENT);
            applyColorScheme(document.documentElement, readColorScheme(localStorageOf(document.defaultView)));
            inject(MatIconRegistry).setDefaultFontSetClass("material-icons-outlined");
        }),
        // The language's texts before anything is shown - no flash of keys. The account's language follows after the
        // login (LanguageEffects).
        provideAppInitializer(() => {
            const language = startLanguage(inject(DOCUMENT));
            inject(Store).dispatch(LanguageActions.useLanguage({language}));
            return firstValueFrom(inject(TranslocoService).load(language));
        }),
        // The installation's look before anything is shown - no flash of the build's own name and colour. A backend that
        // does not answer within 3s does not hold the app: it starts with the build's look.
        provideAppInitializer(() => {
            const answered = firstValueFrom(
                inject(Actions).pipe(
                    ofType(
                        BrandingActions.loadInstallationBrandingSuccess,
                        BrandingActions.loadInstallationBrandingFailure,
                    ),
                    take(1),
                    timeout({first: 3000, with: () => of(null)}),
                ),
            );
            inject(Store).dispatch(BrandingActions.loadInstallationBranding());
            return answered;
        }),
        // Only connect the DevTools extension in dev mode - the JWT and login credentials that
        // flow through the store must not be inspectable via the browser extension in production.
        ...(isDevMode()
            ? [
                  provideStoreDevtools({
                      maxAge: 25,
                      logOnly: false,
                      autoPause: true,
                      trace: false,
                      traceLimit: 75,
                  }),
              ]
            : []),
    ],
};
