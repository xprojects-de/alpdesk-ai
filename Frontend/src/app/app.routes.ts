import {Routes} from "@angular/router";
import {DashboardComponent} from "./components/dashboard/dashboard.component";
import {LoginComponent} from "./components/login/login.component";
import {TenantAppComponent} from "./components/tenant-app/tenant-app.component";
import {adminGuard, tenantAdminGuard, tenantUserGuard} from "./guards/auth.guard";
import {taskEditorGuard} from "./guards/task-editor.guard";

const loadNotFound = () => import("./components/not-found/not-found.component").then(m => m.NotFoundComponent);
// Pages of both areas; the route's data.scope says whose data they show.
const loadTaskEditor = () => import("./components/task-editor/task-editor.component").then(m => m.TaskEditorComponent);
const loadTaskRun = () => import("./components/task-run/task-run.component").then(m => m.TaskRunComponent);
const loadProviderKeys = () =>
    import("./components/provider-key-list/provider-key-list.component").then(m => m.ProviderKeyListComponent);
const loadUsage = () => import("./components/usage/usage.component").then(m => m.UsageComponent);

export const routes: Routes = [
    {path: "", redirectTo: "/login", pathMatch: "full"},
    {path: "login", component: LoginComponent},
    // A tenant's own login page: its look before the login (docs/architecture/branding.md).
    {path: "login/:loginKey", component: LoginComponent},
    // The admin's area.
    {
        path: "dashboard",
        component: DashboardComponent,
        canActivate: [adminGuard],
        children: [
            {path: "", redirectTo: "tenants", pathMatch: "full"},
            {
                path: "tenants",
                loadComponent: () =>
                    import("./components/tenant-list/tenant-list.component").then(m => m.TenantListComponent),
            },
            // One tenant's page; the tab is part of the address (a reload or a link opens the same tab).
            {path: "tenants/:id", redirectTo: "tenants/:id/overview", pathMatch: "full"},
            // A tenant's task in the editor and one of its runs (the list is the tenant page's tab "tasks").
            {path: "tenants/:id/tasks/:taskId", loadComponent: loadTaskEditor, canDeactivate: [taskEditorGuard]},
            {path: "tenants/:id/tasks/:taskId/runs/:runId", loadComponent: loadTaskRun},
            {
                path: "tenants/:id/:tab",
                loadComponent: () =>
                    import("./components/tenant-detail/tenant-detail.component").then(m => m.TenantDetailComponent),
            },
            {
                path: "models",
                loadComponent: () =>
                    import("./components/ai-model-list/ai-model-list.component").then(m => m.AiModelListComponent),
            },
            {path: "provider-keys", loadComponent: loadProviderKeys, data: {scope: "global"}},
            {path: "usage", loadComponent: loadUsage, data: {scope: "admin"}},
            {
                path: "application-log",
                loadComponent: () =>
                    import("./components/application-log-list/application-log-list.component").then(
                        m => m.ApplicationLogListComponent,
                    ),
            },
            {
                path: "access-requests",
                loadComponent: () =>
                    import("./components/access-request-list/access-request-list.component").then(
                        m => m.AccessRequestListComponent,
                    ),
            },
            {path: "**", loadComponent: loadNotFound, data: {home: "/dashboard"}},
        ],
    },
    // The area of a tenant's employees.
    {
        path: "app",
        component: TenantAppComponent,
        canActivate: [tenantUserGuard],
        children: [
            {path: "", redirectTo: "chat", pathMatch: "full"},
            {
                path: "chat",
                loadComponent: () => import("./components/chat/chat.component").then(m => m.ChatComponent),
            },
            // The tenant admins' pages.
            {
                path: "handbook",
                loadComponent: () =>
                    import("./components/handbook-page/handbook-page.component").then(m => m.HandbookPageComponent),
            },
            {
                path: "handbook/:site",
                loadComponent: () =>
                    import("./components/handbook-page/handbook-page.component").then(m => m.HandbookPageComponent),
            },
            {
                path: "documents",
                canActivate: [tenantAdminGuard],
                loadComponent: () =>
                    import("./components/document-list/document-list.component").then(m => m.DocumentListComponent),
            },
            {
                path: "users",
                canActivate: [tenantAdminGuard],
                loadComponent: () =>
                    import("./components/tenant-users-page/tenant-users-page.component").then(
                        m => m.TenantUsersPageComponent,
                    ),
            },
            {
                path: "keys",
                canActivate: [tenantAdminGuard],
                loadComponent: () =>
                    import("./components/tenant-keys-page/tenant-keys-page.component").then(
                        m => m.TenantKeysPageComponent,
                    ),
            },
            {
                path: "assistant",
                canActivate: [tenantAdminGuard],
                loadComponent: () =>
                    import("./components/assistant-page/assistant-page.component").then(m => m.AssistantPageComponent),
            },
            {
                path: "software",
                canActivate: [tenantAdminGuard],
                loadComponent: () =>
                    import("./components/software-page/software-page.component").then(m => m.SoftwarePageComponent),
            },
            {
                path: "tasks",
                canActivate: [tenantAdminGuard],
                loadComponent: () =>
                    import("./components/tasks-page/tasks-page.component").then(m => m.TasksPageComponent),
            },
            {
                path: "tasks/:taskId",
                canActivate: [tenantAdminGuard],
                canDeactivate: [taskEditorGuard],
                loadComponent: loadTaskEditor,
            },
            {path: "tasks/:taskId/runs/:runId", canActivate: [tenantAdminGuard], loadComponent: loadTaskRun},
            {
                path: "file-stores",
                canActivate: [tenantAdminGuard],
                loadComponent: () =>
                    import("./components/file-stores-page/file-stores-page.component").then(
                        m => m.FileStoresPageComponent,
                    ),
            },
            {path: "usage", canActivate: [tenantAdminGuard], loadComponent: loadUsage, data: {scope: "tenant"}},
            {
                path: "account",
                loadComponent: () => import("./components/account/account.component").then(m => m.AccountComponent),
            },
            {path: "**", loadComponent: loadNotFound, data: {home: "/app"}},
        ],
    },
    {path: "**", redirectTo: "/login"},
];
