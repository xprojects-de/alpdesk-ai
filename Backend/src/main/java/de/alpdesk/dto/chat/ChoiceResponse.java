package de.alpdesk.dto.chat;

/**
 * What a question may leave out (GET /tenant/chat/software, GET /api/v1/software): the connected software - or, for a
 * tenant answering through its A2A agent, the agent's skills (docs/architecture/a2a.md "The skills' choice").
 */
public sealed interface ChoiceResponse permits SoftwareChoiceResponse, SkillChoiceResponse {
}
