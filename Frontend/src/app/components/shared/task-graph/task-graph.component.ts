import {Component, computed, input, output} from "@angular/core";
import {FFlowModule, provideFFlow, withA11y} from "@foblex/flow";
import {TranslocoPipe} from "@jsverse/transloco";
import {MatIconModule} from "@angular/material/icon";
import {TaskStepView} from "../../../models/task.model";
import {layoutGraph} from "../../../utils/task-graph.util";
import {aboutText, graphEdges, stepKind} from "../../../utils/task.util";

const NODE_WIDTH = 220;
const NODE_HEIGHT = 58;
const ICONS: Record<string, string> = {
    read_files: "folder_open",
    read_documents: "description",
    extract: "table_rows",
    write_file: "save",
    call_tool: "api",
    call_agent: "smart_toy",
    agent: "auto_awesome",
    verify: "fact_check",
    send_mail: "mail",
};

/** How a node ended in a run: its state, its rounds and its tokens. */
export interface NodeState {
    state: string;
    rounds: number;
    tokens: number;
    /** A prompt step's pick from its choices in its last round. */
    choice: string | null;
}

/**
 * A task's steps as a graph (docs/architecture/tasks.md, the editor and the run view), read only: one node per step in
 * the colour of its kind, a condition as a chip on its edge, an edge back dashed with its limit. Laid out by dagre top
 * to bottom; Foblex Flow draws it and gives it its keyboard layer.
 */
