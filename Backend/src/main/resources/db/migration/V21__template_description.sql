-- What a template is for, said to whoever chooses it from outside (docs/architecture/ai-features.md "Assistant settings
-- and templates"): the A2A card's skill, the MCP server's prompt and list_templates, GET /api/v1/templates. Not its
-- instructions - those stay with the model. NULL: none written, the generic sentence is shown as before.
ALTER TABLE chat_template ADD COLUMN description TEXT;
