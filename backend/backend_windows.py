import os
from fastapi import FastAPI, Request
from dotenv import load_dotenv
import google.generativeai as genai

load_dotenv()
genai.configure(api_key=os.getenv("GOOGLE_API_KEY"))
model = genai.GenerativeModel('gemini-1.5-flash')

app = FastAPI()

@app.post("/chat")
async def chat(request: Request):
    # This receives the text from your App or Agora Cloud
    data = await request.json()
    user_text = data.get("text", "")

    print(f"User said: {user_text}")

    # Get response from Gemini
    response = model.generate_content(user_text)
    ai_text = response.text

    print(f"Gemini says: {ai_text}")

    return {"response": ai_text}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
