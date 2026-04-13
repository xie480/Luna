import axios from "axios";

const http = axios.create({
  baseURL: "http://localhost:8001",
  timeout: 600_000
});

// 統一管理 Token（僅存原始 JWT，不含 Bearer 前綴）
let authToken = null;

export function setAuthToken(token) {
  authToken = token || null;
}

export function getAuthToken() {
  return authToken;
}

export function getErrorMessage(error, fallback = "Request failed") {
  const responseData = error?.response?.data;

  if (typeof responseData === "string" && responseData.trim()) {
    return responseData.trim();
  }

  if (responseData && typeof responseData === "object") {
    const text =
      responseData.message ||
      responseData.reason ||
      responseData.error ||
      responseData.reply;

    if (typeof text === "string" && text.trim()) {
      return text.trim();
    }
  }

  if (typeof error?.message === "string" && error.message.trim()) {
    return error.message.trim();
  }

  return fallback;
}

function toBearerToken(token) {
  if (!token || typeof token !== "string") return "";
  return token.startsWith("Bearer ") ? token : `Bearer ${token}`;
}

function shouldSkipDataUnwrap(config) {
  const accept = String(config?.headers?.Accept || config?.headers?.accept || "");
  if (accept.includes("text/event-stream")) return true;
  if (config?.responseType === "stream") return true;
  return false;
}

// 統一日誌 / token / header
http.interceptors.request.use((config) => {
  console.log("[HTTP]", config.method?.toUpperCase(), config.url);

  if (authToken) {
    config.headers = config.headers || {};
    config.headers.Authorization = toBearerToken(authToken);
  }

  return config;
});

http.interceptors.response.use(
  (res) => {
    if (shouldSkipDataUnwrap(res.config)) {
      return res;
    }
    return res.data;
  },
  (err) => {
    console.error("[HTTP ERROR]", err.message);
    throw err;
  }
);

export default http;
