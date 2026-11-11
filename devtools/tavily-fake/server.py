"""Answers like Tavily's POST /search with invented pages (README.md) - for a test stack whose app has
APP_WEB_SEARCH_ADDRESS pointing here. Standard library only. Every search is logged as one JSON line with its query
(never the key): `podman logs <container>` shows what the app sent out.
"""
import hashlib
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("TAVILY_FAKE_PORT", "8000"))
# In every page: an answer that cites it came from the web search, never from the model's own knowledge.
MARKER = "WEB-4242"


def pages(query: str, count: int) -> list[dict]:
    slug = hashlib.sha256(query.encode()).hexdigest()[:8]
    return [{"title": f"{query} - Fachportal Beispiel ({number})",
             "url": f"https://example.com/{slug}/{number}",
             "content": f"Stand 1. November 2026 (Quelle {MARKER}-{number}): Zu \"{query}\" gilt nach dem Fachportal "
                        f"Beispiel ab 2027 eine neue Regel - die Prüffrist verkürzt sich von 24 auf 18 Monate, "
                        f"Übergangszeit bis 30. Juni 2027.",
             "score": round(0.9 - number / 10, 2)}
            for number in range(1, count + 1)]


class Handler(BaseHTTPRequestHandler):

    def do_POST(self):
        if self.path.rstrip("/") != "/search":
            return self.answer(404, {"detail": {"error": "Not found"}})
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        key = body.get("api_key") or self.headers.get("Authorization", "").removeprefix("Bearer ")
        query = body.get("query", "")
        print(json.dumps({"search": query, "depth": body.get("search_depth"), "rawContent":
                          bool(body.get("include_raw_content")), "keyGiven": bool(key)}, ensure_ascii=False), flush=True)
        if not key:
            return self.answer(401, {"detail": {"error": "Unauthorized: missing or invalid API key."}})
        self.answer(200, {"query": query, "results": pages(query, min(int(body.get("max_results") or 5), 5))})

    def answer(self, status: int, body: dict):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"Tavily fake on :{PORT}", flush=True)
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()
