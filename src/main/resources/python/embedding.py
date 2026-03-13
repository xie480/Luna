import sys
import json
from sentence_transformers import SentenceTransformer

def get_embedding(model_path, text):
    # 模型路径由参数传入
    model = SentenceTransformer(model_path)
    vec = model.encode(text).tolist()
    return vec

if __name__ == "__main__":
    # 参数顺序: 脚本路径, 模型路径, 文本
    if len(sys.argv) < 3:
        print("[]")
        sys.exit(0)

    model_path = sys.argv[1]
    text = sys.argv[2]

    try:
        vector = get_embedding(model_path, text)
        print(json.dumps(vector, ensure_ascii=False))
    except Exception as e:
        # 发生异常时打印空数组，避免Java端解析错误
        print("[]")
