"""The sandbox's HTTP server (docs/architecture/code-execution.md) on Starlette and uvicorn - the session's processes
and files are sessions.py's. It runs as PID 1 of its container, not dumpable, with one uvicorn worker: the one session
lives in this process.

HTTP with the tenant's token (Authorization: Bearer ...), every error as {"error": ...}:
    GET    /health                  {"ok": true, "busy": bool, "video": bool, "extras": null | text,
                                     "unusable": null | why} - no token needed, nothing of a session in it; "video":
                                     the image examines videos (ffmpeg, RTMPose - Dockerfile target "video"); "extras":
                                     the README of an image built on ours (/opt/extras/README.md, at most 16 KB);
                                     "unusable": the instance takes no session until it is started again (then busy
                                     too), and why - its log says more
    POST   /sessions                {"files": [{"name", "content" (base64)}]} -> 201 {"session"}; 409 while busy
    POST   /sessions/<id>/python    {"code"}    -> {"output", "error"}   - one namespace for the whole session
    POST   /sessions/<id>/shell     {"command"} -> {"output", "exitCode"}
    PUT    /sessions/<id>/files?name=<name>  the file's bytes -> 201 {"name"} - into data/, streamed to disk (a video)
    GET    /sessions/<id>/files?path=<path>  -> the bytes of a file the code wrote; 400 for no file below the session
    DELETE /sessions/<id>           -> 204
"""

import ctypes
import hmac
import json
import os
import sys
import threading

import uvicorn
from starlette.applications import Starlette
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException
from starlette.requests import ClientDisconnect
from starlette.responses import JSONResponse, Response, StreamingResponse
from starlette.routing import Route

# -I leaves the script's folder off sys.path; sessions.py lies next to this file.
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
import sessions  # noqa: E402

TOKEN = os.environ.get("SANDBOX_TOKEN", "")
PORT = int(os.environ.get("SANDBOX_PORT", "8080"))
REQUEST_BYTES = int(os.environ.get("SANDBOX_REQUEST_MB", "256")) * 1024 * 1024
# Dated lines like the app's; no access log - nothing of a session belongs in a log.
LOG_CONFIG = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {"dated": {"format": "%(asctime)s %(message)s", "datefmt": "%Y-%m-%d %H:%M:%S"}},
    "handlers": {"stderr": {"class": "logging.StreamHandler", "formatter": "dated", "stream": "ext://sys.stderr"}},
    "loggers": {"uvicorn": {"handlers": ["stderr"], "level": "WARNING"}},
}


async def health(request):
    return JSONResponse({"ok": True, "busy": sessions.busy(), "video": sessions.VIDEO, "extras": sessions.EXTRAS,
                         "unusable": sessions.unusable})


async def create(request):
    body = await checked_body(request)
    try:
        session_id = await run_in_threadpool(sessions.open_session, body.get("files") or [])
    except sessions.Busy:
        raise HTTPException(409, "busy")
    except (ValueError, TypeError, OSError) as e:
        raise HTTPException(400, f"the files could not be written: {e}")
    return JSONResponse({"session": session_id}, 201)


async def python(request):
    body = await checked_body(request)
    return await run(request, "python", str(body.get("code", "")))


async def shell(request):
    body = await checked_body(request)
    return await run(request, "shell", str(body.get("command", "")))


async def put_file(request):
    """One file into the running session's data/, written to disk as it arrives - a video does not fit a JSON body."""
    require_token(request)
    length = declared_length(request, sessions.FILE_MB * 1024 * 1024)
    current = sessions.find(request.path_params["session_id"])
    if current is None:
        raise HTTPException(404, "no such session")
    try:
        target = await run_in_threadpool(sessions.upload_target, current, request.query_params.get("name", ""))
    except (ValueError, OSError) as e:
        raise HTTPException(400, f"the file could not be written: {e}")
    try:
        written = await write_body(request, target, length)
    except (OSError, ClientDisconnect, HTTPException) as e:
        await run_in_threadpool(remove, target)
        if isinstance(e, OSError):
            raise HTTPException(400, f"the file could not be written: {e.strerror}")
        raise
    if written != length:
        await run_in_threadpool(remove, target)
        raise HTTPException(400, "the body is shorter than its length")
    if await run_in_threadpool(sessions.uploaded_into_ended, current, target):
        raise HTTPException(404, "no such session")
    return JSONResponse({"name": os.path.relpath(target, os.path.join(current.directory, "data"))}, 201)


async def write_body(request, target, length):
    written = 0
    with open(target, "wb") as out:
        async for chunk in request.stream():
            written += len(chunk)
            if written > length:
                raise HTTPException(400, "the body is longer than its length")
            await run_in_threadpool(out.write, chunk)
    return written


def remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


async def get_file(request):
    """A file the session's code wrote (a frame, a clip) - streamed, never whole in memory."""
    require_token(request)
    current = sessions.find(request.path_params["session_id"])
    if current is None:
        raise HTTPException(404, "no such session")
    opened = await run_in_threadpool(sessions.open_file, current, request.query_params.get("path", ""))
    if opened is None:
        raise HTTPException(400, "no such file in the session")
    descriptor, size = opened
    return StreamingResponse(read_file(descriptor, size), media_type="application/octet-stream",
                             headers={"Content-Length": str(size)})


def read_file(descriptor, size):
    """The file's first size bytes - what Content-Length promised, should the code still be writing it."""
    try:
        left = size
        while left > 0:
            chunk = os.read(descriptor, min(left, 1024 * 1024))
            if not chunk:
                break
            left -= len(chunk)
            yield chunk
    finally:
        os.close(descriptor)


async def delete(request):
    require_token(request)
    if not await run_in_threadpool(sessions.end_session, request.path_params["session_id"]):
        raise HTTPException(404, "no such session")
    return Response(status_code=204)


async def run(request, kind, text):
    result = await run_in_threadpool(sessions.run, request.path_params["session_id"], kind, text)
    if result is None:
        raise HTTPException(404, "no such session")
    return JSONResponse(result)


async def checked_body(request):
    """The request's JSON object, after its token. uvicorn limits no body: a larger one is refused before it is read,
    and one without a length (chunked) at all - the app always sends one."""
    require_token(request)
    declared_length(request, REQUEST_BYTES)
    try:
        body = json.loads(await request.body() or b"{}")
    except ValueError:
        raise HTTPException(400, "not JSON")
    if not isinstance(body, dict):
        raise HTTPException(400, "not a JSON object")
    return body


def declared_length(request, limit):
    """The body's length as the request declares it - 411 without one, 413 above the limit, before anything is read."""
    length = request.headers.get("content-length")
    if length is None:
        raise HTTPException(411, "length required")
    if not length.isdigit():
        raise HTTPException(400, "invalid length")
    if int(length) > limit:
        raise HTTPException(413, "request too large")
    return int(length)


def require_token(request):
    given = request.headers.get("authorization", "")
    if not hmac.compare_digest(given.encode(), f"Bearer {TOKEN}".encode()):
        raise HTTPException(401, "unauthorized")


async def error(request, exc):
    return JSONResponse({"error": exc.detail}, exc.status_code)


app = Starlette(
    routes=[
        Route("/health", health),
        Route("/sessions", create, methods=["POST"]),
        Route("/sessions/{session_id}/python", python, methods=["POST"]),
        Route("/sessions/{session_id}/shell", shell, methods=["POST"]),
        Route("/sessions/{session_id}/files", put_file, methods=["PUT"]),
        Route("/sessions/{session_id}/files", get_file, methods=["GET"]),
        Route("/sessions/{session_id}", delete, methods=["DELETE"]),
    ],
    exception_handlers={HTTPException: error},
)


def main():
    if len(TOKEN) < 32:
        sys.exit("SANDBOX_TOKEN must be set, at least 32 characters")
    # Not dumpable: a program of a session (same user) can then read neither this process's memory nor its
    # environment (the token) through /proc.
    ctypes.CDLL(None).prctl(4, 0, 0, 0, 0)  # PR_SET_DUMPABLE
    os.makedirs(sessions.WORK, exist_ok=True)
    # A container restarted mid-session (the platform killed it over its memory) starts on what that session left:
    # Kubernetes keeps emptyDir and /dev/shm across container restarts - so the next session finds nothing.
    if not sessions.empty_all():
        sessions.refuse_sessions("a cleanup left files behind")
    # Without signals no session could be ended (sessions.signals_allowed): said now, in the log and in /health, not
    # after the first answer.
    if not sessions.signals_allowed():
        sessions.refuse_sessions("this container may not send signals - the host's security profile denies them "
                                 "(AppArmor? see README.md \"Code execution\")")
    sessions.EXTRAS = sessions.read_extras()
    if sessions.EXTRAS is not None:
        sessions.log(f"own programs described by {sessions.EXTRAS_FILE} ({len(sessions.EXTRAS)} characters)")
    threading.Thread(target=sessions.reaper, daemon=True).start()
    sessions.log(f"sandbox listening on port {PORT}")
    uvicorn.run(app, host="0.0.0.0", port=PORT, access_log=False, server_header=False, date_header=False,
                log_config=LOG_CONFIG)


if __name__ == "__main__":
    main()
