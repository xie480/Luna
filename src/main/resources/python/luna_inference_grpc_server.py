import json
import logging
from concurrent import futures

import grpc
from sentence_transformers import SentenceTransformer, CrossEncoder

import luna_inference_pb2
import luna_inference_pb2_grpc

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("luna-inference-grpc")


class LunaInferenceService(luna_inference_pb2_grpc.LunaInferenceServiceServicer):
    def __init__(self, embedding_model_path: str, rerank_model_path: str):
        logger.info("加载 embedding 模型: %s", embedding_model_path)
        self.embedding_model = SentenceTransformer(embedding_model_path)
        logger.info("加载 rerank 模型: %s", rerank_model_path)
        self.rerank_model = CrossEncoder(rerank_model_path, max_length=1024, trust_remote_code=True)
        logger.info("模型加载完成，服务可用")

    def Embedding(self, request, context):
        try:
            text = request.text or ""
            if not text.strip():
                return luna_inference_pb2.EmbeddingResponse(
                    vector_json="[]",
                    success=False,
                    error_message="text is blank"
                )
            vec = self.embedding_model.encode(text).tolist()
            return luna_inference_pb2.EmbeddingResponse(
                vector_json=json.dumps(vec, ensure_ascii=False),
                success=True,
                error_message=""
            )
        except Exception as e:
            logger.exception("Embedding 调用失败")
            return luna_inference_pb2.EmbeddingResponse(
                vector_json="[]",
                success=False,
                error_message=str(e)
            )

    def Rerank(self, request, context):
        try:
            query = request.query or ""
            docs = list(request.documents)
            if not query.strip():
                return luna_inference_pb2.RerankResponse(
                    scores=[],
                    success=False,
                    error_message="query is blank"
                )
            if not docs:
                return luna_inference_pb2.RerankResponse(
                    scores=[],
                    success=True,
                    error_message=""
                )
            pairs = [[query, d] for d in docs]
            scores = self.rerank_model.predict(pairs).tolist()
            return luna_inference_pb2.RerankResponse(
                scores=scores,
                success=True,
                error_message=""
            )
        except Exception as e:
            logger.exception("Rerank 调用失败")
            return luna_inference_pb2.RerankResponse(
                scores=[],
                success=False,
                error_message=str(e)
            )


def serve(
        host: str = "127.0.0.1",
        port: int = 50051,
        embedding_model_path: str = "",
        rerank_model_path: str = ""
):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    luna_inference_pb2_grpc.add_LunaInferenceServiceServicer_to_server(
        LunaInferenceService(embedding_model_path, rerank_model_path),
        server
    )
    bind_addr = f"{host}:{port}"
    server.add_insecure_port(bind_addr)
    server.start()
    logger.info("Luna Inference gRPC 服务已启动: %s", bind_addr)
    server.wait_for_termination()


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=50051)
    parser.add_argument("--embedding-model-path", required=True)
    parser.add_argument("--rerank-model-path", required=True)
    args = parser.parse_args()

    serve(
        host=args.host,
        port=args.port,
        embedding_model_path=args.embedding_model_path,
        rerank_model_path=args.rerank_model_path
    )
