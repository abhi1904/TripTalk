import os
import asyncio
from dotenv import load_dotenv
from agora_realtime_ai_api.rtc import RtcEngine, RtcConfig
from agora_realtime_ai_api.processors.gemini_processor import GeminiProcessor
from agora_realtime_ai_api.processors.tts.elevenlabs_processor import ElevenLabsProcessor
from agora_realtime_ai_api.bot import RealtimeAIBot

# Load credentials from .env
load_dotenv()

AGORA_APP_ID = os.getenv("AGORA_APP_ID")
AGORA_APP_CERT = os.getenv("AGORA_APP_CERTIFICATE")
GOOGLE_API_KEY = os.getenv("GOOGLE_API_KEY")

async def main():
    # 1. Initialize the Agora Engine for the Backend
    rtc_config = RtcConfig(
        app_id=AGORA_APP_ID,
        app_certificate=AGORA_APP_CERT,
        channel_name="demo_ai",
        uid=100  # The AI's ID in the channel
    )

    # 2. Setup the Brain (Gemini)
    # You can customize the "system_prompt" to tell the AI how to behave
    gemini = GeminiProcessor(
        api_key=GOOGLE_API_KEY,
        model_name="gemini-1.5-flash",
        system_prompt="You are a helpful travel assistant. Keep your answers concise and friendly."
    )

    # 3. Setup the Voice (Using a basic TTS processor)
    # Note: You can also use Google TTS or others here
    tts = ElevenLabsProcessor(api_key="your_optional_elevenlabs_key")

    # 4. Create and Start the Bot
    bot = RealtimeAIBot(
        rtc_config=rtc_config,
        llm_processor=gemini,
        tts_processor=tts
    )

    print("--- AI Agent is starting... joining channel: demo_ai ---")
    await bot.start()

if __name__ == "__main__":
    asyncio.run(main())
