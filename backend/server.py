"""
TripTalk backend.

Hands the Android app an Agora RTC token for a fresh channel, then asks Agora's
Conversational AI Engine to drop a voice agent (ASR -> Gemini -> TTS) into that
channel. Run with:  uvicorn server:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import base64
import os
import random
import time
import uuid

import truststore

# Trust the OS certificate store (needed behind corporate proxies that re-sign HTTPS)
truststore.inject_into_ssl()

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from agora_token.RtcTokenBuilder2 import Role_Publisher, RtcTokenBuilder

load_dotenv()


def env(name: str, default: str | None = None) -> str:
    value = os.getenv(name, default)
    if not value:
        raise RuntimeError(f"Missing {name} in backend/.env")
    return value


APP_ID = env("AGORA_APP_ID")
APP_CERTIFICATE = env("AGORA_APP_CERTIFICATE")
for _name, _value in (("AGORA_APP_ID", APP_ID), ("AGORA_APP_CERTIFICATE", APP_CERTIFICATE)):
    if len(_value) != 32 or any(c not in "0123456789abcdefABCDEF" for c in _value):
        raise RuntimeError(f"{_name} in backend/.env must be the 32-character hex value from Agora Console")
# Optional: without RESTful API keys, REST calls authenticate with an RTC+RTM token instead
CUSTOMER_ID = os.getenv("AGORA_CUSTOMER_ID", "")
CUSTOMER_SECRET = os.getenv("AGORA_CUSTOMER_SECRET", "")
GOOGLE_API_KEY = env("GOOGLE_API_KEY")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
TTS_VENDOR = os.getenv("TTS_VENDOR", "microsoft").lower()

AGENT_UID = 100
TOKEN_TTL_SECONDS = 3600
CONVO_AI_BASE = f"https://api.agora.io/api/conversational-ai-agent/v2/projects/{APP_ID}"

SYSTEM_PROMPT = """You are TripTalk, a friendly voice travel planner.
Your replies are spoken aloud, so:
- Keep each reply to two or three short sentences.
- Never use markdown, bullet points, emojis, or lists with symbols.
- Ask one question at a time.
Your job: help the user plan a trip. Find out the destination, dates or number of days,
budget, who is travelling, and what they enjoy. Then suggest a day-by-day plan, one day
at a time, with practical tips (transport, local food, best time to visit places).
If the user asks a quick travel question (visas, weather, customs, tipping, safety),
answer it directly. If you are not sure about something time-sensitive like prices or
visa rules, say so and suggest checking an official source."""

GREETING = "Hi, I'm TripTalk, your travel planner. Where would you like to go?"


def rtc_token(channel: str, uid: int) -> str:
    return RtcTokenBuilder.build_token_with_uid(
        APP_ID, APP_CERTIFICATE, channel, uid, Role_Publisher, TOKEN_TTL_SECONDS, TOKEN_TTL_SECONDS
    )


def agent_token(channel: str) -> str:
    """RTC+RTM token for the agent; also valid as REST auth for this channel and agent uid."""
    return RtcTokenBuilder.build_token_with_rtm(
        APP_ID, APP_CERTIFICATE, channel, str(AGENT_UID), Role_Publisher, TOKEN_TTL_SECONDS, TOKEN_TTL_SECONDS
    )


def tts_config() -> dict:
    if TTS_VENDOR == "elevenlabs":
        return {
            "vendor": "elevenlabs",
            "params": {
                "key": env("ELEVENLABS_API_KEY"),
                "model_id": os.getenv("ELEVENLABS_MODEL_ID", "eleven_flash_v2_5"),
                "voice_id": env("ELEVENLABS_VOICE_ID"),
            },
        }
    if TTS_VENDOR == "microsoft":
        return {
            "vendor": "microsoft",
            "params": {
                "key": env("AZURE_SPEECH_KEY"),
                "region": env("AZURE_SPEECH_REGION"),
                "voice_name": os.getenv("AZURE_VOICE_NAME", "en-US-AndrewMultilingualNeural"),
            },
        }
    raise RuntimeError(f"Unsupported TTS_VENDOR '{TTS_VENDOR}' (use microsoft or elevenlabs)")


def auth_header(channel: str) -> dict:
    if CUSTOMER_ID and CUSTOMER_SECRET:
        credentials = base64.b64encode(f"{CUSTOMER_ID}:{CUSTOMER_SECRET}".encode()).decode()
        auth = f"Basic {credentials}"
    else:
        auth = f"agora token={agent_token(channel)}"
    return {"Authorization": auth, "Content-Type": "application/json"}


app = FastAPI(title="TripTalk backend")

# agent_id -> channel, so /stop_agent can build a token for token-based REST auth
agent_channels: dict[str, str] = {}


class StartAgentRequest(BaseModel):
    channelName: str
    userUid: int


class StopAgentRequest(BaseModel):
    agentId: str


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/get_config")
def get_config():
    channel = f"triptalk_{uuid.uuid4().hex[:8]}"
    uid = random.randint(1000, 99999)
    return {
        "appId": APP_ID,
        "channel": channel,
        "uid": uid,
        "agentUid": AGENT_UID,
        "rtcToken": rtc_token(channel, uid),
    }


@app.post("/start_agent")
async def start_agent(req: StartAgentRequest):
    body = {
        "name": f"triptalk-{req.channelName}",
        "properties": {
            "channel": req.channelName,
            "token": agent_token(req.channelName),
            "agent_rtc_uid": str(AGENT_UID),
            "remote_rtc_uids": [str(req.userUid)],
            "idle_timeout": 60,
            # Stream user and agent transcripts to the app over the RTC data stream
            "parameters": {"data_channel": "datastream"},
            "asr": {"language": "en-US"},
            "llm": {
                "url": "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                "api_key": GOOGLE_API_KEY,
                "system_messages": [{"role": "system", "content": SYSTEM_PROMPT}],
                "greeting_message": GREETING,
                "failure_message": "Sorry, I lost my train of thought. Could you say that again?",
                "max_history": 32,
                "params": {"model": GEMINI_MODEL},
            },
            "tts": tts_config(),
        },
    }
    async with httpx.AsyncClient(timeout=20) as client:
        resp = await client.post(f"{CONVO_AI_BASE}/join", json=body, headers=auth_header(req.channelName))
    if resp.status_code != 200:
        print(f"Agent start failed ({resp.status_code}): {resp.text}")
        raise HTTPException(status_code=resp.status_code, detail=resp.text)
    data = resp.json()
    agent_id = data.get("agent_id")
    agent_channels[agent_id] = req.channelName
    print(f"Agent {agent_id} joined {req.channelName}")
    return {"agentId": agent_id, "status": data.get("status")}


@app.post("/stop_agent")
async def stop_agent(req: StopAgentRequest):
    channel = agent_channels.pop(req.agentId, "")
    async with httpx.AsyncClient(timeout=20) as client:
        resp = await client.post(
            f"{CONVO_AI_BASE}/agents/{req.agentId}/leave", headers=auth_header(channel)
        )
    if resp.status_code != 200:
        raise HTTPException(status_code=resp.status_code, detail=resp.text)
    return {"stopped": True}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
