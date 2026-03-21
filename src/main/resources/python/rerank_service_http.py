import argparse
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from sentence_transformers import CrossEncoder

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("rerank-http")

model = None


def json_response(handler: BaseHTTPRequestHandler, status_code: int, payload: dict):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status_code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class RerankHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/rerank":
            json_response(self, 404, {"scores": [], "success": False, "error_message": "not found"})
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(content_length) if content_length > 0 else b"{}"
            req = json.loads(raw.decode("utf-8"))

            query = (req.get("query") or "").strip()
            docs = req.get("documents") or []

            if not query:
                json_response(self, 200, {"scores": [], "success": False, "error_message": "query is blank"})
                return

            if not docs:
                json_response(self, 200, {"scores": [], "success": True, "error_message": ""})
                return

            pairs = [[query, d] for d in docs]
            scores = model.predict(pairs).tolist()

            json_response(self, 200, {"scores": scores, "success": True, "error_message": ""})
        except Exception as e:
            logger.exception("rerank request failed")
            json_response(self, 200, {"scores": [], "success": False, "error_message": str(e)})

    def log_message(self, format, *args):
        logger.info("%s - %s", self.address_string(), format % args)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--rerank-model-path", required=True)
    args = parser.parse_args()

    logger.info("加载 rerank 模型: %s", args.rerank_model_path)
    model = CrossEncoder(args.rerank_model_path, max_length=1024, trust_remote_code=True)
    logger.info("rerank 模型加载完成，启动 HTTP 服务: %s:%s", args.host, args.port)

    server = ThreadingHTTPServer((args.host, args.port), RerankHandler)
    server.serve_forever()