@Component({
    selector: "app-task-graph",
    imports: [FFlowModule, MatIconModule, TranslocoPipe],
    providers: [provideFFlow(withA11y())],
    template: `
        <f-flow fDraggable (fSelectionChange)="onSelect($event)" [attr.aria-label]="'tasks.graph.label' | transloco">
            <f-canvas fZoom>
                @for (edge of edges(); track edge.id) {
                    <f-connection
                        [fConnectionId]="edge.id"
                        [fSourceId]="edge.back ? edge.from + '-back-out' : edge.from + '-out'"
                        [fTargetId]="edge.back ? edge.to + '-back-in' : edge.to + '-in'"
                        [fType]="edge.back ? 'bezier' : 'segment'"
                        [fBehavior]="'fixed'"
                        [fRadius]="8"
                        [class.back]="edge.back"
                    >
                        <f-connection-marker-arrow type="end" />
                        @if (edge.label) {
                            <span fConnectionContent position="0.5" class="edge-chip">{{ edge.label }}</span>
                        }
                    </f-connection>
                }
                @for (node of nodes(); track node.id) {
                    <div
                        fNode
                        [fNodeId]="node.id"
                        [fNodePosition]="node.position"
                        class="node"
                        [class]="'node kind-' + node.kind"
                        [class.selected]="node.id === selected()"
                        [class.has-error]="errorSteps().includes(node.id)"
                        [attr.aria-label]="node.id"
                    >
                        <div
                            fConnector
                            [fConnectorType]="'target'"
                            [fConnectorId]="node.id + '-in'"
                            class="port top"
                        ></div>
                        <mat-icon>{{ node.icon }}</mat-icon>
                        <span class="text">
                            <b>{{ node.id }}</b>
                            <small>{{ node.about.key | transloco: node.about.params }}</small>
                        </span>
                        @if (node.state; as state) {
                            <span class="state" [class]="'state state-' + state.state">
                                {{ "tasks.stepState." + state.state | transloco }}
                                @if (state.rounds > 1) {
                                    · {{ "tasks.run.rounds" | transloco: {count: state.rounds} }}
                                }
                                @if (state.choice) {
                                    · → {{ state.choice }}
                                }
                            </span>
                        }
                        <div
                            fConnector
                            [fConnectorType]="'source'"
                            [fConnectorId]="node.id + '-out'"
                            class="port bottom"
                        ></div>
                        <div
                            fConnector
                            [fConnectorType]="'source'"
                            [fConnectorId]="node.id + '-back-out'"
                            class="port right-low"
                        ></div>
                        <div
                            fConnector
                            [fConnectorType]="'target'"
                            [fConnectorId]="node.id + '-back-in'"
                            class="port right-high"
                        ></div>
                    </div>
                }
            </f-canvas>
        </f-flow>
        <div class="legend">
            <span><i class="kind-fixed"></i>{{ "tasks.graph.fixed" | transloco }}</span>
            <span><i class="kind-agent"></i>{{ "tasks.graph.agent" | transloco }}</span>
            <span><i class="kind-output"></i>{{ "tasks.graph.output" | transloco }}</span>
            <span><i class="back"></i>{{ "tasks.graph.back" | transloco }}</span>
        </div>
    `,
    styles: [
        `
            :host {
                display: flex;
                flex-direction: column;
                min-height: 600px;
            }

            f-flow {
                display: block;
                flex: 1;
                min-height: 560px;
            }

            .node {
                box-sizing: border-box;
                width: 220px;
                min-height: 58px;
                display: flex;
                align-items: center;
                gap: 10px;
                padding: 6px 12px;
                border-radius: 10px;
                border: 1px solid;
                color: var(--app-text);
                position: relative;
                cursor: pointer;
            }

            .node mat-icon {
                font-size: 18px;
                width: 18px;
                height: 18px;
            }

            .text {
                display: flex;
                flex-direction: column;
                min-width: 0;
            }

            .text small {
                color: var(--app-text-2);
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }

            .node.selected {
                outline: 2px solid var(--app-brand);
                outline-offset: 2px;
            }

            .node.has-error {
                outline: 2px solid var(--app-err);
                outline-offset: 2px;
            }

            .kind-fixed {
                background: var(--app-flow-step);
                border-color: var(--app-flow-step-line);
            }

            .kind-agent {
                background: var(--app-flow-agent);
                border-color: var(--app-flow-agent-line);
            }

            .kind-output {
                background: var(--app-flow-output);
                border-color: var(--app-flow-output-line);
            }

            .state {
                position: absolute;
                top: -10px;
                right: 8px;
                font-size: 11px;
                padding: 0 6px;
                border-radius: 6px;
                background: var(--app-surface);
                border: 1px solid var(--app-border-strong);
                color: var(--app-text-2);
            }

            .state-DONE {
                color: var(--app-ok);
            }

            .state-FAILED {
                color: var(--app-err);
            }

            .edge-chip {
                display: inline-block;
                font-size: 11px;
                padding: 2px 7px;
                border-radius: 6px;
                background: var(--app-surface);
                border: 1px solid var(--app-border-strong);
                color: var(--app-text-2);
                white-space: nowrap;
            }

            /* The graph is read only: its connectors only anchor the lines. */
            .port {
                position: absolute;
                width: 8px;
                height: 8px;
                opacity: 0;
                pointer-events: none;
            }

            .port.top {
                top: -4px;
                left: calc(50% - 4px);
            }

            .port.bottom {
                bottom: -4px;
                left: calc(50% - 4px);
            }

            .port.right-low {
                right: -4px;
                bottom: 12px;
            }

            .port.right-high {
                right: -4px;
                top: 12px;
            }

            .legend {
                display: flex;
                flex-wrap: wrap;
                gap: 14px;
                padding: 8px 4px 0;
                font-size: 12px;
                color: var(--app-text-2);
            }

            .legend i {
                display: inline-block;
                width: 10px;
                height: 10px;
                border-radius: 3px;
                margin-right: 5px;
                border: 1px solid;
                vertical-align: -1px;
            }

            .legend i.back {
                border: 0;
                border-top: 2px dashed var(--app-text-3);
                border-radius: 0;
                height: 0;
                width: 16px;
                vertical-align: 3px;
            }
        `,
    ],
})
export class TaskGraphComponent {
    readonly steps = input.required<TaskStepView[]>();
    /** In the run view: how each step ended. */
    readonly states = input<Map<string, NodeState>>(new Map());
    /** Steps with a mistake in their lines: marked red. */
    readonly errorSteps = input<string[]>([]);
    readonly selected = input<string | null>(null);
    readonly stepChosen = output<string>();

    readonly edges = computed(() =>
        graphEdges(this.steps()).map((edge, i) => ({
            ...edge,
            id: `${edge.from}-${edge.to}-${i}`,
            label: edge.back ? `${edge.when ?? ""} · max ${edge.maxRounds}`.replace(/^ · /, "") : edge.when,
        })),
    );

    readonly nodes = computed(() => {
        const steps = this.steps();
        const positions = layoutGraph(
            steps.map(step => ({id: step.id, width: NODE_WIDTH, height: NODE_HEIGHT})),
            graphEdges(steps),
        );
        return steps.map(step => ({
            id: step.id,
            about: aboutText(step),
            kind: stepKind(step.type),
            icon: ICONS[step.type],
            position: positions.get(step.id) ?? {x: 0, y: 0},
            state: this.states().get(step.id) ?? null,
        }));
    });

    onSelect(event: {fNodeIds: string[]}): void {
        if (event.fNodeIds?.length) {
            this.stepChosen.emit(event.fNodeIds[0]);
        }
    }
}
