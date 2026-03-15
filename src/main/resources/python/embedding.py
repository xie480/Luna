import sys
import json
import traceback
from sentence_transformers import SentenceTransformer

def get_embedding(model_path, text):
    # 模型路径由参数传入
    model = SentenceTransformer(model_path)
    vec = model.encode(text).tolist()
    return vec

if __name__ == "__main__":
    # 参数顺序: 脚本路径, 模型路径, 文本
    if len(sys.argv) < 3:
        sys.stderr.write("Error: Missing arguments. Usage: python embedding.py <model_path> <text>\n")
        sys.exit(1)

    model_path = sys.argv[1]
    text = sys.argv[2]

    try:
        vector = get_embedding(model_path, text)
        print(json.dumps(vector, ensure_ascii=False))
    except Exception as e:
        # 将错误信息写入标准错误流 (stderr)，以便 Java 端捕获
        sys.stderr.write(f"Python Script Error: {str(e)}\n")
        traceback.print_exc(file=sys.stderr)
        # 以非 0 状态码退出，表示执行失败
        sys.exit(1)
