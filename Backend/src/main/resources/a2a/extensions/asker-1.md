# Extension urn:alpdesk-ai:a2a:asker:1

Who asks, as this installation tells an A2A agent it connected as software of a company. The entry
`message.metadata["urn:alpdesk-ai:a2a:asker:1"]` of every message it sends (the URI named in the header
`A2A-Extensions` and in `message.extensions`):

```json
{
  "tenant": "Musterfirma",
  "language": "de",
  "user": "m.muster",
  "groups": ["Vertrieb"],
  "idmGroups": ["vertrieb-intern"],
  "skills": ["invoices", "holidays"]
}
```

- `tenant` - the company's name.
- `language` - the asking user's language; for a key's question the company's document language.
- `user` - the user's name (the sign-in name); a question of the company's client software or MCP server names
  `apiKey` or `mcpKey` (the key's name) instead.
- `groups` - the company's user groups the user is in; `idmGroups` - the groups its identity management sent at sign-in
  (empty without one).
- `skills` - the agent's skills the user left on for this question.

Names, never ids; nothing in it is a secret. The agent may trust it as far as it trusts the installation: it comes in a
request authenticated with the connection's key, which only the installation knows.
