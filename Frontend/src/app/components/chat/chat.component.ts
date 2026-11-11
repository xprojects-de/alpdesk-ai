import {
    afterRenderEffect,
    Component,
    computed,
    DestroyRef,
    ElementRef,
    effect,
    inject,
    signal,
    viewChild,
} from "@angular/core";
import {takeUntilDestroyed, toSignal} from "@angular/core/rxjs-interop";
import {FormControl, ReactiveFormsModule} from "@angular/forms";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter, map} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatSelectModule} from "@angular/material/select";
import {MatMenuModule} from "@angular/material/menu";
import {CdkTextareaAutosize, TextFieldModule} from "@angular/cdk/text-field";
import {BreakpointObserver} from "@angular/cdk/layout";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatDialog} from "@angular/material/dialog";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {PROVIDER_LABELS} from "../../models/ai.model";
import {ActionDecision, ChatFile, ChoiceId, Conversation, PendingAction} from "../../models/chat.model";
import {ROLE_TENANT_ADMIN} from "../../models/auth.model";
import * as AccountSelectors from "../../store/account/account.selectors";
import * as AuthSelectors from "../../store/auth/auth.selectors";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import * as ChatActions from "../../store/chat/chat.actions";
import * as ChatSelectors from "../../store/chat/chat.selectors";
import * as TenantAiActions from "../../store/tenant-ai/tenant-ai.actions";
import * as TenantAiSelectors from "../../store/tenant-ai/tenant-ai.selectors";
import {
    answerSource,
    atLatest,
    clampListWidth,
    filterConversations,
    groupConversations,
    LIST_WIDTH,
    pickChatModel,
    filesExpanded,
    hasQuestion,
    pickTemplate,
    questionToSend,
} from "../../utils/chat.util";
import {CHAT_FILE_ACCEPT, chatUploadError} from "../../utils/chat-file.util";
import {copiedCode, CopyCodeLabels, renderMarkdown} from "../../utils/markdown.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {ChatActionCardComponent} from "./chat-action-card.component";
import {ChatThinkingComponent} from "./chat-thinking.component";
import {ChatToolsComponent} from "./chat-tools.component";
import {ChatFileChipComponent} from "./chat-file-chip.component";
import {ChatImagePreviewComponent} from "./chat-image-preview.component";
import {ChatImageSequenceComponent} from "./chat-image-sequence.component";
import {ChatVideoPreviewComponent} from "./chat-video-preview.component";
import {isCreatedImage, isCreatedVideo, splitSequence} from "../../utils/chat-image.util";
import {SoftwarePrompt} from "../../models/mcp.model";
import {softwarePromptLabel} from "../../utils/software-prompt.util";
import {DialogSave} from "../shared/dialog-save";
import {ChatPromptDialogComponent} from "./chat-prompt-dialog.component";
import {injectLocale} from "../../i18n/locale";

const MODEL_KEY = "chat_model_id";
/** The conversation list's width the user dragged it to, in this browser. */
const LIST_WIDTH_KEY = "chat_list_width";

