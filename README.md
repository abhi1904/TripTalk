# TripTalk: your voice travel planner

**Plan a trip just by talking.** TripTalk is an Android app where you have a natural,
real-time voice conversation with an AI travel planner. Ask it to plan three days in Goa,
check whether you need a visa for Japan, or find out what to eat in Lisbon. It answers out
loud within moments, and you can interrupt it mid-sentence like a real person.

Built on **Agora's Conversational AI Engine** and **Agora RTC**, with **Google Gemini**
doing the thinking.

## Why

Trip planning today means dozens of tabs, reviews and forum threads. It's worst when
you're already travelling: at the airport, on a bus, with your hands full. Voice is the
most natural interface for travel, but most "AI travel assistants" are still text chats.
TripTalk makes planning a conversation.

## What it does

- **One tap to start a live voice call** with an AI travel planner.
- **Conversational trip planning:** it asks about destination, days, budget, company and
  interests one question at a time, then builds the plan day by day.
- **Quick travel answers:** visas, weather, customs, tipping, safety and local food.
- **Real-time and interruptible:** Agora's engine handles turn detection and barge-in,
  so it feels like a phone call rather than a walkie-talkie.
- **Live transcript:** what you said and what TripTalk replied appear as chat bubbles,
  streamed from the agent over the Agora RTC data stream.
- **Speaking indicator and mute:** see when TripTalk is talking, and mute your mic any time.
- **Honest about limits:** for time-sensitive facts like prices or visa rules, it tells
  you to confirm with an official source.

## Architecture

```
┌──────────────┐  1. GET /get_config          ┌──────────────────┐
│ Android app  │ ───────────────────────────▶ │  FastAPI backend │
│ (Kotlin +    │ ◀── appId, channel, token ── │  (backend/       │
│  Compose)    │                              │   server.py)     │
│              │  3. POST /start_agent        │                  │
│              │ ───────────────────────────▶ │                  │
└──────┬───────┘                              └────────┬─────────┘
       │ 2. joins channel                              │ 4. REST: /join
       │    (Agora RTC, mic audio)                     ▼
       │                                   ┌──────────────────────────────┐
       └──────── real-time audio ─────────▶│ Agora Conversational AI      │
                                           │ Engine agent (uid 100)       │
                                           │  ASR → Gemini (LLM) → TTS    │
                                           └──────────────────────────────┘
```

1. The app asks the backend for a new channel name, uid and RTC token.
2. The app joins that Agora channel and publishes the microphone.
3. The app asks the backend to start an agent in that channel.
4. The backend calls Agora's Conversational AI Engine REST API. Agora's cloud agent joins
   the channel, transcribes the user (ASR), sends the conversation to Gemini through its
   OpenAI-compatible endpoint, and speaks the reply with TTS (Azure or ElevenLabs).
5. **End Call** removes the agent (`/stop_agent`) and leaves the channel.

## Tech stack

| Layer | Tech |
|---|---|
| Mobile | Kotlin, Jetpack Compose, Material 3, Agora RTC SDK 4.5.1 |
| Backend | Python, FastAPI, httpx, agora-token-builder |
| Voice AI | Agora Conversational AI Engine (ASR, turn detection, barge-in) |
| LLM | Google Gemini (`gemini-2.5-flash` by default) |
| TTS | Microsoft Azure Speech (default) or ElevenLabs |

## Running it

### 1. Agora console setup
1. Create a project with **App ID + App Certificate** (secured mode).
2. Enable **Conversational AI** for the project.

### 2. Backend
```bash
cd backend
pip install -r requirements.txt
cp .env.example .env      # then fill in the keys
uvicorn server:app --host 0.0.0.0 --port 8000
```
Check it with `curl http://localhost:8000/get_config`.

Required keys: `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE`, `GOOGLE_API_KEY`, plus TTS keys (`AZURE_SPEECH_KEY` and
`AZURE_SPEECH_REGION`, or set `TTS_VENDOR=elevenlabs` with `ELEVENLABS_API_KEY` and
`ELEVENLABS_VOICE_ID`). REST calls to Agora authenticate with an RTC+RTM token built
from the App ID and Certificate. If you'd rather use RESTful API keys, set
`AGORA_CUSTOMER_ID` and `AGORA_CUSTOMER_SECRET` and the backend switches to Basic auth.

### 3. Android app
Open the project in Android Studio and run it on an emulator. The app reaches the backend at
`http://10.0.2.2:8000`. On a physical phone, add `backendUrl=http://<your PC's LAN IP>:8000`
to `gradle.properties`, rebuild, and keep the phone on the same Wi-Fi network. You may
also need to allow port 8000 through Windows Firewall.

## What's next

- Structured itinerary cards generated from the conversation
- GPS-aware "audio tour guide" mode that tells you about what's around you
- Live translation mode for talking with locals
- Tool calls for flight status, transit directions and nearby places
- Save and share itineraries
