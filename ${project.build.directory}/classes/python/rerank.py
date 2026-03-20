import sys
import json
import traceback
# 需確保 Python 環境中已安裝 sentence-transformers
from sentence_transformers import CrossEncoder

def get_scores(model_path, query, documents):
    # 加載 CrossEncoder 模型
    # max_length 根據模型限制設定，bge-reranker-v2-m3 支持較長上下文，這裡設為 1024 以平衡性能
    model = CrossEncoder(model_path, max_length=1024, trust_remote_code=True)
    
    # 構造 (query, doc) 對
    pairs = [[query, doc] for doc in documents]
    
    # 預測分數，返回 numpy array
    scores = model.predict(pairs)
    
    # 轉換為 list 返回
    return scores.tolist()

if __name__ == "__main__":
    # 參數: 腳本路徑, 模型路徑
    # 注意：Query 和 Documents 通過 Stdin 傳入 JSON
    if len(sys.argv) < 2:
        sys.stderr.write("Error: Missing model_path argument. Usage: python rerank.py <model_path>\n")
        sys.exit(1)

    model_path = sys.argv[1]

    try:
        # 從標準輸入讀取 JSON 數據
        # 格式: { "query": "...", "documents": ["doc1", "doc2", ...] }
        # 使用 sys.stdin.read() 確保讀取完整輸入
        input_str = sys.stdin.read()
        if not input_str:
             raise ValueError("Empty input from stdin")
             
        input_data = json.loads(input_str)
        
        query = input_data.get("query")
        documents = input_data.get("documents")

        if not query or documents is None:
            raise ValueError("Input JSON must contain 'query' and 'documents' fields.")

        if len(documents) == 0:
            print("[]")
            sys.exit(0)

        scores = get_scores(model_path, query, documents)
        
        # 輸出分數列表 JSON
        print(json.dumps(scores, ensure_ascii=False))
        
    except Exception as e:
        # 將錯誤信息寫入標準錯誤流 (stderr)
        sys.stderr.write(f"Python Rerank Script Error: {str(e)}\n")
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)
