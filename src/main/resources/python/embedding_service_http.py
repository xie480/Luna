import json
from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import uvicorn

app = FastAPI()

class EmbeddingRequest(BaseModel):
    text: str

class EmbeddingResponse(BaseModel):
    vector_json: str
    success: bool
    error_message: str

model = None

@app.on_event("startup")
def startup_event():
    global model
    model_path = app.state.embedding_model_path
    model = SentenceTransformer(model_path)

@app.post("/embedding")
def embedding(req: EmbeddingRequest):
    try:
        text = (req.text or "").strip()
        if not text:
            return EmbeddingResponse(vector_json="[]", success=False, error_message="text is blank")
        vec = model.encode(text).tolist()
        return EmbeddingResponse(vector_json=json.dumps(vec, ensure_ascii=False), success=True, error_message="")
    except Exception as e:
        return EmbeddingResponse(vector_json="[]", success=False, error_message=str(e))

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--embedding-model-path", required=True)
    args = parser.parse_args()

    app.state.embedding_model_path = args.embedding_model_path
    uvicorn.run(app, host=args.host, port=args.port)
