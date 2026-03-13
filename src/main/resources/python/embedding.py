import sys
import json
from sentence_transformers import SentenceTransformer

# 模型只加载一次
model = SentenceTransformer("D:/AI_Models/bge-base-zh-v1.5-model")

def get_embedding(text):
    vec = model.encode(text).tolist()
    return vec

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("[]")
        sys.exit(0)

    text = sys.argv[1]

    vector = get_embedding(text)

    print(json.dumps(vector, ensure_ascii=False))