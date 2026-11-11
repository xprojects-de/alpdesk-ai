# Tavily fake

Answers like Tavily's `POST /search` with invented pages on `example.com` - for a test stack that should show the web
search without a Tavily account (`devtools/general-chat-test`). The app is pointed at it with
`APP_WEB_SEARCH_ADDRESS` (`app.web-search.address`, docs/architecture/ai-features.md "Web search"); any Tavily key
entered in the app is accepted, none refused but an empty one (401).

Every page carries the marker `WEB-4242` - an answer that holds it took it from the web search. Every search is logged
as one JSON line with its query and depth, never the key: `podman logs <container>` shows exactly what went out.

Standard library only, no image of its own: `python -I server.py` in `python:3.13-slim` (port `TAVILY_FAKE_PORT`,
default 8000). A local helper, not used by the specs (they have `FakeTavilySearch`).
