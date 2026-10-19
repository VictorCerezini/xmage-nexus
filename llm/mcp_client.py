"""Controller-only JSON-RPC requests to the loopback game bridge."""

import json
import urllib.request

from llm.game_tool_gateway import validate_loopback_url


def rpc(url, method, params=None, ident=1):
    validate_loopback_url(url)
    payload = {"jsonrpc": "2.0", "method": method, "id": ident}
    if params is not None:
        payload["params"] = params
    req = urllib.request.Request(  # noqa: S310 - URL restricted to loopback HTTP above
        url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=180) as response:
        return json.loads(response.read())
