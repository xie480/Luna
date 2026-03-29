import argparse
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("embedding-http")

model = None


def json_response(handler: BaseHTTPRequestHandler, status_code: int, payload: dict):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status_code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class EmbeddingHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/embedding":
            json_response(self, 404, {"vector_json": "[]", "success": False, "error_message": "not found"})
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(content_length) if content_length > 0 else b"{}"
            req = json.loads(raw.decode("utf-8"))
            text = (req.get("text") or "").strip()

            if not text:
                json_response(self, 200, {"vector_json": "[]", "success": False, "error_message": "text is blank"})
                return

            vec = model.encode(text).tolist()
            json_response(
                self,
                200,
                {
                    "vector_json": json.dumps(vec, ensure_ascii=False),
                    "success": True,
                    "error_message": ""
                }
            )
        except Exception as e:
            logger.exception("embedding request failed")
            json_response(self, 200, {"vector_json": "[]", "success": False, "error_message": str(e)})

    def log_message(self, format, *args):
        logger.info("%s - %s", self.address_string(), format % args)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--embedding-model-path", required=True)
    args = parser.parse_args()

    logger.info("加载 embedding 模型: %s", args.embedding_model_path)
    model = SentenceTransformer(args.embedding_model_path)
    logger.info("embedding 模型加载完成，启动 HTTP 服务: %s:%s", args.host, args.port)

    server = ThreadingHTTPServer((args.host, args.port), EmbeddingHandler)
    server.serve_forever()
