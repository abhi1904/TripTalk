# TripTalk: demo video script (about 2.5 minutes)

**Setup before recording:** backend running, app open on the emulator or phone, audio
capture on (screen recorder with system and mic audio). Do one practice run first so the
first cold start doesn't slow you down. Wear headphones only if the recorder can still
capture the app's audio. Otherwise use the speaker in a quiet room.

---

### 0:00–0:20 · The problem (face or slides)
> "Planning a trip means twenty browser tabs, and when you're actually travelling, at an
> airport or on a bus with your bags, you can't type. Voice is the most natural way to ask
> for travel help, but most AI travel tools are still text chats."

### 0:20–0:30 · Introduce TripTalk
> "This is TripTalk, a voice travel planner. It's built on Agora's Conversational AI Engine,
> with Gemini as the brain. You just talk to it."

*Show the app home screen: the title, the Start Talking button and the example prompts.*

### 0:30–1:40 · Live demo (the core, don't cut it)
1. Tap **Start Talking**. Point out the status: "Calling your travel planner…", then
   "Listening". The agent greets you.
2. **You:** "I want to plan a three-day trip to Goa."
3. Answer its follow-ups naturally: "Two friends, mid-range budget, we love beaches and food."
4. Let it describe Day 1. **Interrupt it mid-sentence:** "Actually, can we skip the
   nightclubs and do something more relaxed?" (This shows barge-in, which is the wow moment.)
5. Quick-fire question: "Do I need to carry cash there?"
6. Tap **End Call**.

### 1:40–2:10 · How it works (architecture slide or README diagram)
> "Under the hood, the app joins an Agora RTC channel. Our FastAPI backend creates
> the token and calls Agora's Conversational AI Engine, which drops an agent into the
> channel. That agent handles speech recognition, turn detection and interruptions, sends the
> conversation to Gemini, and speaks back with neural text-to-speech, all in real time.
> The whole backend is about 150 lines of code."

### 2:10–2:30 · What's next and close
> "Next, we're adding a live transcript with itinerary cards, a GPS-aware tour-guide mode,
> and live translation for talking to locals. TripTalk: plan your trip just by talking."

---

**Tips**
- Keep your answers short so the agent does most of the talking.
- If the agent gives a long answer, interrupting it is good. It shows off the tech.
- Record two takes and use the smoother one.
