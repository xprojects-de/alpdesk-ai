import dagre from "@dagrejs/dagre";
import {GraphEdge} from "./task.util";

export interface Position {
    x: number;
    y: number;
}

/**
 * Where each node of a task's graph stands, top to bottom (dagre). The edges back are left out of the layout, so the
 * main path stays straight and they arc beside it.
 */
export function layoutGraph(
    nodes: {id: string; width: number; height: number}[],
    edges: GraphEdge[],
): Map<string, Position> {
    const graph = new dagre.graphlib.Graph();
    graph.setGraph({rankdir: "TB", nodesep: 40, ranksep: 56, marginx: 16, marginy: 16});
    graph.setDefaultEdgeLabel(() => ({}));
    for (const node of nodes) {
        graph.setNode(node.id, {width: node.width, height: node.height});
    }
    for (const edge of edges) {
        if (!edge.back) {
            graph.setEdge(edge.from, edge.to);
        }
    }
    dagre.layout(graph);
    const positions = new Map<string, Position>();
    for (const node of nodes) {
        const placed = graph.node(node.id);
        positions.set(node.id, {x: placed.x - node.width / 2, y: placed.y - node.height / 2});
    }
    return positions;
}
