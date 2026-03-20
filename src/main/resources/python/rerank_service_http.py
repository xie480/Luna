from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import CrossEncoder
from typing import List
import uvicorn

app = FastAPI()

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

class RerankResponse(BaseModel):
    scores: List[float]
    success: bool
    error_message: str

model = None

@app.on_event("startup")
def startup_event():
    global model
    model_path = app.state.rerank_model_path
    model = CrossEncoder(model_path, max_length=1024, trust_remote_code=True)

@app.post("/rerank")
def rerank(req: RerankRequest):
    try:
        query = (req.query or "").strip()
        docs = req.documents or []
        if not query:
            return RerankResponse(scores=[], success=False, error_message="query is blank")
        if not docs:
            return RerankResponse(scores=[], success=True, error_message="")
        pairs = [[query, d] for d in docs]
        scores = model.predict(pairs).tolist()
        return RerankResponse(scores=scores, success=True, error_message="")
    except Exception as e:
        return RerankResponse(scores=[], success=False, error_message=str(e))

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--rerank-model-path", required=True)
    args = parser.parse_args()

    app.state.rerank_model_path = args.rerank_model_path
    uvicorn.run(app, host=args.host, port=args.port)
