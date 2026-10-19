"""Read Codex's account model catalog without starting a thread or inference."""

import json
import queue
import subprocess
import threading

from llm import paths, subscription_client

GUI_MODELS = {"gpt-6-astra", "gpt-6-luna", "gpt-6.1-sol"}


class CatalogClient:
    def __enter__(self):
        self.proc = subprocess.Popen(
            [
                paths.settings()["codex_executable"],
                "app-server",
                "--stdio",
                "-c",
                'forced_login_method="chatgpt"',
                "-c",
                'model_provider="openai"',
                "-c",
                "analytics.enabled=false",
            ],
            cwd=paths.PLAYER_CWD,
            env=subscription_client.clean_env(),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        self.messages = queue.Queue()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        self.ident = 0
        try:
            self.call("initialize", {"clientInfo": {"name": "xmage_subscription", "version": "1"}})
            self._send({"method": "initialized", "params": {}})
        except BaseException:
            self.__exit__(None, None, None)
            raise
        return self

    def _read(self):
        for line in self.proc.stdout:
            self.messages.put(json.loads(line))
        self.messages.put(None)

    def _send(self, value):
        self.proc.stdin.write(json.dumps(value) + "\n")
        self.proc.stdin.flush()

    def call(self, method, params):
        self.ident += 1
        self._send({"id": self.ident, "method": method, "params": params})
        while True:
            try:
                message = self.messages.get(timeout=45)
            except queue.Empty as error:
                raise TimeoutError(f"Codex did not respond to {method}") from error
            if message is None:
                raise RuntimeError("Codex model discovery stopped. Check your local Codex ChatGPT sign-in.")
            if message.get("id") == self.ident:
                if "error" in message:
                    raise RuntimeError(f"Codex rejected {method}; check the installed CLI and ChatGPT sign-in")
                return message["result"]

    def __exit__(self, *_):
        self.proc.stdin.close()
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=5)
        self.proc.stdout.close()
        self.reader.join(timeout=2)


def read_catalog(call):
    # Account identity, plan details and credentials are never returned or saved.
    account = call("account/read", {"refreshToken": False})["account"]
    if account is None or account["type"] != "chatgpt":
        raise RuntimeError("Sign into Codex with ChatGPT before opening subscription matches (codex login).")
    models = []
    cursor = None
    seen = set()
    while True:
        params = {"limit": 100, "includeHidden": False, "cursor": cursor}
        page = call("model/list", params)
        for item in page["data"]:
            if item["hidden"] or item["model"] not in GUI_MODELS:
                continue
            efforts = [entry["reasoningEffort"] for entry in item["supportedReasoningEfforts"]]
            if not efforts or item["defaultReasoningEffort"] not in efforts:
                raise ValueError(f"Invalid reasoning capabilities for {item['model']}")
            models.append(
                {
                    "id": item["model"],
                    "name": item["displayName"],
                    "efforts": efforts,
                    "defaultEffort": item["defaultReasoningEffort"],
                    "isDefault": item["isDefault"],
                }
            )
        cursor = page["nextCursor"]
        if cursor is None:
            break
        if cursor in seen:
            raise ValueError("Codex returned a repeated model catalog cursor")
        seen.add(cursor)
    if not models:
        raise RuntimeError("No GPT-6 Astra, GPT-6 Luna or GPT-6.1 Sol model is available through this ChatGPT sign-in")
    return {
        "id": "chatgpt_subscription",
        "playerType": "ChatGPT subscription",
        "playerName": "ChatGPT",
        "auth": "existing Codex ChatGPT login",
        "models": models,
    }


def discover():
    with CatalogClient() as client:
        return read_catalog(client.call)


def validate_selection(catalog, model, reasoning):
    if model not in GUI_MODELS:
        raise ValueError(f"{model} is not offered for model seats; choose GPT-6 Astra, GPT-6 Luna or GPT-6.1 Sol")
    for item in catalog["models"]:
        if item["id"] == model:
            if reasoning not in item["efforts"]:
                raise ValueError(f"{model} does not support reasoning level {reasoning}")
            return
    raise ValueError(f"{model} is not in the available ChatGPT model catalog; refresh the model list")