/** Tenant area page "Chat": questions answered from the tenant's documents, conversations on the left. */
import {allSoftwareOff, choiceToSend, showsSoftwareChoice, topicsSwitchedOn} from "../../utils/software-choice.util";
@Component({
    selector: "app-chat",
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        MatSelectModule,
        MatMenuModule,
        TextFieldModule,
        MatTooltipModule,
        MatProgressBarModule,
        ChatFileChipComponent,
        ChatImagePreviewComponent,
        ChatImageSequenceComponent,
        ChatVideoPreviewComponent,
        ChatActionCardComponent,
        ChatThinkingComponent,
        ChatToolsComponent,
        TranslocoPipe,
    ],
    // The copy controls of code blocks come with an answer's HTML, which can bind no events of its own.
    host: {
        "(click)": "copyCode($event)",
        "(keydown.enter)": "copyCode($event)",
        "(keydown.space)": "copyCode($event)",
    },
    template: `
        <aside class="conversations" [class.open]="listOpen()" [style.flex-basis.px]="listWidth()">
            <div class="list-head">
                <div class="list-actions">
                    <button mat-flat-button class="new-chat" (click)="newChat()">
                        <mat-icon>edit_square</mat-icon>
                        {{ "chat.list.newChat" | transloco }}
                    </button>
                    <!-- On a phone the list covers the chat: back to the open conversation without choosing one. -->
                    @if (phone()) {
                        <button
                            mat-icon-button
                            (click)="listOpen.set(false)"
                            [attr.aria-label]="'common.close' | transloco"
                        >
                            <mat-icon>close</mat-icon>
                        </button>
                    }
                </div>
                <div class="app-search">
                    <mat-icon>search</mat-icon>
                    <input
                        type="search"
                        [placeholder]="'chat.list.search' | transloco"
                        [attr.aria-label]="'chat.list.search' | transloco"
                        [value]="query()"
                        (input)="query.set($any($event.target).value)"
                    />
                </div>
            </div>
            <nav class="conversation-list" [attr.aria-label]="'chat.list.label' | transloco">
                @for (group of groups(); track group.label) {
                    <div class="group">{{ group.label | transloco }}</div>
                    @for (conversation of group.conversations; track conversation.id) {
                        <div
                            class="conversation"
                            [class.selected]="conversation.id === selectedId()"
                            [attr.aria-current]="conversation.id === selectedId() ? 'true' : null"
                            tabindex="0"
                            role="button"
                            (click)="open(conversation)"
                            (keydown.enter)="open(conversation)"
                            [matTooltip]="conversation.title"
                            matTooltipPosition="right"
                            [matTooltipShowDelay]="600"
                        >
                            <span class="title">{{ conversation.title }}</span>
                            <button
                                mat-icon-button
                                class="delete"
                                (click)="confirmDelete(conversation, $event)"
                                [matTooltip]="'chat.list.delete' | transloco"
                                [attr.aria-label]="'chat.list.delete' | transloco"
                            >
                                <mat-icon>delete</mat-icon>
                            </button>
                        </div>
                    }
                } @empty {
                    <p class="empty">{{ (query() ? "chat.list.noMatch" : "chat.list.empty") | transloco }}</p>
                }
            </nav>
        </aside>

        <div
            class="splitter"
            role="separator"
            aria-orientation="vertical"
            [attr.aria-label]="'chat.splitter.label' | transloco"
            [matTooltip]="'chat.splitter.tooltip' | transloco"
            (pointerdown)="startResize($event)"
            (pointermove)="resize($event)"
            (pointerup)="endResize($event)"
            (pointercancel)="endResize($event)"
            (dblclick)="resetListWidth()"
        ></div>

        <section class="chat">
            <!-- On a phone one bar holds what takes rows above the input elsewhere: the list, the topics, the files. -->
            <div class="mobile-bar">
                <button mat-stroked-button (click)="listOpen.set(!listOpen())">
                    <mat-icon>forum</mat-icon>
                    {{ "chat.list.label" | transloco }}
                </button>
                @if (phone() && showsTopics()) {
                    <button
                        mat-stroked-button
                        class="bar-topics"
                        [class.partial]="topicsOn() < software().length"
                        [matMenuTriggerFor]="topicMenu"
                        [attr.aria-label]="'chat.software.label' | transloco"
                    >
                        <mat-icon>tune</mat-icon>
                        {{ "chat.software.count" | transloco: {on: topicsOn(), total: software().length} }}
                    </button>
                    <mat-menu #topicMenu="matMenu">
                        @for (topic of software(); track topic.id) {
                            <!-- Kept open: one topic after the other is switched without opening it again. -->
                            <button
                                mat-menu-item
                                class="topic-item"
                                [class.off]="excludedConnectionIds().includes(topic.id)"
                                [attr.aria-pressed]="!excludedConnectionIds().includes(topic.id)"
                                (click)="$event.stopPropagation(); toggleTopic(topic.id)"
                            >
                                <mat-icon>{{
                                    excludedConnectionIds().includes(topic.id)
                                        ? "radio_button_unchecked"
                                        : "check_circle"
                                }}</mat-icon>
                                <span>{{ topic.name }}</span>
                            </button>
                        }
                        @if (allTopicsOff()) {
                            <div class="menu-note">
                                {{ (answersThroughAgent() ? "chat.agent.allOff" : "chat.software.allOff") | transloco }}
                            </div>
                        }
                    </mat-menu>
                }
                @if (phone() && files().length) {
                    <button
                        mat-stroked-button
                        class="bar-files"
                        [attr.aria-expanded]="showFiles()"
                        [attr.aria-label]="'chat.files.toggle' | transloco: {count: files().length}"
                        (click)="filesOpen.set(!showFiles())"
                    >
                        <mat-icon>attach_file</mat-icon>
                        {{ files().length }}
                        <mat-icon iconPositionEnd>{{ showFiles() ? "expand_less" : "expand_more" }}</mat-icon>
                    </button>
                }
            </div>
            @if (messagesLoading()) {
                <mat-progress-bar mode="indeterminate" class="loading" />
            }
            <div class="messages" #messageList (scroll)="onMessagesScroll()">
                <div class="column">
                    @for (item of shownMessages(); track item.message.id; let lastMessage = $last) {
                        @let message = item.message;
                        @if (message.role === "USER") {
                            <div class="message user">
                                <div class="bubble">{{ message.content }}</div>
                                @if (message.files.length) {
                                    <div class="file-chips">
                                        @for (file of message.files; track file.id) {
                                            <app-chat-file-chip [file]="file" (download)="download(file)" />
                                        }
                                    </div>
                                }
                            </div>
                        } @else {
                            <div class="message assistant">
                                <div class="bot" aria-hidden="true">
                                    <mat-icon fontSet="material-icons">auto_awesome</mat-icon>
                                </div>
                                <div class="answer">
                                    @if (thinking()[message.id]; as text) {
                                        <app-chat-thinking [text]="text" />
                                    }
                                    @if (tools()[message.id]; as steps) {
                                        <app-chat-tools [steps]="steps" />
                                    }
                                    @if (item.html !== null) {
                                        @if (message.content.trim()) {
                                            <div class="markdown" [innerHTML]="item.html"></div>
                                        }
                                    } @else {
                                        <div class="plain">{{ message.content }}</div>
                                    }
                                    @if (message.action; as action) {
                                        <app-chat-action-card
                                            [action]="action"
                                            [disabled]="sending()"
                                            (decide)="decide(action, $event)"
                                        />
                                    }
                                    @if (message.files.length) {
                                        @if (item.sequence.length) {
                                            <app-chat-image-sequence
                                                [files]="item.sequence"
                                                (download)="download($event)"
                                            />
                                        }
                                        <div class="file-chips">
                                            @for (file of item.others; track file.id) {
                                                @if (isCreatedImage(file)) {
                                                    <app-chat-image-preview [file]="file" (download)="download(file)" />
                                                } @else if (isCreatedVideo(file)) {
                                                    <app-chat-video-preview [file]="file" (download)="download(file)" />
                                                } @else {
                                                    <app-chat-file-chip [file]="file" (download)="download(file)" />
                                                }
                                            }
                                        </div>
                                    }
                                    <div class="answer-foot">
                                        @for (source of sources()[message.id] ?? []; track $index) {
                                            @let shown = answerSource(source);
                                            @if (shown.href) {
                                                <a
                                                    class="source"
                                                    [href]="shown.href"
                                                    target="_blank"
                                                    [attr.rel]="'noopener noreferrer'"
                                                    [matTooltip]="shown.href"
                                                    ><mat-icon>public</mat-icon>{{ shown.label }}</a
                                                >
                                            } @else {
                                                <span class="source"
                                                    ><mat-icon>description</mat-icon>{{ shown.label }}</span
                                                >
                                            }
                                        }
                                        @if (message.content.trim()) {
                                            <button
                                                mat-icon-button
                                                class="copy"
                                                (click)="copy(message.content)"
                                                [matTooltip]="'chat.answer.copy' | transloco"
                                                [attr.aria-label]="'chat.answer.copy' | transloco"
                                            >
                                                <mat-icon>content_copy</mat-icon>
                                            </button>
                                        }
                                        @if (message.waiting && lastMessage) {
                                            <span class="app-chip warn">{{ "chat.agent.waiting" | transloco }}</span>
                                        }
                                        <span class="model">{{ message.modelName }}</span>
                                    </div>
                                </div>
                            </div>
                        }
                    } @empty {
                        @if (!pending() && !messagesLoading()) {
                            <div class="welcome">
                                @if (logoUrl(); as logo) {
                                    <img class="welcome-logo" [src]="logo" alt="" />
                                } @else {
                                    <div class="welcome-mark">
                                        <mat-icon fontSet="material-icons">auto_awesome</mat-icon>
                                    </div>
                                }
                                <h2>{{ "chat.welcome.greeting" | transloco }}</h2>
                                @if (answersThroughAgent()) {
                                    <p class="muted">{{ "chat.agent.welcome" | transloco }}</p>
                                } @else if (templates().length > 0) {
                                    <div class="templates">
                                        @for (option of templates(); track option.id) {
                                            <button
                                                type="button"
                                                class="template-card"
                                                [class.chosen]="templateId() === option.id"
                                                (click)="useTemplate(option.id)"
                                            >
                                                <mat-icon>bolt</mat-icon>
                                                <span>{{ option.name }}</span>
                                            </button>
                                        }
                                    </div>
                                }
                            </div>
                        }
                    }
                    @if (pending(); as exchange) {
                        @if (exchange.question !== null) {
                            <div class="message user">
                                <div class="bubble">{{ exchange.question }}</div>
                            </div>
                        }
                        <div class="message assistant">
                            <div class="bot" aria-hidden="true">
                                <mat-icon fontSet="material-icons">auto_awesome</mat-icon>
                            </div>
                            <div class="answer">
                                @if (exchange.thinking) {
                                    <app-chat-thinking [text]="exchange.thinking" [live]="!exchange.answer" />
                                }
                                @if (exchange.tools.length) {
                                    <app-chat-tools [steps]="exchange.tools" [running]="exchange.toolRunning" />
                                }
                                @if (streamingHtml(); as html) {
                                    <div class="markdown" [innerHTML]="html"></div>
                                } @else {
                                    <div class="typing" [attr.aria-label]="'chat.answer.typing' | transloco">
                                        <span></span><span></span><span></span>
                                    </div>
                                }
                                @if (exchange.sources.length) {
                                    <div class="answer-foot">
                                        @for (source of exchange.sources; track $index) {
                                            @let shown = answerSource(source);
                                            @if (shown.href) {
                                                <a
                                                    class="source"
                                                    [href]="shown.href"
                                                    target="_blank"
                                                    [attr.rel]="'noopener noreferrer'"
                                                    [matTooltip]="shown.href"
                                                    ><mat-icon>public</mat-icon>{{ shown.label }}</a
                                                >
                                            } @else {
                                                <span class="source"
                                                    ><mat-icon>description</mat-icon>{{ shown.label }}</span
                                                >
                                            }
                                        }
                                    </div>
                                }
                            </div>
                        </div>
                    }
                </div>
            </div>

            <div class="composer-wrap">
                <div class="column">
                    <!-- On a phone the bar above opens and folds the files: only the opened strip is here. -->
                    @if ((files().length || uploading()) && (!phone() || showFiles() || uploading())) {
                        <div class="conversation-files">
                            @if (!phone()) {
                                <button
                                    mat-button
                                    class="files-toggle"
                                    [attr.aria-expanded]="showFiles()"
                                    (click)="filesOpen.set(!showFiles())"
                                >
                                    <mat-icon>{{ showFiles() ? "expand_less" : "expand_more" }}</mat-icon>
                                    {{ "chat.files.toggle" | transloco: {count: files().length} }}
                                </button>
                            }
                            @if (showFiles()) {
                                <div class="file-chips">
                                    @for (file of files(); track file.id) {
                                        <app-chat-file-chip
                                            [file]="file"
                                            [removable]="true"
                                            (download)="download(file)"
                                            (remove)="confirmRemoveFile(file)"
                                        />
                                    }
                                </div>
                                <div class="files-hint">
                                    {{ "chat.files.hint" | transloco }}
                                </div>
                            }
                            @if (uploading()) {
                                <mat-progress-bar mode="indeterminate" />
                            }
                        </div>
                    }
                    @if (!answersThroughAgent() && chatModels().length > 0 && modelId() === null) {
                        <p class="app-note warn">
                            <mat-icon>info</mat-icon>
                            @if (tenantAdmin()) {
                                {{ "chat.noModel.tenantAdmin" | transloco }}
                            } @else {
                                {{ "chat.noModel.user" | transloco }}
                            }
                        </p>
                    }
                    @if (showsTopics() && !phone()) {
                        <div class="topics" role="group" [attr.aria-label]="'chat.software.label' | transloco">
                            <span class="topics-label">{{ "chat.software.label" | transloco }}</span>
                            @for (topic of software(); track topic.id) {
                                <button
                                    type="button"
                                    class="app-chip topic"
                                    [class.off]="excludedConnectionIds().includes(topic.id)"
                                    [attr.aria-pressed]="!excludedConnectionIds().includes(topic.id)"
                                    [attr.aria-label]="'chat.software.toggle' | transloco: {name: topic.name}"
                                    (click)="toggleTopic(topic.id)"
                                >
                                    <mat-icon>{{
                                        excludedConnectionIds().includes(topic.id)
                                            ? "radio_button_unchecked"
                                            : "check_circle"
                                    }}</mat-icon>
                                    {{ topic.name }}
                                </button>
                            }
                            @if (allTopicsOff()) {
                                <span class="topics-note">{{
                                    (answersThroughAgent() ? "chat.agent.allOff" : "chat.software.allOff") | transloco
                                }}</span>
                            }
                        </div>
                    }
                    <div class="composer">
                        <textarea
                            #prompt
                            cdkTextareaAutosize
                            [cdkAutosizeMinRows]="phone() ? 1 : 2"
                            [cdkAutosizeMaxRows]="phone() ? 6 : 10"
                            [formControl]="input"
                            (keydown.enter)="onEnter($event)"
                            [attr.aria-label]="'chat.composer.label' | transloco"
                            [placeholder]="
                                selectedTemplate()
                                    ? ('chat.composer.placeholderTemplate'
                                      | transloco: {name: selectedTemplate()!.name})
                                    : ((phone() ? 'chat.composer.placeholderPhone' : 'chat.composer.placeholder')
                                      | transloco)
                            "
                        ></textarea>
                        <div class="composer-row">
                            <input
                                #fileInput
                                type="file"
                                multiple
                                hidden
                                [accept]="fileAccept"
                                (change)="onFilesChosen(fileInput)"
                            />
                            <button
                                mat-icon-button
                                (click)="fileInput.click()"
                                [disabled]="sending() || uploading()"
                                [matTooltip]="'chat.composer.attach' | transloco"
                                [attr.aria-label]="'chat.composer.attach' | transloco"
                            >
                                <mat-icon>attach_file</mat-icon>
                            </button>
                            @if (softwarePrompts().length > 0) {
                                <button
                                    mat-icon-button
                                    [matMenuTriggerFor]="promptMenu"
                                    [disabled]="sending()"
                                    [matTooltip]="'chat.prompts.button' | transloco"
                                    [attr.aria-label]="'chat.prompts.button' | transloco"
                                >
                                    <!-- The software's sign (its page in the menu) - bolt is the templates'. -->
                                    <mat-icon>hub</mat-icon>
                                </button>
                                <mat-menu #promptMenu="matMenu">
                                    @for (option of softwarePrompts(); track option.id) {
                                        <button mat-menu-item (click)="openPrompt(option)">
                                            <span class="prompt-option">
                                                <span>{{ promptLabel(option) }}</span>
                                                <small class="muted">{{ option.software }}</small>
                                            </span>
                                        </button>
                                    }
                                </mat-menu>
                            }
                            @if (!answersThroughAgent()) {
                                <mat-select
                                    class="pill"
                                    [formControl]="model"
                                    [panelWidth]="null"
                                    [attr.aria-label]="'chat.composer.model' | transloco"
                                    [matTooltip]="'chat.composer.model' | transloco"
                                >
                                    <!-- On a phone the pill is narrow: the model's name alone, not its provider. Always
                                     there - a trigger added or removed later is not picked up by the select. -->
                                    <mat-select-trigger>
                                        @if (selectedModel(); as selected) {
                                            {{ selected.displayName }}
                                            @if (!phone()) {
                                                {{
                                                    "chat.composer.provider"
                                                        | transloco
                                                            : {
                                                                  provider:
                                                                      (providerLabels[selected.provider] | transloco),
                                                              }
                                                }}
                                            }
                                        }
                                    </mat-select-trigger>
                                    @for (option of chatModels(); track option.id) {
                                        <mat-option [value]="option.id" [disabled]="option.keySource === null">
                                            {{ option.displayName }}
                                            <span class="provider">
                                                {{
                                                    (option.keySource === null
                                                        ? "chat.composer.providerNoKey"
                                                        : "chat.composer.provider"
                                                    )
                                                        | transloco
                                                            : {provider: (providerLabels[option.provider] | transloco)}
                                                }}
                                            </span>
                                        </mat-option>
                                    }
                                </mat-select>
                            }
                            @if (!answersThroughAgent() && templates().length > 0) {
                                <mat-select
                                    class="pill"
                                    [class.on]="templateId() !== null"
                                    [formControl]="template"
                                    [panelWidth]="null"
                                    [placeholder]="'chat.composer.templatePlaceholder' | transloco"
                                    [attr.aria-label]="'chat.composer.template' | transloco"
                                    [matTooltip]="'chat.composer.template' | transloco"
                                >
                                    <mat-option [value]="null">{{
                                        "chat.composer.templateDefault" | transloco
                                    }}</mat-option>
                                    @for (option of templates(); track option.id) {
                                        <mat-option [value]="option.id">{{ option.name }}</mat-option>
                                    }
                                </mat-select>
                            }
                            <span class="spacer"></span>
                            @if (sending()) {
                                <button
                                    class="send stop"
                                    (click)="stop()"
                                    [matTooltip]="'chat.composer.stopHint' | transloco"
                                    [attr.aria-label]="'chat.composer.stop' | transloco"
                                >
                                    <mat-icon>stop</mat-icon>
                                </button>
                            } @else {
                                <button
                                    class="send"
                                    (click)="send()"
                                    [disabled]="!canSend()"
                                    [matTooltip]="'chat.composer.send' | transloco"
                                    [attr.aria-label]="'chat.composer.send' | transloco"
                                >
                                    <mat-icon>arrow_upward</mat-icon>
                                </button>
                            }
                        </div>
                    </div>
                    <div class="hint" [class.in-conversation]="shownMessages().length > 0">
                        {{ "chat.composer.disclaimer" | transloco }}
                    </div>
                </div>
            </div>
        </section>
    `,
    styles: [
        `
            :host {
                display: flex;
                height: 100%;
                min-height: 420px;
            }

            .conversations {
                flex: 0 0 auto;
                display: flex;
                flex-direction: column;
                min-height: 0;
                background: var(--app-surface);
            }

            .list-head {
                display: flex;
                flex-direction: column;
                gap: 10px;
                padding: 14px 12px 6px;
            }

            .list-actions {
                display: flex;
                align-items: center;
                gap: 6px;
            }

            .list-actions .new-chat {
                flex: 1 1 auto;
            }

            .list-head .app-search {
                max-width: none;
                flex: none;
            }

            .conversation-list {
                flex: 1 1 auto;
                overflow-y: auto;
                padding: 0 8px 16px;
            }

            .group {
                font-size: 11px;
                font-weight: 600;
                letter-spacing: 0.06em;
                text-transform: uppercase;
                color: var(--app-text-3);
                padding: 14px 8px 6px;
            }

            .conversation {
                display: flex;
                align-items: center;
                border-radius: 8px;
                padding: 0 2px 0 10px;
                min-height: 36px;
                color: var(--app-text-2);
                font-size: 13.5px;
                cursor: pointer;
            }

            .conversation:hover {
                background: var(--app-surface-2);
                color: var(--app-text);
            }

            .conversation.selected {
                background: var(--app-brand-soft);
                color: var(--app-brand-text);
                font-weight: 600;
            }

            .conversation .title {
                flex: 1 1 auto;
                min-width: 0;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }

            /* The delete button shows on the entry under the pointer or with the focus - a bin on every line was noise. */
            .conversation .delete {
                visibility: hidden;
                flex: 0 0 auto;
                --mat-icon-button-state-layer-size: 32px;
                width: 32px;
                height: 32px;
                padding: 4px;
            }

            .conversation .delete mat-icon {
                font-size: 18px;
                width: 18px;
                height: 18px;
            }

            .conversation:hover .delete,
            .conversation:focus-within .delete,
            .conversation.selected .delete {
                visibility: visible;
            }

            .empty {
                color: var(--app-text-3);
                padding: 8px;
            }

            /* The line between list and chat - a wider grip than it looks, so it is easy to catch. */
            .splitter {
                flex: 0 0 9px;
                margin: 0 -4px;
                z-index: 1;
                cursor: col-resize;
                touch-action: none;
                position: relative;
            }

            .splitter::before {
                content: "";
                position: absolute;
                top: 0;
                bottom: 0;
                left: 4px;
                width: 1px;
                background: var(--app-border);
            }

            .splitter:hover::before,
            .splitter.dragging::before {
                left: 3px;
                width: 3px;
                background: var(--app-brand);
            }

            .chat {
                flex: 1 1 auto;
                min-width: 0;
                display: flex;
                flex-direction: column;
                position: relative;
                background: var(--app-bg);
            }

            .loading {
                position: absolute;
                top: 0;
                left: 0;
                right: 0;
            }

            .messages {
                flex: 1 1 auto;
                overflow-y: auto;
                min-height: 0;
            }

            /* Answers read best in a column of their own, not over the whole width of a wide screen. */
            .column {
                max-width: 800px;
                margin: 0 auto;
                padding: 0 24px;
            }

            .messages .column {
                padding-top: 28px;
                padding-bottom: 12px;
                display: flex;
                flex-direction: column;
                gap: 22px;
            }

            .message.user {
                align-self: flex-end;
                max-width: 78%;
                display: flex;
                flex-direction: column;
                align-items: flex-end;
            }

            .bubble {
                background: var(--app-brand);
                color: var(--app-on-brand);
                padding: 10px 14px;
                border-radius: 16px 16px 4px 16px;
                white-space: pre-wrap;
                word-break: break-word;
            }

            .message.assistant {
                display: flex;
                gap: 12px;
            }

            .bot {
                width: 30px;
                height: 30px;
                border-radius: 9px;
                background: var(--app-brand);
                color: var(--app-on-brand);
                display: grid;
                place-items: center;
                flex: 0 0 auto;
            }

            .bot mat-icon {
                font-size: 18px;
                width: 18px;
                height: 18px;
            }

            .answer {
                flex: 1 1 auto;
                min-width: 0;
                padding-top: 4px;
            }

            .plain {
                white-space: pre-wrap;
                word-break: break-word;
            }

            .answer-foot {
                display: flex;
                flex-wrap: wrap;
                align-items: center;
                gap: 6px;
                margin-top: 8px;
            }

            .source {
                display: inline-flex;
                align-items: center;
                gap: 5px;
                font-size: 12px;
                border: 1px solid var(--app-border);
                background: var(--app-surface);
                border-radius: 6px;
                padding: 2px 8px;
                color: var(--app-text-2);
                max-width: 100%;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }

            a.source {
                text-decoration: none;
            }

            a.source:hover {
                color: var(--app-brand-text);
                border-color: var(--app-brand-soft);
            }

            .source mat-icon {
                font-size: 15px;
                width: 15px;
                height: 15px;
                color: var(--app-text-3);
            }

            .copy {
                --mat-icon-button-state-layer-size: 30px;
                width: 30px;
                height: 30px;
                padding: 5px;
                color: var(--app-text-3);
            }

            .copy mat-icon {
                font-size: 17px;
                width: 17px;
                height: 17px;
            }

            .model {
                margin-left: auto;
                font-size: 11.5px;
                color: var(--app-text-3);
            }

            /* Answers come as Markdown and are inserted with [innerHTML], which gets no view attribute - hence ::ng-deep. */
            .markdown {
                line-height: 1.65;
                min-width: 0;
                max-width: 100%;
                word-break: break-word;
            }

            .markdown ::ng-deep > :first-child {
                margin-top: 0;
            }

            .markdown ::ng-deep > :last-child {
                margin-bottom: 0;
            }

            .markdown ::ng-deep :is(h1, h2, h3, h4) {
                margin: 14px 0 6px;
                font-size: 1.05em;
                font-weight: 600;
            }

            .markdown ::ng-deep h1 {
                font-size: 1.2em;
            }

            .markdown ::ng-deep :is(p, ul, ol, blockquote) {
                margin: 0 0 10px;
            }

            .markdown ::ng-deep :is(ul, ol) {
                padding-left: 22px;
            }

            .markdown ::ng-deep li {
                margin: 2px 0;
            }

            .markdown ::ng-deep a {
                color: var(--app-brand-text);
            }

            .markdown ::ng-deep table {
                border-collapse: collapse;
                display: block;
                overflow-x: auto;
                margin: 4px 0 12px;
                font-size: 13px;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                background: var(--app-surface);
            }

            .markdown ::ng-deep :is(th, td) {
                border-bottom: 1px solid var(--app-border);
                padding: 7px 12px;
                text-align: left;
            }

            .markdown ::ng-deep tr:last-child td {
                border-bottom: 0;
            }

            /* On a phone tighter, and a cell keeps its words whole: the answer's word-break squeezed a table of numbers
               into the width by breaking them ("Zei t", "111, 0"). A table wider than the screen scrolls in itself - the
               page never does. */
            @media (max-width: 760px) {
                .markdown ::ng-deep table {
                    font-size: 12.5px;
                }

                .markdown ::ng-deep :is(th, td) {
                    padding: 6px 8px;
                    vertical-align: top;
                    word-break: normal;
                    overflow-wrap: break-word;
                }
            }

            .markdown ::ng-deep th {
                background: var(--app-surface-2);
                font-weight: 600;
            }

            .markdown ::ng-deep code {
                font-family: var(--app-mono);
                font-size: 0.88em;
                background: var(--app-surface-2);
                border: 1px solid var(--app-border);
                border-radius: 5px;
                padding: 1px 5px;
            }

            /* A code block's frame is its .code-block (styles.scss). */
            .markdown ::ng-deep pre {
                margin: 0;
                padding: 10px 12px;
                overflow-x: auto;
            }

            .markdown ::ng-deep pre code {
                background: none;
                border: 0;
                padding: 0;
            }

            .markdown ::ng-deep blockquote {
                border-left: 3px solid var(--app-border-strong);
                padding-left: 10px;
                color: var(--app-text-2);
            }

            .welcome {
                padding-top: 8vh;
                text-align: center;
            }

            .welcome-mark {
                width: 48px;
                height: 48px;
                border-radius: 14px;
                background: var(--app-brand);
                color: var(--app-on-brand);
                display: grid;
                place-items: center;
                margin: 0 auto 16px;
            }

            .welcome-logo {
                max-width: 96px;
                max-height: 48px;
                object-fit: contain;
                margin-bottom: 16px;
            }

            .welcome h2 {
                margin: 0 0 28px;
                font-size: 26px;
                font-weight: 650;
                letter-spacing: -0.02em;
            }

            .templates {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                gap: 10px;
                text-align: left;
            }

            .template-card {
                display: flex;
                align-items: center;
                gap: 10px;
                border: 1px solid var(--app-border);
                background: var(--app-surface);
                color: var(--app-text);
                border-radius: 10px;
                padding: 12px 14px;
                font: inherit;
                font-weight: 500;
                text-align: left;
                cursor: pointer;
                transition:
                    border-color 0.15s,
                    box-shadow 0.15s,
                    transform 0.15s;
            }

            .template-card:hover,
            .template-card.chosen {
                border-color: color-mix(in oklab, var(--app-brand) 45%, var(--app-border));
                box-shadow: var(--app-shadow-1);
                transform: translateY(-1px);
            }

            .template-card mat-icon {
                color: var(--app-brand-text);
                background: var(--app-brand-soft);
                border-radius: 8px;
                padding: 6px;
                font-size: 18px;
                width: 18px;
                height: 18px;
                flex: 0 0 auto;
            }

            .typing {
                display: flex;
                gap: 4px;
                padding: 10px 0;
            }

            .typing span {
                width: 7px;
                height: 7px;
                border-radius: 50%;
                background: var(--app-text-3);
                animation: typing 1.2s infinite ease-in-out;
            }

            .typing span:nth-child(2) {
                animation-delay: 0.2s;
            }

            .typing span:nth-child(3) {
                animation-delay: 0.4s;
            }

            @keyframes typing {
                0%,
                80%,
                100% {
                    opacity: 0.3;
                    transform: translateY(0);
                }
                40% {
                    opacity: 1;
                    transform: translateY(-3px);
                }
            }

            .composer-wrap {
                flex: 0 0 auto;
                padding: 8px 0 16px;
            }

            .composer-wrap .app-note {
                margin: 0 0 8px;
            }

            .composer {
                background: var(--app-surface);
                border: 1px solid var(--app-border-strong);
                border-radius: 16px;
                box-shadow: var(--app-shadow-2);
                padding: 10px 10px 8px 14px;
            }

            .composer:focus-within {
                border-color: color-mix(in oklab, var(--app-brand) 60%, var(--app-border-strong));
            }

            .composer textarea {
                display: block;
                width: 100%;
                border: 0;
                outline: 0;
                resize: none;
                background: transparent;
                color: var(--app-text);
                font: inherit;
                line-height: 1.5;
                padding: 4px 0;
            }

            .composer textarea::placeholder {
                color: var(--app-text-3);
            }

            .composer-row {
                display: flex;
                align-items: center;
                gap: 6px;
                flex-wrap: wrap;
            }

            .spacer {
                flex: 1 1 auto;
            }

            /* Two lines per entry - the prompt and its software: room between the entries of the dense menu. */
            .prompt-option {
                display: flex;
                flex-direction: column;
                padding: 8px 0;
            }

            /* The model and template choice as small pills instead of full form fields. */
            .pill {
                width: 240px;
                max-width: 100%;
                border: 1px solid var(--app-border);
                border-radius: 15px;
                padding: 4px 8px 4px 12px;
                font-size: 12.5px;
                --mat-select-trigger-text-size: 12.5px;
                --mat-select-enabled-trigger-text-color: var(--app-text-2);
            }

            .pill.on {
                background: var(--app-brand-soft);
                border-color: transparent;
                --mat-select-enabled-trigger-text-color: var(--app-brand-text);
            }

            .provider {
                color: var(--app-text-3);
            }

            .send {
                width: 36px;
                height: 36px;
                border-radius: 50%;
                border: 0;
                background: var(--app-brand);
                color: var(--app-on-brand);
                display: grid;
                place-items: center;
                cursor: pointer;
            }

            .send:disabled {
                background: var(--app-surface-3);
                color: var(--app-text-3);
                cursor: default;
            }

            .send.stop {
                background: var(--app-text);
                color: var(--app-surface);
            }

            .hint {
                margin-top: 8px;
                text-align: center;
                font-size: 11.5px;
                color: var(--app-text-3);
            }

            .file-chips {
                display: flex;
                flex-wrap: wrap;
                gap: 6px;
                margin-top: 6px;
            }

            .topics {
                display: flex;
                flex-wrap: wrap;
                align-items: center;
                gap: 6px;
                margin-bottom: 8px;
            }

            .topics-label {
                color: var(--app-text-2);
                font-size: 12.5px;
                margin-right: 2px;
            }

            .topic {
                cursor: pointer;
                border: none;
                font: inherit;
                display: inline-flex;
                align-items: center;
                gap: 4px;
            }

            .topic mat-icon {
                font-size: 16px;
                width: 16px;
                height: 16px;
                color: var(--app-brand);
            }

            .topic.off {
                opacity: 0.6;
                text-decoration: line-through;
            }

            .topic.off mat-icon {
                color: var(--app-text-2);
            }

            .topics-note {
                color: var(--app-text-2);
                font-size: 12.5px;
                flex-basis: 100%;
            }

            /* The phone's topics menu: the chips' signs and their struck-through "off". */
            .topic-item mat-icon {
                color: var(--app-brand);
            }

            /* The name struck through, not its sign - the empty circle says "off" on its own. */
            .topic-item.off span {
                text-decoration: line-through;
                color: var(--app-text-2);
            }

            .topic-item.off mat-icon {
                color: var(--app-text-2);
            }

            .menu-note {
                max-width: 240px;
                padding: 8px 16px;
                color: var(--app-text-2);
                font-size: 12.5px;
            }

            .conversation-files {
                padding: 0 0 8px;
            }

            .files-toggle {
                font-size: 12.5px;
                height: 28px;
                padding: 0 8px 0 4px;
                color: var(--app-text-2);
            }

            /* Opened, many files still leave room for the answer: the strip scrolls in itself. */
            .conversation-files .file-chips {
                max-height: 30vh;
                overflow-y: auto;
            }

            .files-hint {
                font-size: 11.5px;
                color: var(--app-text-3);
                margin-top: 4px;
            }

            .mobile-bar {
                display: none;
            }

            /* On a phone the chat takes the whole width; the list slides over it from the "Chats" button. */
            @media (max-width: 760px) {
                .splitter {
                    display: none;
                }

                .conversations {
                    display: none;
                }

                .conversations.open {
                    display: flex;
                    position: absolute;
                    inset: 0;
                    z-index: 5;
                }

                :host {
                    position: relative;
                }

                .mobile-bar {
                    display: flex;
                    gap: 6px;
                    padding: 4px 8px 0;
                    --mat-button-outlined-container-height: 32px;
                    --mat-button-outlined-horizontal-padding: 10px;
                }

                /* Not every topic on: told by the button's colour, as the struck-through chips tell it elsewhere. */
                .bar-topics.partial {
                    background: color-mix(in oklab, var(--app-warn) 12%, transparent);
                    --mat-button-outlined-label-text-color: var(--app-warn);
                    --mat-button-outlined-outline-color: var(--app-warn);
                }

                .column {
                    padding: 0 12px;
                }

                /* Every line the composer takes is a line less of the answer: one row of controls, less padding. */
                .composer-wrap {
                    padding: 4px 0 8px;
                }

                .composer {
                    padding: 6px 6px 6px 12px;
                    border-radius: 14px;
                }

                .composer-row {
                    flex-wrap: nowrap;
                    gap: 4px;
                }

                .pill {
                    flex: 1 1 0;
                    width: auto;
                    min-width: 0;
                    padding: 3px 6px 3px 10px;
                }

                .spacer {
                    display: none;
                }

                .send {
                    flex: 0 0 auto;
                }

                /* Shown on the empty chat; within a conversation it would cost two lines below every answer. */
                .hint.in-conversation {
                    display: none;
                }

                .hint {
                    margin-top: 6px;
                }
            }
        `,
    ],
})
export class ChatComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    private readonly locale = injectLocale();
    private destroyRef = inject(DestroyRef);

    readonly conversations = this.store.selectSignal(ChatSelectors.selectConversations);
    readonly query = signal("");
    /** The list under headings of when a conversation was last used - those of the search. */
    readonly groups = computed(() =>
        groupConversations(filterConversations(this.conversations(), this.query()), new Date()),
    );
    /** On a phone: whether the conversation list is slid over the chat. */
    readonly listOpen = signal(false);
    readonly logoUrl = this.store.selectSignal(BrandingSelectors.selectLogoUrl);
    readonly selectedId = this.store.selectSignal(ChatSelectors.selectSelectedConversationId);
    readonly messages = this.store.selectSignal(ChatSelectors.selectMessages);
    readonly messagesLoading = this.store.selectSignal(ChatSelectors.selectMessagesLoading);
    readonly sources = this.store.selectSignal(ChatSelectors.selectSources);
    readonly answerSource = answerSource;
    readonly thinking = this.store.selectSignal(ChatSelectors.selectThinking);
    readonly tools = this.store.selectSignal(ChatSelectors.selectTools);
    readonly pending = this.store.selectSignal(ChatSelectors.selectPendingExchange);
    /** The active language's texts, once loaded: the answers' copy controls are labelled again when it is switched. */
    private readonly translation = toSignal(this.transloco.selectTranslation());
    /** The same width at which the styles turn to the phone layout. */
    readonly phone = toSignal(
        inject(BreakpointObserver)
            .observe("(max-width: 760px)")
            .pipe(map(state => state.matches)),
        {initialValue: false},
    );
    /**
     * Assistant answers as HTML from their Markdown, and their created images split into the sequence and the rest
     * (both once per change, not per check).
     */
    readonly shownMessages = computed(() => {
        const labels = this.copyLabels();
        return this.messages().map(message => ({
            message,
            html: message.role === "ASSISTANT" ? renderMarkdown(message.content, labels) : null,
            ...splitSequence(message.files),
        }));
    });
    readonly streamingHtml = computed(() => {
        const answer = this.pending()?.answer;
        return answer ? renderMarkdown(answer, this.copyLabels()) : null;
    });
    readonly sending = this.store.selectSignal(ChatSelectors.selectSending);
    readonly chatModels = this.store.selectSignal(TenantAiSelectors.selectChatModels);
    private readonly roles = this.store.selectSignal(AuthSelectors.selectAuthRoles);
    /** A tenant admin adds the missing key itself; a user is sent to the company's admin. */
    readonly tenantAdmin = computed(() => this.roles().includes(ROLE_TENANT_ADMIN));
    readonly files = this.store.selectSignal(ChatSelectors.selectFiles);
    /** The user's choice to open or fold the files above the input - null: by their number (utils/chat.util.ts). */
    readonly filesOpen = signal<boolean | null>(null);
    readonly showFiles = computed(() => filesExpanded(this.files().length, this.phone(), this.filesOpen()));
    readonly uploading = this.store.selectSignal(ChatSelectors.selectUploading);
    readonly fileAccept = CHAT_FILE_ACCEPT;
    readonly providerLabels = PROVIDER_LABELS;

    /** The conversation list's width - dragged with the splitter, remembered in this browser. */
    readonly listWidth = signal(this.readListWidth());
    /** While the splitter is dragged: where the drag started. */
    private drag: {startX: number; startWidth: number} | null = null;
    readonly model = new FormControl<number | null>(null);
    readonly templates = this.store.selectSignal(ChatSelectors.selectTemplates);
    /** The prompts of the connected software the user may use - filled in a dialog, their text into the input. */
    readonly softwarePrompts = this.store.selectSignal(ChatSelectors.selectSoftwarePrompts);
    /** The topics (connected software) the user reaches, and those the open conversation leaves out. */
    readonly software = this.store.selectSignal(ChatSelectors.selectSoftware);
    readonly excludedConnectionIds = this.store.selectSignal(ChatSelectors.selectExcludedConnectionIds);
    /** The tenant's chat answers through its A2A agent: no model, template or software of ours to choose. */
    readonly answersThroughAgent = computed(
        () => this.store.selectSignal(AccountSelectors.selectAccount)()?.answersThroughAgent ?? false,
    );
    readonly showsTopics = computed(() => showsSoftwareChoice(this.software()));
    readonly allTopicsOff = computed(() => allSoftwareOff(this.excludedConnectionIds(), this.software()));
    readonly topicsOn = computed(() => topicsSwitchedOn(this.excludedConnectionIds(), this.software()));
    readonly promptLabel = softwarePromptLabel;
    /** null: "Standard" - the tenant's settings alone. */
    readonly template = new FormControl<number | null>(null);
    readonly input = new FormControl("", {nonNullable: true});
    private readonly prompt = viewChild.required<ElementRef<HTMLTextAreaElement>>("prompt");
    private readonly promptSize = viewChild.required(CdkTextareaAutosize);
    private readonly messageList = viewChild.required<ElementRef<HTMLElement>>("messageList");
    /** Whether the list scrolls to the newest message as it comes ({@link onMessagesScroll}). */
    private followsLatest = true;
    /** The text sent last, put back into the input if the question fails. */
    private lastSent = "";

    // Signals of the controls, so canSend follows them.
    private readonly inputText = toSignal(this.input.valueChanges, {initialValue: this.input.value});
    readonly modelId = toSignal(this.model.valueChanges, {initialValue: this.model.value});
    readonly selectedModel = computed(() => this.chatModels().find(option => option.id === this.modelId()));
    readonly templateId = toSignal(this.template.valueChanges, {initialValue: this.template.value});
    readonly selectedTemplate = computed(() => this.templates().find(option => option.id === this.templateId()));
    // Not while uploading: the question would go without the files still on their way. A template chosen needs no
    // text: its name is the question then (utils/chat.util.ts).
    readonly canSend = computed(
        () =>
            !this.sending() &&
            !this.uploading() &&
            (this.answersThroughAgent() || this.modelId() !== null) &&
            hasQuestion(this.inputText(), this.templateId()),
    );

    constructor() {
        this.store.dispatch(TenantAiActions.loadModels());
        this.store.dispatch(ChatActions.loadConversations());
        this.store.dispatch(ChatActions.loadTemplates());
        this.store.dispatch(ChatActions.loadSoftwarePrompts());
        this.store.dispatch(ChatActions.loadSoftware());

        effect(() => this.model.setValue(pickChatModel(this.chatModels(), this.model.value ?? this.readLastModel())));
        this.model.valueChanges.pipe(takeUntilDestroyed()).subscribe(id => this.rememberModel(id));

        // Another conversation opens at its newest message.
        effect(() => {
            this.selectedId();
            this.followsLatest = true;
        });
        // Newest message in view - after loading a conversation, a question and its answer; not while the reader has
        // scrolled up during an answer (to reread the question), which would pull them down with every word.
        afterRenderEffect(() => {
            this.messages();
            this.pending();
            if (!this.followsLatest) {
                return;
            }
            const list = this.messageList().nativeElement;
            list.scrollTop = list.scrollHeight;
        });

        // A prompt's text goes into the input - after what is typed there already - for the user to read and send.
        this.actions$.pipe(ofType(ChatActions.fillSoftwarePromptSuccess), takeUntilDestroyed()).subscribe(({text}) => {
            const typed = this.input.value.trim();
            this.setInput(typed ? `${typed}\n\n${text}` : text);
            this.prompt().nativeElement.focus();
        });
        this.actions$
            .pipe(ofType(ChatActions.fillSoftwarePromptFailure), takeUntilDestroyed())
            .subscribe(({error}) => this.showError(this.transloco.translate("chat.snack.error", {error})));
        this.actions$
            .pipe(ofType(ChatActions.decideActionFailure), takeUntilDestroyed())
            .subscribe(({error}) => this.showError(this.transloco.translate("chat.snack.error", {error})));
        this.actions$
            .pipe(ofType(ChatActions.stoppedAnswerFailed), takeUntilDestroyed())
            .subscribe(({error}) =>
                this.showError(this.transloco.translate("chat.snack.stoppedAnswerFailed", {error})),
            );
        this.actions$.pipe(ofType(ChatActions.sendMessageFailure), takeUntilDestroyed()).subscribe(({error}) => {
            if (!this.input.value.trim()) {
                this.setInput(this.lastSent);
            }
            this.showError(this.transloco.translate("chat.snack.error", {error}));
        });
        this.actions$.pipe(ofType(ChatActions.uploadFilesSuccess), takeUntilDestroyed()).subscribe(({response}) => {
            const count = response.files.length;
            const key = count === 1 ? "chat.snack.attached.one" : "chat.snack.attached.other";
            this.snackBar.open(this.transloco.translate(key, {count}), this.transloco.translate("common.ok"), {
                duration: 3000,
            });
        });
        this.actions$
            .pipe(
                ofType(ChatActions.uploadFilesFailure, ChatActions.deleteFileFailure, ChatActions.downloadFileFailure),
                takeUntilDestroyed(),
            )
            .subscribe(({error}) => this.showError(this.transloco.translate("chat.snack.error", {error})));
        this.actions$
            .pipe(ofType(ChatActions.deleteConversationFailure), takeUntilDestroyed())
            .subscribe(({error}) => this.showError(this.transloco.translate("chat.snack.deleteFailed", {error})));
        this.actions$
            .pipe(ofType(ChatActions.deleteConversationSuccess), takeUntilDestroyed())
            .subscribe(() => this.showInfo("chat.snack.deleted", 3000));
    }

    newChat(): void {
        this.listOpen.set(false);
        this.store.dispatch(ChatActions.newConversation());
    }

    open(conversation: Conversation): void {
        this.listOpen.set(false);
        this.store.dispatch(ChatActions.selectConversation({id: conversation.id}));
        // The conversation goes on with the template of its last question.
        this.template.setValue(pickTemplate(this.templates(), conversation.templateId));
    }

    /**
     * Puts a text into the input field. The field grows with typing only - a text set from here would stay hidden
     * behind its first two lines, so it is measured again.
     */
    private setInput(text: string): void {
        this.input.setValue(text);
        this.promptSize().resizeToFitContent(true);
    }

    /** A prompt of the connected software: its form, then its text in the input field. */
    openPrompt(prompt: SoftwarePrompt): void {
        this.dialogSave.open<ChatPromptDialogComponent, SoftwarePrompt, Record<string, string>>(
            ChatPromptDialogComponent,
            {data: prompt},
            {
                action: values => ChatActions.fillSoftwarePrompt({promptId: prompt.id, values}),
                success: [ChatActions.fillSoftwarePromptSuccess],
                failure: ChatActions.fillSoftwarePromptFailure,
            },
        );
    }

    /** A template card of the empty chat: the next question goes with it. */
    useTemplate(templateId: number): void {
        this.template.setValue(templateId);
        this.prompt().nativeElement.focus();
    }

    /** The answer as written (Markdown) - it pastes as text and keeps its lists and tables readable. */
    copy(content: string): void {
        navigator.clipboard.writeText(content).then(
            () => this.showInfo("chat.snack.answerCopied", 2000),
            () => this.showError(this.transloco.translate("chat.snack.copyAnswerFailed")),
        );
    }

    /** The copy control of a code block in an answer - a span, as the sanitizer of [innerHTML] removes buttons. */
    copyCode(event: Event): void {
        const code = copiedCode(event.target);
        if (code === null) {
            return;
        }
        event.preventDefault();
        navigator.clipboard.writeText(code).then(
            () => this.showInfo("chat.snack.codeCopied", 2000),
            () => this.showError(this.transloco.translate("chat.snack.copyCodeFailed")),
        );
    }

    onEnter(event: Event): void {
        if ((event as KeyboardEvent).shiftKey) {
            return;
        }
        event.preventDefault();
        this.send();
    }

    /** Follows the newest message only while the reader is at it. */
    onMessagesScroll(): void {
        const list = this.messageList().nativeElement;
        this.followsLatest = atLatest(list.scrollTop, list.scrollHeight, list.clientHeight);
    }

    send(): void {
        const modelId = this.model.value;
        const message = questionToSend(this.input.value, this.selectedTemplate());
        const agent = this.answersThroughAgent();
        if (!this.canSend() || (modelId === null && !agent)) {
            return;
        }
        this.followsLatest = true;
        this.lastSent = this.input.value;
        this.setInput("");
        this.store.dispatch(
            ChatActions.sendMessage({
                request: {
                    conversationId: this.selectedId(),
                    modelId: agent ? null : modelId,
                    message,
                    templateId: agent ? null : this.template.value,
                    ...choiceToSend(this.excludedConnectionIds(), this.software()),
                },
            }),
        );
    }

    toggleTopic(connectionId: ChoiceId): void {
        this.store.dispatch(ChatActions.toggleSoftware({connectionId}));
    }

    onFilesChosen(input: HTMLInputElement): void {
        const files = Array.from(input.files ?? []);
        // Cleared so choosing the same file again fires (change) again.
        input.value = "";
        if (files.length === 0) {
            return;
        }
        const error = chatUploadError(files, this.locale());
        if (error) {
            this.showError(this.transloco.translate(error.key, error.params));
            return;
        }
        this.store.dispatch(ChatActions.uploadFiles({conversationId: this.selectedId(), files}));
    }

    /**
     * Runs or cancels the call the answer stopped at; the model goes on in the open conversation. Not while any answer
     * streams - also another conversation's: both would stream into the one answer shown.
     */
    decide(action: PendingAction, decision: ActionDecision): void {
        const conversationId = this.selectedId();
        if (conversationId === null || this.sending()) {
            return;
        }
        this.store.dispatch(ChatActions.decideAction({conversationId, actionId: action.id, decision}));
    }

    readonly isCreatedImage = isCreatedImage;
    readonly isCreatedVideo = isCreatedVideo;

    download(file: ChatFile): void {
        this.store.dispatch(ChatActions.downloadFile({file}));
    }

    confirmRemoveFile(file: ChatFile): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("chat.confirm.removeFile", {name: file.fileName}),
                    confirmLabel: this.transloco.translate("common.remove"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(ChatActions.deleteFile({id: file.id})));
    }

    /** Stops showing the answer; the backend still finishes and stores it. */
    stop(): void {
        this.store.dispatch(ChatActions.stopStreaming());
    }

    confirmDelete(conversation: Conversation, event: Event): void {
        event.stopPropagation();
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("chat.confirm.deleteChat", {title: conversation.title}),
                    confirmLabel: this.transloco.translate("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(ChatActions.deleteConversation({id: conversation.id})));
    }

    startResize(event: PointerEvent): void {
        const splitter = event.target as HTMLElement;
        splitter.setPointerCapture(event.pointerId);
        splitter.classList.add("dragging");
        this.drag = {startX: event.clientX, startWidth: this.listWidth()};
        event.preventDefault();
    }

    resize(event: PointerEvent): void {
        if (this.drag) {
            this.listWidth.set(clampListWidth(this.drag.startWidth + event.clientX - this.drag.startX));
        }
    }

    endResize(event: PointerEvent): void {
        if (!this.drag) {
            return;
        }
        (event.target as HTMLElement).classList.remove("dragging");
        this.drag = null;
        this.storeListWidth(this.listWidth());
    }

    resetListWidth(): void {
        this.listWidth.set(LIST_WIDTH.default);
        this.storeListWidth(LIST_WIDTH.default);
    }

    private readListWidth(): number {
        try {
            const stored = localStorage.getItem(LIST_WIDTH_KEY);
            return stored === null ? LIST_WIDTH.default : clampListWidth(Number(stored));
        } catch {
            return LIST_WIDTH.default;
        }
    }

    private storeListWidth(width: number): void {
        try {
            localStorage.setItem(LIST_WIDTH_KEY, String(width));
        } catch {
            // Storage disabled: the width just isn't remembered.
        }
    }

    private readLastModel(): number | null {
        try {
            const stored = localStorage.getItem(MODEL_KEY);
            return stored === null ? null : Number(stored);
        } catch {
            return null;
        }
    }

    private rememberModel(id: number | null): void {
        try {
            if (id !== null) {
                localStorage.setItem(MODEL_KEY, String(id));
            }
        } catch {
            // Storage disabled: the choice just isn't remembered.
        }
    }

    /** The texts of the code blocks' copy controls in the active language - read through its signal, so a computed follows a switch. */
    private copyLabels(): CopyCodeLabels {
        this.translation();
        return {
            copy: this.transloco.translate("common.copy"),
            title: this.transloco.translate("chat.answer.copyCode"),
        };
    }

    private showInfo(key: string, duration: number): void {
        this.snackBar.open(this.transloco.translate(key), this.transloco.translate("common.ok"), {duration});
    }

    private showError(message: string): void {
        this.snackBar.open(message, this.transloco.translate("common.ok"), {
            duration: 10000,
            panelClass: "error-snackbar",
        });
    }
}
